package com.shizuku.translate.service;

import com.shizuku.translate.entity.TokenUsageLog;
import com.shizuku.translate.entity.TranslationCache;
import com.shizuku.translate.entity.TranslationRecord;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.repository.TokenUsageLogRepository;
import com.shizuku.translate.repository.TranslationCacheRepository;
import com.shizuku.translate.repository.TranslationRecordRepository;
import com.shizuku.translate.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class HistoricalUsageMigrationService {
    private static final Logger log = LoggerFactory.getLogger(HistoricalUsageMigrationService.class);
    private static final String CACHE_SOURCE = "CACHE_BACKFILL";
    private static final String RECORD_SOURCE = "RECORD_ESTIMATE";

    private final TokenUsageLogRepository usageLogRepository;
    private final TranslationCacheRepository cacheRepository;
    private final TranslationRecordRepository recordRepository;
    private final UserRepository userRepository;

    public HistoricalUsageMigrationService(TokenUsageLogRepository usageLogRepository,
                                           TranslationCacheRepository cacheRepository,
                                           TranslationRecordRepository recordRepository,
                                           UserRepository userRepository) {
        this.usageLogRepository = usageLogRepository;
        this.cacheRepository = cacheRepository;
        this.recordRepository = recordRepository;
        this.userRepository = userRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void migrateOnStartup() {
        try {
            int clearedPrompts = recordRepository.clearKnownPluginPrompts();
            MigrationResult result = backfillUsage();
            log.info("Historical usage migration complete: cache={}, estimatedRecords={}, clearedPluginPrompts={}",
                    result.cacheCount, result.recordCount, clearedPrompts);
        } catch (Exception e) {
            // A historical migration must never prevent the application from starting.
            log.error("Historical usage migration failed; it can be retried on the next restart", e);
        }
    }

    private MigrationResult backfillUsage() {
        int cacheCount = 0;
        int recordCount = 0;
        Set<String> accountedTranslations = new HashSet<>();
        List<TranslationCache> caches = cacheRepository.findAll();

        for (TranslationCache cache : caches) {
            if (cache.getTotalTokens() == null || cache.getTotalTokens() <= 0) continue;
            Optional<User> user = userRepository.findById(cache.getUserId());
            if (user.isEmpty()) continue;

            accountedTranslations.add(translationKey(user.get().getId(), cache.getModel(), cache.getTranslatedText()));
            if (usageLogRepository.existsBySourceTypeAndSourceId(CACHE_SOURCE, cache.getId())) continue;

            TokenUsageLog logEntry = TokenUsageLog.builder()
                    .user(user.get())
                    .provider("deepseek")
                    .model(cache.getModel())
                    .sourceType(CACHE_SOURCE)
                    .sourceId(cache.getId())
                    .estimated(false)
                    .promptTokens(Math.max(0, value(cache.getPromptTokens())))
                    .completionTokens(Math.max(0, value(cache.getCompletionTokens())))
                    .totalTokens(Math.max(0, cache.getTotalTokens()))
                    .createdAt(cache.getCreatedAt() == null ? LocalDateTime.now() : cache.getCreatedAt())
                    .build();
            usageLogRepository.save(logEntry);
            cacheCount++;
        }

        for (TranslationRecord record : recordRepository.findAll()) {
            if (record.getUser() == null || record.getTranslatedText() == null && record.getSourceText() == null) continue;
            if (usageLogRepository.existsBySourceTypeAndSourceId(RECORD_SOURCE, record.getId())) continue;

            // A streaming record backed by a cache already has exact usage above.
            // Do not count a cache hit a second time as an estimated request.
            String key = translationKey(record.getUser().getId(), record.getModel(), record.getTranslatedText());
            if (accountedTranslations.contains(key)) continue;

            int promptTokens = estimateTokens(record.getSourceText());
            int completionTokens = estimateTokens(record.getTranslatedText());
            TokenUsageLog logEntry = TokenUsageLog.builder()
                    .user(record.getUser())
                    .provider("deepseek")
                    .model(record.getModel() == null || record.getModel().isBlank() ? "unknown" : record.getModel())
                    .sourceType(RECORD_SOURCE)
                    .sourceId(record.getId())
                    .estimated(true)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .totalTokens(promptTokens + completionTokens)
                    .createdAt(record.getCreatedAt() == null ? LocalDateTime.now() : record.getCreatedAt())
                    .build();
            usageLogRepository.save(logEntry);
            recordCount++;
        }
        return new MigrationResult(cacheCount, recordCount);
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        int characters = text.codePointCount(0, text.length());
        return (int) Math.round(characters * 0.95d);
    }

    private int value(Integer number) {
        return number == null ? 0 : number;
    }

    private String translationKey(Long userId, String model, String translatedText) {
        String raw = userId + "|" + (model == null ? "" : model) + "|" + (translatedText == null ? "" : translatedText);
        try {
            return HexFormatHolder.sha256(raw);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static class HexFormatHolder {
        private static String sha256(String value) throws NoSuchAlgorithmException {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private record MigrationResult(int cacheCount, int recordCount) {}
}
