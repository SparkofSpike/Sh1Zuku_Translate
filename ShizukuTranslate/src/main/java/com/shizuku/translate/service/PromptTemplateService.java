package com.shizuku.translate.service;

import com.shizuku.translate.config.AppConfig;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class PromptTemplateService {

    /** Replaced with the resolved target-language name, e.g. "简体中文". */
    private static final String LANGUAGE_PLACEHOLDER = "{language}";

    private final AppConfig.AppProperties appProperties;
    private final GlossaryService glossaryService;

    public PromptTemplateService(AppConfig.AppProperties appProperties, GlossaryService glossaryService) {
        this.appProperties = appProperties;
        this.glossaryService = glossaryService;
    }

    /**
     * Default system prompt for non-streaming translation (literary translation).
     */
    public static final String DEFAULT_TRANSLATE_PROMPT =
            "你是一位专业的文学翻译家。请将用户提供的外文原文精准翻译为{language}。\\n\\n翻译要求：\\n1. " +
            "遵循「信达雅」原则：忠实原文内容，译文通顺流畅，保持一定的文学美感\\n2. 如果原文为日文，保留日式特有的称谓习惯，如「桑」「酱」「大人」等\\n3. " +
            "人名、地名、专有名词统一音译，保持一致性\\n4. 遇到特殊符号（如「♪」「♯」「†」）或数字编号时，原样保留\\n5. " +
            "对话部分保持口语自然感，内心独白部分保持忧郁或严肃语调\\n6. " +
            "若遇到外国文化特有概念（如「お盆」「初詣」等），可酌情补充简短括号注释\\n禁用Markdown格式，应使用全角空格或者Tab来进行段前间距的分明" +
            "\\n\\n禁止事项：\\n" +
            "- " +
            "不要在译文后添加任何译者注释或说明\\n- " +
            "不要改变原文的段落结构和标点符号\\n- 不要过度使用网络流行语或过度口语化，除非原文如此\\n- 不要输出除翻译结果以外的任何内容";

    /**
     * Default system prompt for streaming translation (novel translation).
     *
     * <p>The source language is deliberately not pinned: the model is told to detect it, so the
     * same prompt serves Japanese, Korean, and Chinese source texts.
     */
    public static final String DEFAULT_STREAM_PROMPT =
            "你是一名专业小说翻译，请将用户提供的小说原文翻译为{language}，保持原文风格和语气。"
            + "原文可能为日语、韩语或中文，请自行判断，不要询问用户。";

    /**
     * Build a complete system prompt by combining the default prompt with user-selected presets,
     * the presets' glossary entries, and an optional custom instruction.
     *
     * @param defaultPrompt  the base prompt to start with (contains {@code {language}})
     * @param presets        list of preset keys selected by the user
     * @param customPrompt   optional custom instruction from the user
     * @param targetLanguage requested target language tag; unknown or null falls back to the default
     * @return the complete system prompt string
     */
    public String buildSystemPrompt(String defaultPrompt, List<String> presets, String customPrompt,
                                    String targetLanguage) {
        String langCode = appProperties.resolveTargetLanguage(targetLanguage);
        String langLabel = appProperties.targetLanguageLabel(langCode);

        StringBuilder systemPrompt = new StringBuilder(
                defaultPrompt.replace(LANGUAGE_PLACEHOLDER, langLabel));

        if (presets != null && !presets.isEmpty()) {
            Map<String, String> presetMap = appProperties.getPresetMap();
            systemPrompt.append("\n\n请特别注意以下要求：");
            for (String presetKey : presets) {
                String prompt = presetMap.get(presetKey);
                if (prompt != null) {
                    systemPrompt.append("\n- ").append(prompt);
                } else {
                    systemPrompt.append("\n- ").append(presetKey);
                }
                // A preset whose name matches a glossary series also contributes that series'
                // terminology, rendered into the current target language.
                String glossary = glossaryService.buildSeriesInjection(presetKey, langCode);
                if (glossary != null) {
                    systemPrompt.append("\n- ").append(glossary);
                }
            }
        }

        if (customPrompt != null && !customPrompt.isBlank()) {
            systemPrompt.append("\n\n用户额外指示：").append(customPrompt);
        }

        return systemPrompt.toString();
    }
}
