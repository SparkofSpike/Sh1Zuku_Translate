package com.shizuku.translate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.config.DeepSeekConfig;
import com.shizuku.translate.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The screenshot-extraction parser must survive the small decorations a vision model adds
 * (code fences, prose around the JSON) and must refuse to fabricate a title — the title is
 * what drives the Pixiv search that follows.
 */
class PixivImageImportServiceTest {

    private PixivImageImportService service() {
        return new PixivImageImportService(null, new DeepSeekConfig.DeepSeekProperties(), new ObjectMapper());
    }

    @Test
    void parsesPlainJson() {
        Map<String, Object> result = service().parseExtraction(
                "{\"title\":\"月夜の誓い\",\"author\":\"作者名\",\"tags\":[\"恋愛\",\"学園\"],\"summary\":\"简介\"}");
        assertEquals("月夜の誓い", result.get("title"));
        assertEquals("作者名", result.get("author"));
        assertEquals("简介", result.get("summary"));
        assertEquals(List.of("恋愛", "学園"), result.get("tags"));
    }

    @Test
    void toleratesCodeFencesAndSurroundingProse() {
        Map<String, Object> result = service().parseExtraction(
                "好的，这是提取结果：\n```json\n{\"title\":\"タイトル\",\"tags\":[]}\n```\n希望对你有帮助！");
        assertEquals("タイトル", result.get("title"));
        assertEquals(List.of(), result.get("tags"));
        assertEquals("", result.get("author"));
    }

    @Test
    void capsTagsAtTen() {
        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < 15; i++) {
            if (i > 0) {
                tags.append(',');
            }
            tags.append("\"t").append(i).append('"');
        }
        Map<String, Object> result = service().parseExtraction(
                "{\"title\":\"x\",\"tags\":[" + tags + "]}");
        assertEquals(10, ((List<?>) result.get("tags")).size());
    }

    @Test
    void blankTitleIsRejected() {
        assertThrows(BusinessException.class, () -> service().parseExtraction(
                "{\"title\":\"  \",\"tags\":[\"a\"]}"));
        assertThrows(BusinessException.class, () -> service().parseExtraction(
                "{\"author\":\"only author\"}"));
    }

    @Test
    void nonJsonIsRejected() {
        assertThrows(BusinessException.class, () -> service().parseExtraction("这不是 JSON"));
        assertThrows(BusinessException.class, () -> service().parseExtraction(null));
        assertThrows(BusinessException.class, () -> service().parseExtraction(""));
    }

    @Test
    void ignoresNonStringTagEntries() {
        Map<String, Object> result = service().parseExtraction(
                "{\"title\":\"t\",\"tags\":[\"ok\",123,true,\" \",\"also\"]}");
        assertEquals(List.of("ok", "also"), result.get("tags"));
    }
}
