package com.shizuku.translate.service;

import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.dto.HistoryResponse;
import com.shizuku.translate.dto.TokenUsage;
import com.shizuku.translate.dto.TranslateRequest;
import com.shizuku.translate.dto.TranslateResponse;
import com.shizuku.translate.entity.TranslationCache;
import com.shizuku.translate.entity.TranslationRecord;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.exception.ResourceNotFoundException;
import com.shizuku.translate.integration.DeepSeekClient;
import com.shizuku.translate.repository.TranslationCacheRepository;
import com.shizuku.translate.repository.TranslationRecordRepository;
import com.shizuku.translate.integration.DeepSeekClient.DeepSeekResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

@Service
public class TranslationService {

    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);

    private final DeepSeekClient deepSeekClient;
    private final TranslationRecordRepository recordRepository;
    private final TranslationCacheRepository cacheRepository;
    private final UserService userService;
    private final PromptTemplateService promptTemplateService;

    public TranslationService(DeepSeekClient deepSeekClient,
                              TranslationRecordRepository recordRepository,
                              TranslationCacheRepository cacheRepository,
                              UserService userService,
                              PromptTemplateService promptTemplateService) {
        this.deepSeekClient = deepSeekClient;
        this.recordRepository = recordRepository;
        this.cacheRepository = cacheRepository;
        this.userService = userService;
        this.promptTemplateService = promptTemplateService;
    }

    @Transactional
    public TranslateResponse translate(String username, TranslateRequest request) {
        User user = userService.findByUsername(username);

        String systemPrompt = promptTemplateService.buildSystemPrompt(
                PromptTemplateService.DEFAULT_TRANSLATE_PROMPT,
                request.getPresets(),
                request.getCustomPrompt()
        );

        DeepSeekResult result = deepSeekClient.chat(systemPrompt, request.getSourceText(), request.getModel(), request.getThinkingType());
        String translated = result.getContent();

        TranslationRecord record = new TranslationRecord();
        record.setUser(user);
        record.setSourceText(request.getSourceText());
        record.setTranslatedText(translated);
        record.setModel(request.getModel());
        record.setCustomPrompt(request.getCustomPrompt());
        record = recordRepository.save(record);

        TranslateResponse response = new TranslateResponse();
        response.setId(record.getId());
        response.setTranslatedText(translated);
        response.setModel(request.getModel());
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

    public void translateStream(String username, TranslateRequest request,
                                Consumer<String> onToken, Consumer<TranslateResponse> onComplete,
                                Consumer<String> onError) {
        User user = userService.findByUsername(username);

        String systemPrompt = promptTemplateService.buildSystemPrompt(
                PromptTemplateService.DEFAULT_STREAM_PROMPT,
                request.getPresets(),
                request.getCustomPrompt()
        );

        String cacheKey = buildCacheKey(user.getId(), request.getModel(), systemPrompt, request.getSourceText());
        TranslationCache cached = cacheRepository.findByUserIdAndCacheKey(user.getId(), cacheKey);
        if (cached != null) {
            log.info("Translation cache hit for user {}, key {}", user.getId(), cacheKey.substring(0, 12));
            onToken.accept(cached.getTranslatedText());

            TranslationRecord record = new TranslationRecord();
            record.setUser(user);
            record.setSourceText(request.getSourceText());
            record.setTranslatedText(cached.getTranslatedText());
            record.setModel(request.getModel());
            record.setCustomPrompt(request.getCustomPrompt());
            record = recordRepository.save(record);

            TranslateResponse response = new TranslateResponse();
            response.setId(record.getId());
            response.setTranslatedText(cached.getTranslatedText());
            response.setModel(request.getModel());
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

        deepSeekClient.chatStream(
                systemPrompt,
                request.getSourceText(),
                request.getModel(),
                request.getThinkingType(),
                token -> {
                    fullText.append(token);
                    onToken.accept(token);
                },
                usage -> {
                    usageHolder[0] = usage;

                    TranslationRecord record = new TranslationRecord();
                    record.setUser(user);
                    record.setSourceText(request.getSourceText());
                    record.setTranslatedText(fullText.toString());
                    record.setModel(request.getModel());
                    record.setCustomPrompt(request.getCustomPrompt());
                    record = recordRepository.save(record);

                    TranslateResponse response = new TranslateResponse();
                    response.setId(record.getId());
                    response.setTranslatedText(fullText.toString());
                    response.setModel(request.getModel());
                    response.setCreatedAt(record.getCreatedAt());
                    if (usage != null) {
                        response.setTokenUsage(usage);
                    }
                    try {
                        TranslationCache cache = TranslationCache.builder()
                                .userId(user.getId())
                                .cacheKey(cacheKey)
                                .model(request.getModel())
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
                error -> onError.accept(error)
        );
    }

    private String buildCacheKey(Long userId, String model, String systemPrompt, String sourceText) {
        String raw = userId + "|" + (model == null ? "" : model) + "|" + systemPrompt + "|" + sourceText;
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
