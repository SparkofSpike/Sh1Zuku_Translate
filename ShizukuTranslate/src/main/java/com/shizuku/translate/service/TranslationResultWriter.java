package com.shizuku.translate.service;

import com.shizuku.translate.dto.TokenUsage;
import com.shizuku.translate.dto.TranslateResponse;
import com.shizuku.translate.entity.TranslationRecord;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.integration.AiModelClient.AiModelConfig;
import com.shizuku.translate.repository.TranslationRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists the result of a translation in a short, dedicated transaction.
 *
 * <p>The calls into the model (DeepSeek and friends) are synchronous HTTP requests that can
 * take minutes. Holding them inside a {@code @Transactional} boundary pins one JDBC
 * connection for the whole call, so a handful of concurrent slow translations exhausts the
 * pool and every other request starts failing with
 * {@code HikariPool-1 - Connection is not available}. Keeping the database work in this
 * separate bean means the connection is only held for the handful of milliseconds the
 * inserts actually need.
 *
 * <p>This has to live in its own bean: a self-invoked {@code @Transactional} method inside
 * {@link TranslationService} would bypass the Spring proxy and silently open no transaction
 * at all.
 */
@Service
public class TranslationResultWriter {

    private final TranslationRecordRepository recordRepository;
    private final UsageService usageService;

    public TranslationResultWriter(TranslationRecordRepository recordRepository, UsageService usageService) {
        this.recordRepository = recordRepository;
        this.usageService = usageService;
    }

    /** Records usage and stores the translation itself; returns the client-facing response. */
    @Transactional
    public TranslateResponse persistTranslate(User user, AiModelConfig config, TokenUsage usage,
                                              String translatedText, String sourceText, String customPrompt) {
        usageService.record(user, config, usage);

        TranslationRecord record = new TranslationRecord();
        record.setUser(user);
        record.setSourceText(sourceText == null ? "" : sourceText);
        record.setTranslatedText(translatedText);
        record.setModel(config.getModel());
        record.setCustomPrompt(customPrompt);
        record = recordRepository.save(record);

        TranslateResponse response = new TranslateResponse();
        response.setId(record.getId());
        response.setTranslatedText(translatedText);
        response.setModel(config.getModel());
        response.setCreatedAt(record.getCreatedAt());
        if (usage != null) {
            response.setTokenUsage(usage);
        }
        return response;
    }
}
