package com.shizuku.translate.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.config.DeepSeekConfig;
import com.shizuku.translate.exception.BusinessException;
import com.shizuku.translate.integration.AiModelClient;
import com.shizuku.translate.integration.AiModelClient.AiModelConfig;
import com.shizuku.translate.integration.AiModelClient.DeepSeekResult;
import com.shizuku.translate.integration.AiModelClient.ImagePayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extracts novel metadata (title / author / tags / summary) from a screenshot of a Pixiv
 * novel page, so the import panel can then search Pixiv for the work and import it without
 * the user typing anything but a paste.
 *
 * <p>Uses the site's vision model ({@code deepseek-flash}); the model is only asked for a
 * strict JSON object and the parser tolerates fences and surrounding prose, because a small
 * model will occasionally decorate its output despite instructions.
 */
@Service
public class PixivImageImportService {

    private static final Logger log = LoggerFactory.getLogger(PixivImageImportService.class);
    private static final String VISION_MODEL = "deepseek-flash";

    private static final String SYSTEM_PROMPT = """
            你是一个小说信息提取助手。用户会给你一张或多张图片，它们可能是 Pixiv 小说页面的截图。
            请提取图中的小说信息，并只输出一个 JSON 对象，不要输出任何其他文字或标记（不要 markdown 代码块）：
            {"title": "小说标题", "author": "作者名", "tags": ["标签1", "标签2"], "summary": "作品简介"}
            规则：
            - title 必填，用图中原文（通常是日文）；如果图中确实没有标题，返回空字符串 ""。
            - author、summary 看不到就返回空字符串 ""。
            - tags 是图中的标签列表，最多 10 个；看不到就返回空数组 []。
            """;

    private final AiModelClient aiModelClient;
    private final DeepSeekConfig.DeepSeekProperties deepSeekProperties;
    private final ObjectMapper objectMapper;

    public PixivImageImportService(AiModelClient aiModelClient,
                                   DeepSeekConfig.DeepSeekProperties deepSeekProperties,
                                   ObjectMapper objectMapper) {
        this.aiModelClient = aiModelClient;
        this.deepSeekProperties = deepSeekProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs the vision model over the pasted screenshots and returns
     * {@code {title, author, tags[], summary}}.
     *
     * @throws BusinessException when the site key is missing, the model fails, or the image
     *                           does not contain a recognisable title
     */
    public Map<String, Object> extractNovelInfo(List<byte[]> images, List<String> mediaTypes) {
        if (images == null || images.isEmpty()) {
            throw new BusinessException("请上传至少一张图片");
        }
        String key = deepSeekProperties.getKey();
        if (key == null || key.isBlank()) {
            throw new BusinessException("站方视觉模型未配置，无法识别图片");
        }
        AiModelConfig config = new AiModelConfig("deepseek", key, deepSeekProperties.getBaseUrl(),
                VISION_MODEL, "disabled");
        List<ImagePayload> payloads = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            String mediaType = mediaTypes != null && i < mediaTypes.size() ? mediaTypes.get(i) : null;
            payloads.add(new ImagePayload(images.get(i), mediaType));
        }
        DeepSeekResult result = aiModelClient.chatWithImages(SYSTEM_PROMPT,
                "请提取这张图片中的小说信息。", payloads, config);
        return parseExtraction(result.getContent());
    }

    /**
     * Parses the model's answer into the fixed extraction shape. Tolerates ```json fences and
     * leading/trailing prose; requires a non-blank title, because a title is what the Pixiv
     * search is driven by.
     */
    Map<String, Object> parseExtraction(String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException("模型没有返回内容，请重试");
        }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("图片提取未返回 JSON：{}", content.length() > 200 ? content.substring(0, 200) : content);
            throw new BusinessException("无法从图片中识别出小说信息，请确认截图包含小说标题");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(content.substring(start, end + 1));
        } catch (Exception e) {
            throw new BusinessException("无法从图片中识别出小说信息，请确认截图包含小说标题", e);
        }
        String title = node.path("title").asText("").trim();
        if (title.isBlank()) {
            throw new BusinessException("无法从图片中识别出小说标题，请确认截图包含标题后重试");
        }
        String author = node.path("author").asText("").trim();
        String summary = node.path("summary").asText("").trim();
        List<String> tags = new ArrayList<>();
        JsonNode tagsNode = node.path("tags");
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                if (!tag.isTextual()) {
                    continue;
                }
                String value = tag.asText("").trim();
                if (!value.isEmpty() && tags.size() < 10) {
                    tags.add(value);
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", title);
        result.put("author", author);
        result.put("tags", tags);
        result.put("summary", summary);
        return result;
    }
}
