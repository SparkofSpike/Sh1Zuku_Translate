package com.shizuku.translate.integration;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelClientVisionTest {

    @Test
    void visionModelBuildsUserContentWithTextAndBase64Image() {
        AiModelClient.AiModelConfig config = new AiModelClient.AiModelConfig(
                "deepseek", "key", "https://api.deepseek.com/v1",
                "deepseek-flash", "disabled");

        Map<String, Object> request = AiModelClient.buildVisionRequest(
                "Translate the image.", "Keep the formatting.",
                new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, "image/jpeg", config);

        assertEquals("deepseek-flash", request.get("model"));
        assertFalse(request.containsKey("thinking"));

        List<Map<String, Object>> messages = messages(request);
        assertEquals("system", messages.get(0).get("role"));
        assertEquals("Translate the image.", messages.get(0).get("content"));
        assertEquals("user", messages.get(1).get("role"));

        List<Map<String, Object>> content = contentParts(messages.get(1));
        assertEquals("text", content.get(0).get("type"));
        assertEquals("Keep the formatting.", content.get(0).get("text"));
        assertEquals("image_url", content.get(1).get("type"));
        Map<String, Object> imageUrl = imageUrl(content.get(1));
        assertEquals("data:image/png;base64,iVBORw0KGgo=", imageUrl.get("url"));
    }

    @Test
    void visionModelCanEnableThinking() {
        AiModelClient.AiModelConfig config = new AiModelClient.AiModelConfig(
                "deepseek", "key", "https://api.deepseek.com/v1",
                "deepseek-flash", "enabled");

        Map<String, Object> request = AiModelClient.buildVisionRequest(
                "system", null, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}, "image/png", config);

        assertEquals(Map.of("type", "enabled"), request.get("thinking"));
        assertEquals("", contentParts(messages(request).get(1)).get(0).get("text"));
    }

    @Test
    void onlyVisualSiteModelsAreAcceptedForImageRequests() {
        AiModelClient.AiModelConfig flash = new AiModelClient.AiModelConfig(
                "deepseek", "key", "https://api.deepseek.com/v1",
                "deepseek-flash", "disabled");
        AiModelClient.AiModelConfig pro = new AiModelClient.AiModelConfig(
                "deepseek", "key", "https://api.deepseek.com/v1",
                "deepseek-v4-pro", "disabled");
        AiModelClient.AiModelConfig retiredVision = new AiModelClient.AiModelConfig(
                "deepseek", "key", "https://api.deepseek.com/v1",
                "deepseek-v4-flash-vision-exp", "disabled");

        assertTrue(flash.isVisual());
        assertFalse(pro.isVisual());
        // The retired name still resolves to V4.1 Flash upstream, so saved profiles keep working.
        assertTrue(retiredVision.isVisual());
        assertThrows(IllegalArgumentException.class,
                () -> AiModelClient.buildVisionRequest("system", "text", new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, "image/png", pro));
        assertThrows(IllegalArgumentException.class,
                () -> AiModelClient.buildVisionRequest("system", "text", new byte[] {1}, "image/bmp", flash));
    }

    @Test
    void multipleImagesJoinOneUserMessageInOrder() {
        AiModelClient.AiModelConfig config = new AiModelClient.AiModelConfig(
                "deepseek", "key", "https://api.deepseek.com/v1",
                "deepseek-flash", "disabled");

        List<AiModelClient.ImagePayload> images = List.of(
                new AiModelClient.ImagePayload(
                        new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a}, "image/png"),
                new AiModelClient.ImagePayload(
                        new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}, "image/jpeg"));

        Map<String, Object> request = AiModelClient.buildVisionRequest("system", "接着上文继续翻译", images, config);

        List<Map<String, Object>> content = contentParts(messages(request).get(1));
        assertEquals(3, content.size());
        assertEquals("text", content.get(0).get("type"));
        assertEquals("接着上文继续翻译", content.get(0).get("text"));
        assertEquals("image_url", content.get(1).get("type"));
        assertEquals("image_url", content.get(2).get("type"));
        // Upload order is preserved so pages stay in reading order for the model.
        assertEquals("data:image/png;base64,iVBORw0KGgo=", imageUrl(content.get(1)).get("url"));
        assertEquals("data:image/jpeg;base64,/9j/", imageUrl(content.get(2)).get("url"));
    }

    @Test
    void emptyImageListIsRejected() {
        AiModelClient.AiModelConfig config = new AiModelClient.AiModelConfig(
                "deepseek", "key", "https://api.deepseek.com/v1",
                "deepseek-flash", "disabled");

        assertThrows(IllegalArgumentException.class,
                () -> AiModelClient.buildVisionRequest("system", "text", List.of(), config));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> messages(Map<String, Object> request) {
        return (List<Map<String, Object>>) request.get("messages");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> contentParts(Map<String, Object> message) {
        return (List<Map<String, Object>>) message.get("content");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> imageUrl(Map<String, Object> imagePart) {
        return (Map<String, Object>) imagePart.get("image_url");
    }
}
