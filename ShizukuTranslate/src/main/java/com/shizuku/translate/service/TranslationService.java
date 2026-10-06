package com.shizuku.translate.service;

import com.shizuku.translate.dto.HistoryResponse;
import com.shizuku.translate.dto.SseStatusEvent;
import com.shizuku.translate.dto.TokenUsage;
import com.shizuku.translate.dto.TranslateRequest;
import com.shizuku.translate.dto.TranslateResponse;
import com.shizuku.translate.entity.TranslationCache;
import com.shizuku.translate.entity.TranslationRecord;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.exception.ResourceNotFoundException;
import com.shizuku.translate.integration.AiModelClient;
import com.shizuku.translate.integration.AiModelClient.AiModelConfig;
import com.shizuku.translate.integration.AiModelClient.DeepSeekResult;
import com.shizuku.translate.repository.TranslationCacheRepository;
import com.shizuku.translate.repository.TranslationRecordRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import org.springframework.web.multipart.MultipartFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    private final AiModelClient aiModelClient;
    private final TranslationRecordRepository recordRepository;
    private final TranslationCacheRepository cacheRepository;
    private final UserService userService;
    private final PromptTemplateService promptTemplateService;
    private final UsageService usageService;
    private final TranslationResultWriter resultWriter;
    private final com.shizuku.translate.service.feedback.TranslationFeedbackService feedbackService;

    public TranslationService(AiModelClient aiModelClient,
                              TranslationRecordRepository recordRepository,
                              TranslationCacheRepository cacheRepository,
                              UserService userService,
                              PromptTemplateService promptTemplateService,
                              UsageService usageService,
                              TranslationResultWriter resultWriter,
                              com.shizuku.translate.service.feedback.TranslationFeedbackService feedbackService) {
        this.aiModelClient = aiModelClient;
        this.recordRepository = recordRepository;
        this.cacheRepository = cacheRepository;
        this.userService = userService;
        this.promptTemplateService = promptTemplateService;
        this.usageService = usageService;
        this.resultWriter = resultWriter;
        this.feedbackService = feedbackService;
    }

    /**
     * Images travel in a single multimodal call, so this bound also caps the token cost per
     * request. Public because the streaming image endpoint validates the upload count before
     * the async job starts.
     */
    public static final int MAX_IMAGES_PER_REQUEST = 10;

    /**
     * Above this input length a translation is split into paragraph-aligned chunks. Derived
     * from observed behaviour: single-pass outputs that run into the tens of thousands of
     * tokens start dropping words, collapsing into telegraph style and drifting on names in
     * their last third, while per-chunk outputs of roughly half that size stay clean.
     */
    static final int CHUNK_UNIT_CHARS = 42000;

    /** Upper bound for pre-extracted terms; a runaway list must not inflate every chunk prompt. */
    static final int MAX_EXTRACTED_TERMS = 300;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Prompt for the pre-translation term scan (runs in thinking mode). */
    private static final String TERM_EXTRACT_SYSTEM =
            "你是术语管理助手。请从小说原文中提取所有需要统一译名的专有名词，覆盖三类：\n"
            + "1. 人物（本名、昵称、称呼方式）；2. 地名、组织、作品名；3. 自造词与特殊概念（术式、物品、种族、设定术语）。\n"
            + "对每个词给出：原文、建议的简体中文译名、类型（person/place/concept）。\n"
            + "以 JSON 数组输出：[{\"term\":\"原文\",\"translation\":\"译名\",\"type\":\"person\"}]\n"
            + "只输出 JSON 数组，不要任何其他文字。";

    /** Prompt for the post-translation terminology audit (runs in thinking mode). */
    private static final String TERM_AUDIT_SYSTEM =
            "你是翻译审校助手。给你一张术语表（原文→标准译名）和一篇已完成的译文。\n"
            + "任务：检查译文中是否出现了与标准译名不一致的写法（同一术语的不同译法）。\n"
            + "只报告明确属于同一术语却写法不同的情况，不要把意思相近的普通词误报为变体。\n"
            + "以 JSON 数组输出：[{\"standard\":\"标准译名\",\"variants\":[\"出现的不同写法\"]}]\n"
            + "如果没有任何不一致，输出 []。只输出 JSON。";

    /**
     * Image translation. The multimodal call and the database writes are deliberately kept
     * outside any transaction: see {@link TranslationResultWriter} for why an HTTP call must
     * never run inside a transaction (it pins a pooled JDBC connection for its whole duration).
     */
    public TranslateResponse translateImages(String username, TranslateRequest request, List<MultipartFile> images,
                                             boolean hideCustomPrompt) throws java.io.IOException {
        if (images == null || images.isEmpty()) {
            throw new com.shizuku.translate.exception.BusinessException("请至少上传一张图片");
        }
        if (images.size() > MAX_IMAGES_PER_REQUEST) {
            throw new com.shizuku.translate.exception.BusinessException(
                    "一次最多处理 " + MAX_IMAGES_PER_REQUEST + " 张图片");
        }
        User user = userService.findByUsername(username);
        AiModelConfig config = userService.resolveAiModelConfig(user, request.getModel(), request.getThinkingType(), request.getModelProfileId());
        if (!config.isVisual()) throw new com.shizuku.translate.exception.BusinessException("只有视觉模型才能使用模型处理");
        List<AiModelClient.ImagePayload> payloads = new ArrayList<>();
        for (MultipartFile image : images) {
            if (image == null || image.isEmpty()) continue;
            payloads.add(new AiModelClient.ImagePayload(image.getBytes(), image.getContentType()));
        }
        if (payloads.isEmpty()) {
            throw new com.shizuku.translate.exception.BusinessException("请至少上传一张图片");
        }
        String systemPrompt = promptTemplateService.buildSystemPrompt(PromptTemplateService.DEFAULT_TRANSLATE_PROMPT,
                request.getPresets(), request.getCustomPrompt(), request.getTargetLanguage());
        DeepSeekResult result = aiModelClient.chatWithImages(systemPrompt,
                buildImageUserMessage(request.getSourceText(), payloads.size()), payloads, config);
        // Image pages have no stable source text, so the result is recorded without a target
        // language and deliberately never shared — only exact-text matches are reusable.
        return resultWriter.persistTranslate(user, config, result.getUsage(), result.getContent(),
                request.getSourceText() == null ? "" : request.getSourceText(),
                hideCustomPrompt ? null : request.getCustomPrompt());
    }

    /**
     * Streaming variant of {@link #translateImages}: tokens reach the caller while the model is
     * still working, which is what lets the web UI render pages as they are translated. The
     * finished text is recorded exactly like the non-streaming path — same prompt, same
     * "images carry no stable source text, so no target language and no sharing" rule.
     */
    public void translateImagesStream(String username, TranslateRequest request,
                                      List<AiModelClient.ImagePayload> payloads, boolean hideCustomPrompt,
                                      Consumer<String> onToken, Consumer<TranslateResponse> onComplete,
                                      Consumer<String> onError, Runnable onUpstreamConnected,
                                      BooleanSupplier cancelled) {
        if (payloads == null || payloads.isEmpty()) {
            throw new com.shizuku.translate.exception.BusinessException("请至少上传一张图片");
        }
        if (payloads.size() > MAX_IMAGES_PER_REQUEST) {
            throw new com.shizuku.translate.exception.BusinessException(
                    "一次最多处理 " + MAX_IMAGES_PER_REQUEST + " 张图片");
        }
        User user = userService.findByUsername(username);
        AiModelConfig config = userService.resolveAiModelConfig(user, request.getModel(), request.getThinkingType(), request.getModelProfileId());
        if (!config.isVisual()) throw new com.shizuku.translate.exception.BusinessException("只有视觉模型才能使用模型处理");
        String systemPrompt = promptTemplateService.buildSystemPrompt(PromptTemplateService.DEFAULT_TRANSLATE_PROMPT,
                request.getPresets(), request.getCustomPrompt(), request.getTargetLanguage());
        StringBuilder fullText = new StringBuilder();
        aiModelClient.chatStreamWithImages(systemPrompt,
                buildImageUserMessage(request.getSourceText(), payloads.size()), payloads, config,
                token -> {
                    fullText.append(token);
                    onToken.accept(token);
                },
                usage -> {
                    // A stream that ends without a single token means the model returned nothing
                    // (filtered page, upstream hiccup). Recording that as a finished translation
                    // would show the user an empty result after a long wait, so it is reported as
                    // a failure instead. The text path is left alone on purpose: the browser
                    // extension consumes it and this guard is a behaviour change.
                    if (fullText.length() == 0) {
                        onError.accept("模型没有返回任何内容，请重试");
                        return;
                    }
                    TranslateResponse response = resultWriter.persistTranslate(user, config, usage,
                            fullText.toString(),
                            request.getSourceText() == null ? "" : request.getSourceText(),
                            hideCustomPrompt ? null : request.getCustomPrompt());
                    onComplete.accept(response);
                },
                error -> onError.accept(error),
                onUpstreamConnected,
                cancelled);
    }

    /**
     * Multi-image uploads are consecutive pages of one work, so the model is told to merge them
     * into a single continuous translation instead of treating each page as a separate document.
     */
    private static String buildImageUserMessage(String sourceText, int imageCount) {
        String text = sourceText == null ? "" : sourceText;
        if (imageCount <= 1) {
            return text;
        }
        String hint = "（本次共 " + imageCount + " 张图片，属于同一部作品的连续页面，"
                + "请按上传顺序合并为一段完整译文，不要逐张分开输出。）";
        return text.isBlank() ? hint : text + "\n\n" + hint;
    }

    /**
     * Non-streaming translation. Deliberately NOT transactional: the upstream model call can
     * take minutes and must not hold a pooled JDBC connection while it runs. Database writes
     * happen afterwards in {@link TranslationResultWriter#persistTranslate}, which owns a
     * short transaction.
     */
    public TranslateResponse translate(String username, TranslateRequest request, boolean hideCustomPrompt) {
        requireSourceText(request);
        long startedAt = System.currentTimeMillis();
        // A re-translation is a dissatisfaction signal for the previous result: record it
        // (and keep that sample in full) before anything else can fail.
        feedbackService.onRetranslate(request.getRetranslatedFrom());
        User user = userService.findByUsername(username);
        String resolvedTargetLanguage = promptTemplateService.resolveTargetLanguage(request.getTargetLanguage());

        // Reuse someone else's translation before spending tokens on the model. Resolving the
        // target language here (rather than re-deriving it from the prompt) keeps the column
        // value and the lookup key identical for writer and reader.
        if (!request.isSkipCache()) {
            TranslationRecord shared = recordRepository
                    .findFirstByUserIdNotAndSourceTextAndTargetLanguageOrderByCreatedAtDesc(
                            user.getId(), request.getSourceText(), resolvedTargetLanguage)
                    .orElse(null);
            if (shared != null) {
                log.info("Serving shared translation for user {} from record {}", user.getId(), shared.getId());
                TranslateResponse response = sharedResponse(shared);
                // The replay creates no record of its own; a fresh correlation id still lets the
                // result be rated, and the pending registration keeps this exact text so a low
                // rating can retain the sample in full.
                response.setRequestId(java.util.UUID.randomUUID().toString());
                feedbackService.registerTranslation(response.getRequestId(), request.getSourceText(),
                        shared.getTranslatedText(), "shared", shared.getModel(), null,
                        resolvedTargetLanguage, System.currentTimeMillis() - startedAt, false);
                return response;
            }
        }

        String systemPrompt = promptTemplateService.buildSystemPrompt(
                PromptTemplateService.DEFAULT_TRANSLATE_PROMPT,
                request.getPresets(),
                request.getCustomPrompt(),
                request.getTargetLanguage()
        );
        AiModelConfig config = userService.resolveAiModelConfig(user, request.getModel(), request.getThinkingType(), request.getModelProfileId());

        DeepSeekResult result = aiModelClient.chat(systemPrompt, request.getSourceText(), config);

        TranslateResponse response = resultWriter.persistTranslate(user, config, result.getUsage(), result.getContent(),
                request.getSourceText(), hideCustomPrompt ? null : request.getCustomPrompt(),
                resolvedTargetLanguage);
        feedbackService.registerTranslation(response.getRequestId(), request.getSourceText(),
                result.getContent(), config.getProvider(), config.getModel(), config.getThinkingType(),
                resolvedTargetLanguage, System.currentTimeMillis() - startedAt, true);
        return response;
    }

    /** Builds the response for a translation reused from another user's history. */
    private static TranslateResponse sharedResponse(TranslationRecord shared) {
        TranslateResponse response = new TranslateResponse();
        response.setId(shared.getId());
        response.setTranslatedText(shared.getTranslatedText());
        response.setModel(shared.getModel());
        response.setCreatedAt(shared.getCreatedAt());
        response.setFromSharedTranslation(true);
        return response;
    }

    public Page<HistoryResponse> getHistory(String username, Pageable pageable) {
        User user = userService.findByUsername(username);
        return recordRepository.findByUserIdOrderByCreatedAtDesc(user.getId(), pageable)
                .map(this::toHistoryResponse);
    }

    public HistoryResponse getDetail(Long id, String username) {
        User user = userService.findByUsername(username);
        TranslationRecord record = recordRepository.findByIdAndUserId(id, user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Record not found"));
        return toHistoryResponse(record);
    }

    private HistoryResponse toHistoryResponse(TranslationRecord rec) {
        HistoryResponse r = new HistoryResponse();
        r.setId(rec.getId());
        r.setSourceText(rec.getSourceText());
        r.setTranslatedText(rec.getTranslatedText());
        r.setModel(rec.getModel());
        r.setCustomPrompt(rec.getCustomPrompt());
        r.setCreatedAt(rec.getCreatedAt());
        return r;
    }

    public void translateStream(String username, TranslateRequest request, boolean hideCustomPrompt,
                                Consumer<String> onToken, Consumer<TranslateResponse> onComplete,
                                Consumer<String> onError, Runnable onUpstreamConnected) {
        translateStream(username, request, hideCustomPrompt, onToken, onComplete, onError,
                onUpstreamConnected, () -> Thread.currentThread().isInterrupted());
    }

    public void translateStream(String username, TranslateRequest request, boolean hideCustomPrompt,
                                Consumer<String> onToken, Consumer<TranslateResponse> onComplete,
                                Consumer<String> onError, Runnable onUpstreamConnected,
                                BooleanSupplier cancelled) {
        translateStream(username, request, hideCustomPrompt, onToken, onComplete, onError,
                onUpstreamConnected, cancelled, status -> { });
    }

    /** Overload that also reports long-novel pipeline progress through {@code onStatus}. */
    public void translateStream(String username, TranslateRequest request, boolean hideCustomPrompt,
                                Consumer<String> onToken, Consumer<TranslateResponse> onComplete,
                                Consumer<String> onError, Runnable onUpstreamConnected,
                                BooleanSupplier cancelled, Consumer<SseStatusEvent> onStatus) {
        requireSourceText(request);
        long startedAt = System.currentTimeMillis();
        feedbackService.onRetranslate(request.getRetranslatedFrom());
        User user = userService.findByUsername(username);

        String systemPrompt = promptTemplateService.buildSystemPrompt(
                PromptTemplateService.DEFAULT_STREAM_PROMPT,
                request.getPresets(),
                request.getCustomPrompt(),
                request.getTargetLanguage()
        );

        AiModelConfig config = userService.resolveAiModelConfig(user, request.getModel(), request.getThinkingType(), request.getModelProfileId());
        String cacheKey = buildCacheKey(user.getId(), config, systemPrompt, request.getSourceText());
        if (cancelled.getAsBoolean()) {
            return;
        }
        // Resolved once so the shared-translation lookup key, the history row and the response
        // all describe the same language.
        String resolvedTargetLanguage = promptTemplateService.resolveTargetLanguage(request.getTargetLanguage());

        // Reuse another user's translation of the same text into the same language before
        // checking anything else: it saves the caller tokens, and unlike the personal cache it
        // is interesting information even when the user has their own cached copy.
        if (!request.isSkipCache()) {
            TranslationRecord shared = recordRepository
                    .findFirstByUserIdNotAndSourceTextAndTargetLanguageOrderByCreatedAtDesc(
                            user.getId(), request.getSourceText(), resolvedTargetLanguage)
                    .orElse(null);
            if (shared != null) {
                log.info("Serving shared translation for user {} from record {}", user.getId(), shared.getId());
                onToken.accept(shared.getTranslatedText());

                TranslationRecord ownRecord = new TranslationRecord();
                ownRecord.setUser(user);
                ownRecord.setSourceText(request.getSourceText());
                ownRecord.setTranslatedText(shared.getTranslatedText());
                ownRecord.setModel(config.getModel());
                ownRecord.setCustomPrompt(hideCustomPrompt ? null : request.getCustomPrompt());
                ownRecord.setTargetLanguage(resolvedTargetLanguage);
                ownRecord.setRequestId(java.util.UUID.randomUUID().toString());
                ownRecord = recordRepository.save(ownRecord);

                TranslateResponse response = new TranslateResponse();
                response.setId(ownRecord.getId());
                response.setTranslatedText(shared.getTranslatedText());
                response.setModel(config.getModel());
                response.setCreatedAt(ownRecord.getCreatedAt());
                response.setFromSharedTranslation(true);
                response.setRequestId(ownRecord.getRequestId());
                feedbackService.registerTranslation(ownRecord.getRequestId(), request.getSourceText(),
                        shared.getTranslatedText(), "shared", shared.getModel(), null,
                        resolvedTargetLanguage, System.currentTimeMillis() - startedAt, false);
                onComplete.accept(response);
                return;
            }
        }

        TranslationCache cached = null;
        if (!request.isSkipCache()) {
            java.util.List<TranslationCache> cachedEntries =
                    cacheRepository.findByUserIdAndCacheKeyOrderByCreatedAtDesc(user.getId(), cacheKey);
            if (!cachedEntries.isEmpty()) {
                cached = cachedEntries.get(0);
                if (cachedEntries.size() > 1) {
                    log.warn("Multiple translation cache entries for user {}, key {}; using newest", user.getId(), cacheKey.substring(0, 12));
                }
            }
        }
        if (cached != null) {
            log.info("Translation cache hit for user {}, key {}", user.getId(), cacheKey.substring(0, 12));
            onToken.accept(cached.getTranslatedText());

            TranslationRecord record = new TranslationRecord();
            record.setUser(user);
            record.setSourceText(request.getSourceText());
            record.setTranslatedText(cached.getTranslatedText());
            record.setModel(config.getModel());
            record.setCustomPrompt(hideCustomPrompt ? null : request.getCustomPrompt());
            record.setTargetLanguage(promptTemplateService.resolveTargetLanguage(request.getTargetLanguage()));
            record.setRequestId(java.util.UUID.randomUUID().toString());
            record = recordRepository.save(record);

            TranslateResponse response = new TranslateResponse();
            response.setId(record.getId());
            response.setTranslatedText(cached.getTranslatedText());
            response.setModel(config.getModel());
            response.setCreatedAt(record.getCreatedAt());
            response.setFromCache(true);
            response.setRequestId(record.getRequestId());
            // Older cache rows may miss individual token counts; unboxing a
            // null field must not crash a cache-hit replay.
            if (cached.getTotalTokens() != null && cached.getTotalTokens() > 0) {
                TokenUsage usage = new TokenUsage();
                usage.setPromptTokens(cached.getPromptTokens() != null ? cached.getPromptTokens() : 0);
                usage.setCompletionTokens(cached.getCompletionTokens() != null ? cached.getCompletionTokens() : 0);
                usage.setTotalTokens(cached.getTotalTokens());
                response.setTokenUsage(usage);
            }
            feedbackService.registerTranslation(record.getRequestId(), request.getSourceText(),
                    cached.getTranslatedText(), "cache", cached.getModel(), null,
                    resolvedTargetLanguage, System.currentTimeMillis() - startedAt, false);
            onComplete.accept(response);
            return;
        }

        // Long texts go through the chunked pipeline: one request per paragraph-aligned chunk
        // on a single continuous token stream (see CHUNK_UNIT_CHARS for the why).
        if (request.getSourceText().length() > CHUNK_UNIT_CHARS) {
            translateChunked(user, request, config, systemPrompt, resolvedTargetLanguage, cacheKey,
                    hideCustomPrompt, startedAt, onToken, onComplete, onError, cancelled, onStatus);
            return;
        }

        StringBuilder fullText = new StringBuilder();

        aiModelClient.chatStream(
                systemPrompt,
                request.getSourceText(),
                config,
                token -> {
                    fullText.append(token);
                    onToken.accept(token);
                },
                usage -> {
                    finalizeStreamedTranslation(user, config, usage, fullText.toString(),
                            request.getSourceText(), request.getCustomPrompt(), hideCustomPrompt,
                            resolvedTargetLanguage, cacheKey, startedAt, onComplete);
                },
                error -> onError.accept(error),
                onUpstreamConnected,
                cancelled
        );
    }

    // ===== long-novel chunked pipeline =====

    /**
     * Translates a long text as paragraph-aligned chunks on one continuous token stream.
     *
     * <p>Each chunk request carries the tails of the previous chunk (source and translation),
     * the way a multi-turn conversation carries its history, so names, pronouns and tone stay
     * attached across the seams even though no single request ever sees the whole book. With
     * {@code novelTermFix} enabled the text is first scanned for proper nouns and coined terms
     * (the table is injected into every chunk) and the finished translation is audited for
     * terminology drift afterwards.
     */
    private void translateChunked(User user, TranslateRequest request, AiModelConfig config,
                                  String systemPrompt, String resolvedTargetLanguage, String cacheKey,
                                  boolean hideCustomPrompt, long startedAt,
                                  Consumer<String> onToken, Consumer<TranslateResponse> onComplete,
                                  Consumer<String> onError, BooleanSupplier cancelled,
                                  Consumer<SseStatusEvent> onStatus) {
        String sourceText = request.getSourceText();
        List<String> chunks = splitIntoChunks(sourceText, CHUNK_UNIT_CHARS);
        List<String> translatedChunks = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        TokenUsage mergedUsage = null;

        // Stage 1 — pre-extract proper nouns / coined terms so every chunk renders them the
        // same way.
        List<TermPair> terms = List.of();
        if (request.isNovelTermFix()) {
            onStatus.accept(new SseStatusEvent("long-novel", "extract", null, null, null, null));
            try {
                terms = extractTerms(config, sourceText);
            } catch (Exception e) {
                log.warn("Term pre-extraction failed; continuing without it", e);
            }
            onStatus.accept(new SseStatusEvent("long-novel", "extract-done", null, null, null, terms.size()));
        }

        String effectivePrompt = systemPrompt;
        if (!terms.isEmpty()) {
            effectivePrompt = systemPrompt + "\n\n请特别注意以下要求：\n- " + renderTermBlock(terms);
        }

        // Stage 2 — translate chunk by chunk; tokens keep flowing through the same stream.
        for (int i = 0; i < chunks.size(); i++) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            onStatus.accept(new SseStatusEvent("long-novel", "translate", null, i + 1, chunks.size(), null));
            StringBuilder piece = new StringBuilder();
            TokenUsage[] chunkUsage = new TokenUsage[1];
            String[] chunkError = new String[1];
            aiModelClient.chatStream(effectivePrompt, buildChunkUserMessage(chunks, translatedChunks, i), config,
                    token -> {
                        piece.append(token);
                        onToken.accept(token);
                    },
                    usage -> chunkUsage[0] = usage,
                    error -> chunkError[0] = error,
                    () -> { },
                    cancelled);
            if (chunkError[0] != null) {
                onError.accept(chunkError[0]);
                return;
            }
            if (piece.length() == 0) {
                onError.accept("第 " + (i + 1) + "/" + chunks.size() + " 块没有返回内容，请重试");
                return;
            }
            translatedChunks.add(piece.toString());
            fullText.append(piece);
            mergedUsage = mergeUsage(mergedUsage, chunkUsage[0]);
        }

        // Stage 3 — audit the finished translation for terminology drift and repair it.
        String finalText = fullText.toString();
        if (request.isNovelTermFix() && !terms.isEmpty()) {
            onStatus.accept(new SseStatusEvent("long-novel", "audit", null, null, null, null));
            try {
                AuditOutcome outcome = auditTerminology(config, finalText, terms);
                finalText = outcome.correctedText();
                onStatus.accept(new SseStatusEvent("long-novel", "audit-done", null, null, null, outcome.fixes()));
            } catch (Exception e) {
                log.warn("Terminology audit failed; keeping the translation as-is", e);
                onStatus.accept(new SseStatusEvent("long-novel", "audit-skipped", null, null, null, null));
            }
        }

        if (cancelled.getAsBoolean()) {
            return;
        }
        finalizeStreamedTranslation(user, config, mergedUsage, finalText, sourceText,
                request.getCustomPrompt(), hideCustomPrompt, resolvedTargetLanguage, cacheKey,
                startedAt, onComplete);
    }

    /** The shared tail of a streamed translation: usage, history row, feedback, cache, response. */
    private void finalizeStreamedTranslation(User user, AiModelConfig config, TokenUsage usage,
                                             String translatedText, String sourceText, String customPrompt,
                                             boolean hideCustomPrompt, String resolvedTargetLanguage,
                                             String cacheKey, long startedAt,
                                             Consumer<TranslateResponse> onComplete) {
        usageService.record(user, config, usage);

        TranslationRecord record = new TranslationRecord();
        record.setUser(user);
        record.setSourceText(sourceText);
        record.setTranslatedText(translatedText);
        record.setModel(config.getModel());
        record.setCustomPrompt(hideCustomPrompt ? null : customPrompt);
        record.setTargetLanguage(resolvedTargetLanguage);
        record.setRequestId(java.util.UUID.randomUUID().toString());
        record = recordRepository.save(record);

        TranslateResponse response = new TranslateResponse();
        response.setId(record.getId());
        response.setTranslatedText(translatedText);
        response.setModel(config.getModel());
        response.setCreatedAt(record.getCreatedAt());
        response.setRequestId(record.getRequestId());
        if (usage != null) {
            response.setTokenUsage(usage);
        }
        feedbackService.registerTranslation(record.getRequestId(), sourceText, translatedText,
                config.getProvider(), config.getModel(), config.getThinkingType(),
                resolvedTargetLanguage, System.currentTimeMillis() - startedAt, true);
        try {
            TranslationCache cache = TranslationCache.builder()
                    .userId(user.getId())
                    .cacheKey(cacheKey)
                    .model(config.getModel())
                    .translatedText(translatedText)
                    .promptTokens(usage != null ? usage.getPromptTokens() : null)
                    .completionTokens(usage != null ? usage.getCompletionTokens() : null)
                    .totalTokens(usage != null ? usage.getTotalTokens() : null)
                    .build();
            cacheRepository.save(cache);
        } catch (Exception e) {
            log.warn("Failed to write translation cache", e);
        }
        onComplete.accept(response);
    }

    /**
     * Splits {@code text} into paragraph-aligned chunks of roughly {@code chunkUnit} characters,
     * never breaking inside a line: every boundary is pulled forward to the next newline, so a
     * chunk always ends where a line (usually a paragraph) ends.
     */
    static List<String> splitIntoChunks(String text, int chunkUnit) {
        int total = text.length();
        int count = Math.max(1, (int) Math.ceil(total / (double) chunkUnit));
        int target = (int) Math.ceil(total / (double) count);
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < total) {
            int remaining = count - out.size();
            if (remaining <= 1) {
                out.add(text.substring(start));
                break;
            }
            int targetEnd = Math.min(total, start + target);
            int end = targetEnd;
            if (targetEnd < total) {
                int newline = text.indexOf('\n', targetEnd);
                end = (newline >= 0) ? newline + 1 : total;
            }
            out.add(text.substring(start, end));
            start = end;
        }
        return out;
    }

    /**
     * User message for one chunk: the previous chunk's source and translation tails serve as the
     * conversation history (what happened, how it was phrased), then the text to translate.
     */
    static String buildChunkUserMessage(List<String> chunks, List<String> translatedSoFar, int index) {
        String current = chunks.get(index);
        if (index == 0) {
            return current;
        }
        String prevSource = tail(chunks.get(index - 1), 400);
        String prevTranslation = tail(translatedSoFar.get(index - 1), 400);
        return "【前文末尾，仅供衔接参考，请勿重复翻译】\n原文：…" + prevSource
                + "\n译文：…" + prevTranslation
                + "\n\n【请从下方内容开始继续翻译，保持人称、术语、语气与前文完全一致】\n" + current;
    }

    private static String tail(String value, int max) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value.length() <= max ? value : value.substring(value.length() - max);
    }

    private static TokenUsage mergeUsage(TokenUsage a, TokenUsage b) {
        if (b == null) return a;
        if (a == null) return b;
        TokenUsage merged = new TokenUsage();
        merged.setPromptTokens(safeInt(a.getPromptTokens()) + safeInt(b.getPromptTokens()));
        merged.setCompletionTokens(safeInt(a.getCompletionTokens()) + safeInt(b.getCompletionTokens()));
        merged.setTotalTokens(safeInt(a.getTotalTokens()) + safeInt(b.getTotalTokens()));
        return merged;
    }

    private static int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    /** One extracted proper noun / coined term with its mandated rendering. */
    record TermPair(String source, String translation) {}

    /** Scans the whole text for proper nouns and coined terms (thinking mode; short output). */
    private List<TermPair> extractTerms(AiModelConfig config, String sourceText) {
        AiModelConfig thinking = new AiModelConfig(config.getProvider(), config.getApiKey(),
                config.getBaseUrl(), config.getModel(), "enabled");
        AiModelClient.DeepSeekResult result = aiModelClient.chat(TERM_EXTRACT_SYSTEM,
                "请提取以下原文中的专有名词：\n\n" + sourceText, thinking);
        List<TermPair> terms = parseTermPairs(result.getContent());
        log.info("Pre-extracted {} terms for a {} character text", terms.size(), sourceText.length());
        return terms;
    }

    /** Parses term JSON, tolerating prose around the array; an unparsable answer yields none. */
    static List<TermPair> parseTermPairs(String content) {
        List<TermPair> out = new ArrayList<>();
        String json = extractJsonArray(content);
        if (json == null) {
            return out;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode arr = JSON.readTree(json);
            if (arr.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : arr) {
                    String term = node.path("term").asText("").trim();
                    String translation = node.path("translation").asText("").trim();
                    if (!term.isEmpty() && !translation.isEmpty() && out.size() < MAX_EXTRACTED_TERMS) {
                        out.add(new TermPair(term, translation));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse term extraction output; continuing without terms", e);
        }
        return out;
    }

    /** Renders the extracted table as one system-prompt line shared by every chunk. */
    static String renderTermBlock(List<TermPair> terms) {
        StringBuilder block = new StringBuilder("【本文专有名词对照表】（自动提取，翻译时必须严格遵循这些译名）：");
        for (TermPair term : terms) {
            block.append(term.source()).append("→").append(term.translation()).append("、");
        }
        if (block.charAt(block.length() - 1) == '、') {
            block.setLength(block.length() - 1);
        }
        return block.append("。").toString();
    }

    /** Result of the post-translation audit: the repaired text plus how many occurrences changed. */
    record AuditOutcome(String correctedText, int fixes) {}

    /**
     * Audits the finished translation for terminology drift and repairs it deterministically: the
     * model only points out mismatched renderings, the actual replacements are plain string
     * edits, so a flaky audit answer can never rewrite arbitrary prose.
     */
    private AuditOutcome auditTerminology(AiModelConfig config, String translatedText, List<TermPair> terms) {
        AiModelConfig thinking = new AiModelConfig(config.getProvider(), config.getApiKey(),
                config.getBaseUrl(), config.getModel(), "enabled");
        StringBuilder termList = new StringBuilder();
        for (TermPair term : terms) {
            termList.append(term.source()).append(" → ").append(term.translation()).append('\n');
        }
        AiModelClient.DeepSeekResult result = aiModelClient.chat(TERM_AUDIT_SYSTEM,
                "【术语表】\n" + termList + "\n【译文】\n" + translatedText, thinking);
        String text = translatedText;
        int fixes = 0;
        for (String[] pair : parseAuditFindings(result.getContent())) {
            String standard = pair[0];
            String variant = pair[1];
            if (variant.equals(standard) || !text.contains(variant)) {
                continue;
            }
            int count = 0;
            int index = 0;
            while ((index = text.indexOf(variant, index)) >= 0) {
                count++;
                index += variant.length();
            }
            if (count > 0) {
                text = text.replace(variant, standard);
                fixes += count;
            }
        }
        return new AuditOutcome(text, fixes);
    }

    /** Parses audit JSON into {@code [standard, variant]} replacement pairs. */
    static List<String[]> parseAuditFindings(String content) {
        List<String[]> out = new ArrayList<>();
        String json = extractJsonArray(content);
        if (json == null) {
            return out;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode arr = JSON.readTree(json);
            if (arr.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode node : arr) {
                    String standard = node.path("standard").asText("").trim();
                    if (standard.isEmpty()) {
                        continue;
                    }
                    for (com.fasterxml.jackson.databind.JsonNode variantNode : node.path("variants")) {
                        String variant = variantNode.asText("").trim();
                        // Conservative: skip empties, no-op replacements and single characters, so a
                        // hallucinated "variant" cannot shred the text via a common short string.
                        if (!variant.isEmpty() && !variant.equals(standard) && variant.length() >= 2) {
                            out.add(new String[]{standard, variant});
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse audit output; keeping the translation as-is", e);
        }
        return out;
    }

    /** Returns the widest {@code [...]} span in the content, or null when there is none. */
    private static String extractJsonArray(String content) {
        if (content == null) {
            return null;
        }
        int open = content.indexOf('[');
        int close = content.lastIndexOf(']');
        if (open < 0 || close <= open) {
            return null;
        }
        return content.substring(open, close + 1);
    }

    private void requireSourceText(TranslateRequest request) {
        if (request.getSourceText() == null || request.getSourceText().isBlank()) {
            throw new com.shizuku.translate.exception.BusinessException("请输入要翻译的文本，或上传图片");
        }
    }

    private String buildCacheKey(Long userId, AiModelConfig config, String systemPrompt, String sourceText) {
        String raw = userId + "|" + config.getProvider() + "|" + config.getBaseUrl()
                + "|" + config.getModel() + "|" + systemPrompt + "|" + sourceText;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpiredCache() {
        int deleted = cacheRepository.deleteByCreatedAtBefore(LocalDateTime.now().minusDays(30));
        if (deleted > 0) {
            log.info("Cleaned {} expired translation cache entries", deleted);
        }
    }
}
