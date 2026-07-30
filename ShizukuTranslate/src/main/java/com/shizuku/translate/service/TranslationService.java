package com.shizuku.translate.service;

import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.dto.HistoryResponse;
import com.shizuku.translate.dto.TokenUsage;
import com.shizuku.translate.dto.TranslateRequest;
import com.shizuku.translate.dto.TranslateResponse;
import com.shizuku.translate.entity.TranslationRecord;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.integration.DeepSeekClient;
import com.shizuku.translate.repository.TranslationRecordRepository;
import com.shizuku.translate.integration.DeepSeekClient.DeepSeekResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class TranslationService {

    private final DeepSeekClient deepSeekClient;
    private final TranslationRecordRepository recordRepository;
    private final UserService userService;
    private final AppConfig appConfig;

    public TranslationService(DeepSeekClient deepSeekClient,
                              TranslationRecordRepository recordRepository,
                              UserService userService,
                              AppConfig appConfig) {
        this.deepSeekClient = deepSeekClient;
        this.recordRepository = recordRepository;
        this.userService = userService;
        this.appConfig = appConfig;

    }

    @Transactional
    public TranslateResponse translate(String username, TranslateRequest request) {
        User user = userService.findByUsername(username);
        String defaultPrompt = "你是一位专业的文学翻译家。请将用户提供的外文原文精准翻译为简体中文。\\n\\n翻译要求：\\n1. " +
                "遵循「信达雅」原则：忠实原文内容，译文通顺流畅，保持一定的文学美感\\n2. 如果原文为日文，保留日式特有的称谓习惯，如「桑」「酱」「大人」等\\n3. " +
                "人名、地名、专有名词统一音译，保持一致性\\n4. 遇到特殊符号（如「♪」「♯」「†」）或数字编号时，原样保留\\n5. " +
                "对话部分保持口语自然感，内心独白部分保持忧郁或严肃语调\\n6. " +
                "若遇到外国文化特有概念（如「お盆」「初詣」等），可酌情补充简短括号注释\\n禁用Markdown格式，应使用全角空格或者Tab来进行段前间距的分明" +
                "\\n\\n禁止事项：\\n" +
                "- " +
                "不要在译文后添加任何译者注释或说明\\n- " +
                "不要改变原文的段落结构和标点符号\\n- 不要过度使用网络流行语或过度口语化，除非原文如此\\n- 不要输出除翻译结果以外的任何内容";
        StringBuilder systemPrompt = new StringBuilder(defaultPrompt);
        List<String> selectedPresets = request.getPresets();
        if (selectedPresets != null && !selectedPresets.isEmpty()) {
            Map<String, String> presetMap = appConfig.appProperties().getPresetMap();
            systemPrompt.append("\n\n请特别注意以下要求：");
            for (String presetKey : selectedPresets) {
                String prompt = presetMap.get(presetKey);
                if (prompt != null) {
                    systemPrompt.append("\n- ").append(prompt);
                } else {
                    systemPrompt.append("\n- ").append(presetKey);  // 兜底
                }
            }
        }
        if (request.getCustomPrompt() != null && !request.getCustomPrompt().isBlank()) {
            systemPrompt.append("\n\n用户额外指示：").append(request.getCustomPrompt());
        }

        DeepSeekResult result = deepSeekClient.chat(systemPrompt.toString(), request.getSourceText(), request.getModel());
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
                .orElseThrow(() -> new RuntimeException("Record not found"));
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

        String defaultPrompt = "你是一名专业日译中小说翻译，请将用户提供的日语文本翻译为流畅的中文，保持原文风格和语气。";
        StringBuilder systemPrompt = new StringBuilder(defaultPrompt);

        List<String> selectedPresets = request.getPresets();
        if (selectedPresets != null && !selectedPresets.isEmpty()) {
            Map<String, String> presetMap = appConfig.appProperties().getPresetMap();
            systemPrompt.append("\n\n请特别注意以下要求：");
            for (String presetKey : selectedPresets) {
                String prompt = presetMap.get(presetKey);
                if (prompt != null) {
                    systemPrompt.append("\n- ").append(prompt);
                } else {
                    systemPrompt.append("\n- ").append(presetKey);
                }
            }
        }

        if (request.getCustomPrompt() != null && !request.getCustomPrompt().isBlank()) {
            systemPrompt.append("\n\n用户额外指示：").append(request.getCustomPrompt());
        }

        StringBuilder fullText = new StringBuilder();
        TokenUsage[] usageHolder = new TokenUsage[1];

        deepSeekClient.chatStream(
                systemPrompt.toString(),
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
