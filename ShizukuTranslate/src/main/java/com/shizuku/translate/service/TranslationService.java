package com.shizuku.translate.service;

import com.shizuku.translate.dto.HistoryResponse;
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
import java.util.HexFormat;
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

    public TranslationService(AiModelClient aiModelClient,
                              TranslationRecordRepository recordRepository,
                              TranslationCacheRepository cacheRepository,
                              UserService userService,
                              PromptTemplateService promptTemplateService,
                              UsageService usageService) {
        this.aiModelClient = aiModelClient;
        this.recordRepository = recordRepository;
        this.cacheRepository = cacheRepository;
        this.userService = userService;
        this.promptTemplateService = promptTemplateService;
        this.usageService = usageService;
    }

    @Transactional
    public TranslateResponse translateImage(String username, TranslateRequest request, MultipartFile image, boolean hideCustomPrompt) throws java.io.IOException {
        User user = userService.findByUsername(username);
        AiModelConfig config = userService.resolveAiModelConfig(user, request.getModel(), request.getThinkingType(), request.getModelProfileId());
        if (!config.isVisual()) throw new com.shizuku.translate.exception.BusinessException("只有视觉模型才能使用模型处理");
        String systemPrompt = promptTemplateService.buildSystemPrompt(PromptTemplateService.DEFAULT_TRANSLATE_PROMPT,
                request.getPresets(), request.getCustomPrompt());
        DeepSeekResult result = aiModelClient.chatWithImage(systemPrompt, request.getSourceText(), image.getBytes(),
                image.getContentType() == null ? "image/png" : image.getContentType(), config);
        usageService.record(user, config, result.getUsage());
        TranslationRecord record = new TranslationRecord();
        record.setUser(user); record.setSourceText(request.getSourceText() == null ? "" : request.getSourceText()); record.setTranslatedText(result.getContent());
        record.setModel(config.getModel()); record.setCustomPrompt(hideCustomPrompt ? null : request.getCustomPrompt());
        record = recordRepository.save(record);
        TranslateResponse response = new TranslateResponse(); response.setId(record.getId());
        response.setTranslatedText(result.getContent()); response.setModel(config.getModel()); response.setCreatedAt(record.getCreatedAt());
        response.setTokenUsage(result.getUsage()); return response;
    }

    @Transactional
    public TranslateResponse translate(String username, TranslateRequest request, boolean hideCustomPrompt) {
        requireSourceText(request);
        User user = userService.findByUsername(username);

        String systemPrompt = promptTemplateService.buildSystemPrompt(
                PromptTemplateService.DEFAULT_TRANSLATE_PROMPT,
                request.getPresets(),
                request.getCustomPrompt()
        );
        AiModelConfig config = userService.resolveAiModelConfig(user, request.getModel(), request.getThinkingType(), request.getModelProfileId());

        DeepSeekResult result = aiModelClient.chat(systemPrompt, request.getSourceText(), config);
        String translated = result.getContent();
        usageService.record(user, config, result.getUsage());

        TranslationRecord record = new TranslationRecord();
        record.setUser(user);
        record.setSourceText(request.getSourceText());
        record.setTranslatedText(translated);
        record.setModel(config.getModel());
        record.setCustomPrompt(hideCustomPrompt ? null : request.getCustomPrompt());
        record = recordRepository.save(record);

        TranslateResponse response = new TranslateResponse();
        response.setId(record.getId());
        response.setTranslatedText(translated);
        response.setModel(config.getModel());
        response.setCreatedAt(record.getCreatedAt());

        if (result.getUsage() != null) {
            response.setTokenUsage(result.getUsage());
        }

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
        requireSourceText(request);
        User user = userService.findByUsername(username);

        String systemPrompt = promptTemplateService.buildSystemPrompt(
                PromptTemplateService.DEFAULT_STREAM_PROMPT,
                request.getPresets(),
                request.getCustomPrompt()
        );

        AiModelConfig config = userService.resolveAiModelConfig(user, request.getModel(), request.getThinkingType(), request.getModelProfileId());
        String cacheKey = buildCacheKey(user.getId(), config, systemPrompt, request.getSourceText());
        if (cancelled.getAsBoolean()) {
            return;
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
            record = recordRepository.save(record);

            TranslateResponse response = new TranslateResponse();
            response.setId(record.getId());
            response.setTranslatedText(cached.getTranslatedText());
            response.setModel(config.getModel());
            response.setCreatedAt(record.getCreatedAt());
            if (cached.getTotalTokens() != null && cached.getTotalTokens() > 0) {
                TokenUsage usage = new TokenUsage();
                usage.setPromptTokens(cached.getPromptTokens());
                usage.setCompletionTokens(cached.getCompletionTokens());
                usage.setTotalTokens(cached.getTotalTokens());
                response.setTokenUsage(usage);
            }
            onComplete.accept(response);
            return;
        }

        StringBuilder fullText = new StringBuilder();
        TokenUsage[] usageHolder = new TokenUsage[1];

        aiModelClient.chatStream(
                systemPrompt,
                request.getSourceText(),
                config,
                token -> {
                    fullText.append(token);
                    onToken.accept(token);
                },
                usage -> {
                    usageHolder[0] = usage;
                    usageService.record(user, config, usage);

                    TranslationRecord record = new TranslationRecord();
                    record.setUser(user);
                    record.setSourceText(request.getSourceText());
                    record.setTranslatedText(fullText.toString());
                    record.setModel(config.getModel());
                    record.setCustomPrompt(hideCustomPrompt ? null : request.getCustomPrompt());
                    record = recordRepository.save(record);

                    TranslateResponse response = new TranslateResponse();
                    response.setId(record.getId());
                    response.setTranslatedText(fullText.toString());
                    response.setModel(config.getModel());
                    response.setCreatedAt(record.getCreatedAt());
                    if (usage != null) {
                        response.setTokenUsage(usage);
                    }
                    try {
                        TranslationCache cache = TranslationCache.builder()
                                .userId(user.getId())
                                .cacheKey(cacheKey)
                                .model(config.getModel())
                                .translatedText(fullText.toString())
                                .promptTokens(usage != null ? usage.getPromptTokens() : null)
                                .completionTokens(usage != null ? usage.getCompletionTokens() : null)
                                .totalTokens(usage != null ? usage.getTotalTokens() : null)
                                .build();
                        cacheRepository.save(cache);
                    } catch (Exception e) {
                        log.warn("Failed to write translation cache", e);
                    }
                    onComplete.accept(response);
                },
                error -> onError.accept(error),
                onUpstreamConnected,
                cancelled
        );
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
