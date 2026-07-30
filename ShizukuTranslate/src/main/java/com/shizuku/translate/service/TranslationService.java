package com.shizuku.translate.service;

import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.dto.HistoryResponse;
import com.shizuku.translate.dto.TokenUsage;
import com.shizuku.translate.dto.TranslateRequest;
import com.shizuku.translate.dto.TranslateResponse;
import com.shizuku.translate.entity.TranslationRecord;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.exception.ResourceNotFoundException;
import com.shizuku.translate.integration.DeepSeekClient;
import com.shizuku.translate.repository.TranslationRecordRepository;
import com.shizuku.translate.integration.DeepSeekClient.DeepSeekResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.List;
import java.util.function.Consumer;

@Service
public class TranslationService {

    private final DeepSeekClient deepSeekClient;
    private final TranslationRecordRepository recordRepository;
    private final UserService userService;
    private final PromptTemplateService promptTemplateService;

    public TranslationService(DeepSeekClient deepSeekClient,
                              TranslationRecordRepository recordRepository,
                              UserService userService,
                              PromptTemplateService promptTemplateService) {
        this.deepSeekClient = deepSeekClient;
        this.recordRepository = recordRepository;
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

        DeepSeekResult result = deepSeekClient.chat(systemPrompt, request.getSourceText(), request.getModel());
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

        StringBuilder fullText = new StringBuilder();
        TokenUsage[] usageHolder = new TokenUsage[1];

        deepSeekClient.chatStream(
                systemPrompt,
                request.getSourceText(),
                request.getModel(),
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
                    onComplete.accept(response);
                },
                error -> onError.accept(error)
        );
    }
}
