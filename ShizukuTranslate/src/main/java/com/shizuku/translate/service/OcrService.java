package com.shizuku.translate.service;

import com.shizuku.translate.dto.OcrResponse;
import com.shizuku.translate.integration.DeepSeekClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Service
public class OcrService {

    private final RestTemplate restTemplate = new RestTemplate();
    private final DeepSeekClient deepSeekClient;

    public OcrService(DeepSeekClient deepSeekClient) {
        this.deepSeekClient = deepSeekClient;
    }

    @Value("${ocr.worker-url:http://localhost:5557}")
    private String ocrWorkerUrl;

    /**
     * Send image to Python OCR, then polish result with DeepSeek API
     */
    public OcrResponse processImage(MultipartFile file, boolean polish, double threshold) {
        try {
            // ====== Step 1: PaddleOCR ======
            LinkedMultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("image", new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename();
                }
            });
            body.add("threshold", String.valueOf(threshold));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            HttpEntity<LinkedMultiValueMap<String, Object>> requestEntity =
                    new HttpEntity<>(body, headers);

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.postForObject(
                    ocrWorkerUrl + "/ocr",
                    requestEntity,
                    Map.class);

            if (response == null) {
                return OcrResponse.error("OCR service no response");
            }
            if (response.containsKey("error")) {
                Object message = response.get("message");
                return OcrResponse.error(message == null ? (String) response.get("error") : String.valueOf(message));
            }

            String rawText = (String) response.get("text");
            if (rawText == null || rawText.trim().isEmpty()) {
                return OcrResponse.error("No text detected");
            }

            // ====== Step 2: DeepSeek polish (optional) ======
            String polishedText = polish ? polishOcrText(rawText) : rawText;

            if (polishedText == null || polishedText.trim().isEmpty()) {
                return OcrResponse.success(rawText, 1);
            }

            int lineCount = polishedText.split("\n").length;
            return OcrResponse.success(polishedText, lineCount);

        } catch (Exception e) {
            return OcrResponse.error("OCR failed: " + e.getMessage());
        }
    }

    /**
     * Use DeepSeek to polish raw OCR output
     */
    private String polishOcrText(String rawText) {
        try {
            String systemPrompt = "You are a Japanese text post-processor. Your task is to take raw OCR output "
                    + "and fix the formatting. Rules: 1) Merge characters that were split across lines incorrectly. "
                    + "2) Split text at correct paragraph boundaries. "
                    + "3) Fix wrong line breaks (vertical Japanese columns should be read right-to-left). "
                    + "4) Remove any duplicate or garbled characters. "
                    + "5) Output ONLY the corrected Japanese text, nothing else.";

            String userMessage = "Fix the formatting of this Japanese OCR output:\n\n" + rawText;

            DeepSeekClient.DeepSeekResult result = deepSeekClient.chat(
                    systemPrompt, userMessage, "deepseek-v4-flash", null);

            if (result != null && result.getContent() != null) {
                return result.getContent().trim();
            }
            return null;
        } catch (Exception e) {
            return null; // fallback to raw OCR if DeepSeek fails
        }
    }

    /**
     * Health check
     */
    public boolean isHealthy() {
        try {
            restTemplate.getForObject(ocrWorkerUrl + "/health", Map.class);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
