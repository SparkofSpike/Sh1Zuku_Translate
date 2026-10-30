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
import com.shizuku.translate.service.longform.ChunkedTranslationSupport;
import com.shizuku.translate.service.longform.TerminologyService;
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
    private final TerminologyService terminologyService;

    public TranslationService(AiModelClient aiModelClient,
                              TranslationRecordRepository recordRepository,
                              TranslationCacheRepository cacheRepository,
                              UserService userService,
                              PromptTemplateService promptTemplateService,
                              UsageService usageService,
                              TranslationResultWriter resultWriter,
                              com.shizuku.translate.service.feedback.TranslationFeedbackService feedbackService,
                              TerminologyService terminologyService) {
        this.aiModelClient = aiModelClient;
        this.recordRepository = recordRepository;
        this.cacheRepository = cacheRepository;
        this.userService = userService;
        this.promptTemplateService = promptTemplateService;
        this.usageService = usageService;
        this.resultWriter = resultWriter;
        this.feedbackService = feedbackService;
        this.terminologyService = terminologyService;
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

    /**
     * Chunk length for models whose single-request output cap is small. Measured against
     * Index-Translate on 2026-10-22: the endpoint reports {@code completion_tokens=4096} and
     * {@code finish_reason=length} no matter what {@code max_tokens} we ask for, so 8192 is
     * not actually available. On ordinary varied text it runs ~0.44 output tokens per Japanese
     * character (800 chars → 346, 1000 → 444), so 4000 chars land around 1800 tokens with
     * headroom; a 42k-char chunk could never fit. Text that repeats the same sentence many
     * times drives the model into a runaway loop (2000 chars → 6841 output chars → capped),
     * which is another reason to leave a wide margin rather than size the chunk to the limit.
     */
    static final int CAPPED_MODEL_CHUNK_UNIT_CHARS = 4000;

    /** Picks the chunk length that the model's output cap can actually hold. */
    static int chunkUnitFor(AiModelConfig config) {
        return config != null && (config.isAnthropic() || config.isIndexTranslate())
                ? CAPPED_MODEL_CHUNK_UNIT_CHARS : CHUNK_UNIT_CHARS;
    }

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
        TranslateResponse response = resultWriter.persistTranslate(user, config, result.getUsage(), result.getContent(),
                request.getSourceText() == null ? "" : request.getSourceText(),
                hideCustomPrompt ? null : request.getCustomPrompt(), null, result.isTruncated());
        // Image pages have no stable source text, so the result is recorded without a target
        // language and deliberately never shared — only exact-text matches are reusable.
        // Set on the response here as well: the flag is a property of this answer, not of
        // whatever the writer chose to echo back.
        response.setTruncated(result.isTruncated());
        return response;
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
                    TranslateResponse response = resultWriter.persistTranslate(user, config, usage.usage(),
                            fullText.toString(),
                            request.getSourceText() == null ? "" : request.getSourceText(),
                            hideCustomPrompt ? null : request.getCustomPrompt(),
                            null, usage.truncated());
                    // finish_reason=length means the provider stopped at its output cap: the text
                    // above is a prefix of the translation, not the whole of it.
                    response.setTruncated(usage.truncated());
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
        // value and the lookup key identical for writer and reader. Thinking mode skips this
        // replay: a caller who chose thinking is asking for a fresh, higher-effort result.
        if (!request.isSkipCache() && !thinkingRequested(request)) {
            TranslationRecord shared = recordRepository
                    .findFirstByUserIdNotAndSourceTextAndTargetLanguageOrderByCreatedAtDesc(
                            user.getId(), request.getSourceText(), resolvedTargetLanguage)
                    .orElse(null);
            if (shared != null && isShareable(shared)) {
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
                resolvedTargetLanguage, result.isTruncated());
        // A capped answer is a prefix of the translation; the caller has to be able to say so
        // instead of presenting half a novel as the finished work.
        response.setTruncated(result.isTruncated());
        feedbackService.registerTranslation(response.getRequestId(), request.getSourceText(),
                result.getContent(), config.getProvider(), config.getModel(), config.getThinkingType(),
                resolvedTargetLanguage, System.currentTimeMillis() - startedAt, true);
        return response;
    }

    /**
     * Whether a stored translation may be served to another user. A capped (truncated) row is a
     * prefix of the translation, so replaying it would hand someone half a novel labelled as a
     * finished one — those rows stay visible to their owner only.
     */
    static boolean isShareable(TranslationRecord record) {
        return record != null && !Boolean.TRUE.equals(record.getTruncated());
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
        // Rows written before the column existed are null, which reads as "not truncated".
        r.setTruncated(Boolean.TRUE.equals(rec.getTruncated()));
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
        // is interesting information even when the user has their own cached copy. Thinking
        // mode skips this replay: a caller who chose thinking wants a fresh result.
        if (!request.isSkipCache() && !thinkingRequested(request)) {
            TranslationRecord shared = recordRepository
                    .findFirstByUserIdNotAndSourceTextAndTargetLanguageOrderByCreatedAtDesc(
                            user.getId(), request.getSourceText(), resolvedTargetLanguage)
                    .orElse(null);
            if (shared != null && isShareable(shared)) {
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
        // on a single continuous token stream. The threshold is the model's own chunk size, not
        // a fixed constant: models capped at 8192 output tokens (Index-Translate, Anthropic)
        // cannot finish a 20k-char request in one call, so they must be split far earlier.
        // The coined-term correction also routes texts through that pipeline (pre-extraction and
        // the audit pass), so asking for it works on short texts too.
        if (request.getSourceText().length() > chunkUnitFor(config) || request.isNovelTermFix()) {
            translateChunked(user, request, config, systemPrompt, resolvedTargetLanguage, cacheKey,
                    hideCustomPrompt, startedAt, onToken, onComplete, onError, cancelled, onStatus);
            return;
        }

        StringBuilder fullText = new StringBuilder();

        aiModelClient.chatStreamTracked(
                systemPrompt,
                request.getSourceText(),
                config,
                token -> {
                    fullText.append(token);
                    onToken.accept(token);
                },
                completion -> {
                    finalizeStreamedTranslation(user, config, completion.usage(), fullText.toString(),
                            request.getSourceText(), request.getCustomPrompt(), hideCustomPrompt,
                            resolvedTargetLanguage, cacheKey, startedAt, onComplete, completion.truncated());
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
        List<String> chunks = ChunkedTranslationSupport.splitIntoChunks(sourceText, chunkUnitFor(config));
        List<String> translatedChunks = new ArrayList<>();
        StringBuilder fullText = new StringBuilder();
        TokenUsage mergedUsage = null;
        // One capped chunk makes the assembled text a prefix of the translation.
        boolean truncated = false;

        // Stage 1 — pre-extract proper nouns / coined terms so every chunk renders them the
        // same way.
        List<TerminologyService.TermPair> terms = List.of();
        if (request.isNovelTermFix()) {
            onStatus.accept(new SseStatusEvent("long-novel", "extract", null, null, null, null));
            try {
                terms = terminologyService.extractTerms(config, sourceText);
            } catch (Exception e) {
                log.warn("Term pre-extraction failed; continuing without it", e);
            }
            onStatus.accept(new SseStatusEvent("long-novel", "extract-done", null, null, null, terms.size()));
        }

        String effectivePrompt = systemPrompt;
        if (!terms.isEmpty()) {
            effectivePrompt = systemPrompt + "\n\n请特别注意以下要求：\n- " + TerminologyService.renderTermBlock(terms);
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
            boolean[] chunkTruncated = new boolean[1];
            aiModelClient.chatStreamTracked(effectivePrompt, ChunkedTranslationSupport.buildChunkUserMessage(chunks, translatedChunks, i), config,
                    token -> {
                        piece.append(token);
                        onToken.accept(token);
                    },
                    completion -> {
                        chunkUsage[0] = completion.usage();
                        chunkTruncated[0] = completion.truncated();
                    },
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
            // One capped chunk is enough to make the whole assembled text a prefix: the seam is
            // lost, so the result as a whole must be reported as partial.
            truncated = truncated || chunkTruncated[0];
            translatedChunks.add(piece.toString());
            fullText.append(piece);
            mergedUsage = mergeUsage(mergedUsage, chunkUsage[0]);
        }

        // Stage 3 — audit the finished translation for terminology drift and repair it.
        String finalText = fullText.toString();
        if (request.isNovelTermFix() && !terms.isEmpty()) {
            onStatus.accept(new SseStatusEvent("long-novel", "audit", null, null, null, null));
            TerminologyService.AuditOutcome outcome = null;
            try {
                outcome = terminologyService.auditTerminology(config, finalText, terms);
            } catch (Exception e) {
                log.warn("Terminology audit failed; keeping the translation as-is", e);
            }
            if (outcome != null) {
                finalText = outcome.correctedText();
                // Kept outside the try above: a client disconnect while reporting the result
                // must surface as a disconnect (logged as a client cancel by the controller),
                // not be mislabelled as an audit failure.
                onStatus.accept(new SseStatusEvent("long-novel", "audit-done", null, null, null, outcome.fixes()));
            } else {
                onStatus.accept(new SseStatusEvent("long-novel", "audit-skipped", null, null, null, null));
            }
        }

        if (cancelled.getAsBoolean()) {
            return;
        }
        finalizeStreamedTranslation(user, config, mergedUsage, finalText, sourceText,
                request.getCustomPrompt(), hideCustomPrompt, resolvedTargetLanguage, cacheKey,
                startedAt, onComplete, truncated);
    }

    /** The shared tail of a streamed translation: usage, history row, feedback, cache, response. */
    private void finalizeStreamedTranslation(User user, AiModelConfig config, TokenUsage usage,
                                             String translatedText, String sourceText, String customPrompt,
                                             boolean hideCustomPrompt, String resolvedTargetLanguage,
                                             String cacheKey, long startedAt,
                                             Consumer<TranslateResponse> onComplete) {
        finalizeStreamedTranslation(user, config, usage, translatedText, sourceText, customPrompt,
                hideCustomPrompt, resolvedTargetLanguage, cacheKey, startedAt, onComplete, false);
    }

    /**
     * The shared tail of a streamed translation. A truncated result is stored (the tokens were
     * spent and the user can still read what arrived) but never cached: replaying a half
     * translation from the cache would keep presenting it as a complete one.
     */
    private void finalizeStreamedTranslation(User user, AiModelConfig config, TokenUsage usage,
                                             String translatedText, String sourceText, String customPrompt,
                                             boolean hideCustomPrompt, String resolvedTargetLanguage,
                                             String cacheKey, long startedAt,
                                             Consumer<TranslateResponse> onComplete, boolean truncated) {
        usageService.record(user, config, usage);

        TranslationRecord record = new TranslationRecord();
        record.setUser(user);
        record.setSourceText(sourceText);
        record.setTranslatedText(translatedText);
        record.setModel(config.getModel());
        record.setCustomPrompt(hideCustomPrompt ? null : customPrompt);
        record.setTargetLanguage(resolvedTargetLanguage);
        record.setRequestId(java.util.UUID.randomUUID().toString());
        record.setTruncated(truncated);
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
        response.setTruncated(truncated);
        feedbackService.registerTranslation(record.getRequestId(), sourceText, translatedText,
                config.getProvider(), config.getModel(), config.getThinkingType(),
                resolvedTargetLanguage, System.currentTimeMillis() - startedAt, true);
        if (truncated) {
            onComplete.accept(response);
            return;
        }
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

    static TokenUsage mergeUsage(TokenUsage a, TokenUsage b) {
        if (b == null) return a;
        if (a == null) return b;
        TokenUsage merged = new TokenUsage();
        merged.setPromptTokens(safeInt(a.getPromptTokens()) + safeInt(b.getPromptTokens()));
        merged.setCompletionTokens(safeInt(a.getCompletionTokens()) + safeInt(b.getCompletionTokens()));
        merged.setTotalTokens(safeInt(a.getTotalTokens()) + safeInt(b.getTotalTokens()));
        return merged;
    }

    static int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private void requireSourceText(TranslateRequest request) {
        if (request.getSourceText() == null || request.getSourceText().isBlank()) {
            throw new com.shizuku.translate.exception.BusinessException("请输入要翻译的文本，或上传图片");
        }
    }

    /**
     * True when the caller explicitly asked for thinking mode via the request. Such requests
     * also skip the cross-user shared translation: that entry may have been produced in either
     * mode, and a caller who chose thinking is asking for a fresh, higher-effort result rather
     * than a replay of somebody else's fast-path output.
     */
    private static boolean thinkingRequested(TranslateRequest request) {
        return "enabled".equalsIgnoreCase(request.getThinkingType());
    }

    /**
     * Cache-key version. Bumped whenever stored translations could be wrong in a way the
     * current code no longer produces: keys written by an older version simply never match,
     * so a bad row can never be replayed after an upgrade. Added for the 8192-token truncation
     * fix — pre-1.x caches may hold answers that stopped mid-translation.
     */
    static final String CACHE_KEY_VERSION = "v2";

    private String buildCacheKey(Long userId, AiModelConfig config, String systemPrompt, String sourceText) {
        // thinkingType is part of the key: enabling thinking is a quality choice, and a
        // fast-path result from before the toggle must not shadow the thinking result.
        String raw = CACHE_KEY_VERSION + "|" + userId + "|" + config.getProvider() + "|" + config.getBaseUrl()
                + "|" + config.getModel() + "|" + config.getThinkingType()
                + "|" + systemPrompt + "|" + sourceText;
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
