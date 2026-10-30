package com.shizuku.translate.service.longform;

import com.shizuku.translate.integration.AiModelClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Terminology handling for the chunked long-novel pipeline: a pre-translation scan that extracts
 * proper nouns and coined terms (injected into every chunk), and a post-translation audit that
 * deterministically repairs terminology drift.
 *
 * <p>The two model calls run in thinking mode and are best-effort: callers log and continue when
 * either fails. The parsing and rendering helpers are static so they can be unit-tested without a
 * model.
 */
@Service
public class TerminologyService {

    private static final Logger log = LoggerFactory.getLogger(TerminologyService.class);

    /** Upper bound for pre-extracted terms; a runaway list must not inflate every chunk prompt. */
    static final int MAX_EXTRACTED_TERMS = 300;

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Prompt for the pre-translation term scan (runs in thinking mode). */
    static final String TERM_EXTRACT_SYSTEM =
            "你是术语管理助手。请从小说原文中提取所有需要统一译名的专有名词，覆盖三类：\n"
            + "1. 人物（本名、昵称、称呼方式）；2. 地名、组织、作品名；3. 自造词与特殊概念（术式、物品、种族、设定术语）。\n"
            + "对每个词给出：原文、建议的简体中文译名、类型（person/place/concept）。\n"
            + "以 JSON 数组输出：[{\"term\":\"原文\",\"translation\":\"译名\",\"type\":\"person\"}]\n"
            + "只输出 JSON 数组，不要任何其他文字。";

    /** Prompt for the post-translation terminology audit (runs in thinking mode). */
    static final String TERM_AUDIT_SYSTEM =
            "你是翻译审校助手。给你一张术语表（原文→标准译名）和一篇已完成的译文。\n"
            + "任务：检查译文中是否出现了与标准译名不一致的写法（同一术语的不同译法）。\n"
            + "只报告明确属于同一术语却写法不同的情况，不要把意思相近的普通词误报为变体。\n"
            + "以 JSON 数组输出：[{\"standard\":\"标准译名\",\"variants\":[\"出现的不同写法\"]}]\n"
            + "如果没有任何不一致，输出 []。只输出 JSON。";

    private final AiModelClient aiModelClient;

    public TerminologyService(AiModelClient aiModelClient) {
        this.aiModelClient = aiModelClient;
    }

    /** One extracted proper noun / coined term with its mandated rendering. */
    public record TermPair(String source, String translation) {}

    /** Result of the post-translation audit: the repaired text plus how many occurrences changed. */
    public record AuditOutcome(String correctedText, int fixes) {}

    /**
     * Scans the whole text for proper nouns and coined terms (thinking mode; short output).
     */
    public List<TermPair> extractTerms(AiModelClient.AiModelConfig config, String sourceText) {
        AiModelClient.AiModelConfig thinking = new AiModelClient.AiModelConfig(config.getProvider(), config.getApiKey(),
                config.getBaseUrl(), config.getModel(), "enabled");
        AiModelClient.DeepSeekResult result = aiModelClient.chat(TERM_EXTRACT_SYSTEM,
                "请提取以下原文中的专有名词：\n\n" + sourceText, thinking);
        List<TermPair> terms = parseTermPairs(result.getContent());
        log.info("Pre-extracted {} terms for a {} character text", terms.size(), sourceText.length());
        return terms;
    }

    /**
     * Audits the finished translation for terminology drift and repairs it deterministically: the
     * model only points out mismatched renderings, the actual replacements are plain string
     * edits, so a flaky audit answer can never rewrite arbitrary prose.
     */
    public AuditOutcome auditTerminology(AiModelClient.AiModelConfig config, String translatedText, List<TermPair> terms) {
        AiModelClient.AiModelConfig thinking = new AiModelClient.AiModelConfig(
                config.getProvider(), config.getApiKey(), config.getBaseUrl(), config.getModel(), "enabled");
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

    /**
     * Parses term JSON, tolerating prose around the array; an unparsable answer yields none.
     */
    public static List<TermPair> parseTermPairs(String content) {
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

    /**
     * Renders the extracted table as one system-prompt line shared by every chunk.
     */
    public static String renderTermBlock(List<TermPair> terms) {
        StringBuilder block = new StringBuilder("【本文专有名词对照表】（自动提取，翻译时必须严格遵循这些译名）：");
        for (TermPair term : terms) {
            block.append(term.source()).append("→").append(term.translation()).append("、");
        }
        if (block.charAt(block.length() - 1) == '、') {
            block.setLength(block.length() - 1);
        }
        return block.append("。").toString();
    }

    /**
     * Parses audit JSON into {@code [standard, variant]} replacement pairs.
     */
    public static List<String[]> parseAuditFindings(String content) {
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
}