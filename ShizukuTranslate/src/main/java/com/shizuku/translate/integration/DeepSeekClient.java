package com.shizuku.translate.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.config.DeepSeekConfig;
import com.shizuku.translate.dto.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class DeepSeekClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekClient.class);

    private final RestClient restClient;
    private final DeepSeekConfig.DeepSeekProperties properties;
    private final ObjectMapper objectMapper;

    public DeepSeekClient(RestClient deepSeekRestClient, DeepSeekConfig.DeepSeekProperties properties, ObjectMapper objectMapper) {
        this.restClient = deepSeekRestClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public DeepSeekResult chat(String systemPrompt, String userMessage, String model) {
        Map<String, Object> request = Map.of(
                "model", model != null ? model : properties.getDefaultModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                ),
                "temperature", 0.3,
                "max_tokens", 100000
        );

        Map<String, Object> response = restClient.post()
                .uri("/chat/completions")
                .body(request)
                .retrieve()
                .body(Map.class);

        if (response == null || !response.containsKey("choices")) {
            throw new RuntimeException("Invalid response from DeepSeek API");
        }

        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        String content = (String) message.get("content");

        TokenUsage usage = null;
        if (response.containsKey("usage")) {
            Map<String, Object> usageMap = (Map<String, Object>) response.get("usage");
            usage = new TokenUsage();
            usage.setPromptTokens(((Number) usageMap.get("prompt_tokens")).intValue());
            usage.setCompletionTokens(((Number) usageMap.get("completion_tokens")).intValue());
            usage.setTotalTokens(((Number) usageMap.get("total_tokens")).intValue());
        }

        return new DeepSeekResult(content, usage);
    }

    public void chatStream(String systemPrompt, String userMessage, String model,
                           Consumer<String> onToken, Consumer<TokenUsage> onComplete,
                           Consumer<String> onError) {
        try {
            Map<String, Object> request = Map.of(
                    "model", model != null ? model : properties.getDefaultModel(),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userMessage)
                    ),
                    "temperature", 0.3,
                    "max_tokens", 100000,
                    "stream", true
            );

            restClient.post()
                    .uri("/chat/completions")
                    .body(request)
                    .exchange((clientRequest, clientResponse) -> {
                        TokenUsage finalUsage = null;

                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(clientResponse.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (line.startsWith("data: ")) {
                                    String data = line.substring(6).trim();
                                    if ("[DONE]".equals(data)) {
                                        break;
                                    }
                                    try {
                                        Map<String, Object> chunk = objectMapper.readValue(data, Map.class);
                                        List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                                        if (choices != null && !choices.isEmpty()) {
                                            Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                                            if (delta != null && delta.containsKey("content")) {
                                                String token = (String) delta.get("content");
                                                if (token != null) {
                                                    onToken.accept(token);
                                                }
                                            }
                                        }
                                        if (chunk.containsKey("usage")) {
                                            Map<String, Object> usageMap = (Map<String, Object>) chunk.get("usage");
                                            finalUsage = new TokenUsage();
                                            finalUsage.setPromptTokens(((Number) usageMap.get("prompt_tokens")).intValue());
                                            finalUsage.setCompletionTokens(((Number) usageMap.get("completion_tokens")).intValue());
                                            finalUsage.setTotalTokens(((Number) usageMap.get("total_tokens")).intValue());
                                        }
                                    } catch (Exception e) {
                                        log.warn("Failed to parse SSE chunk: {}", data, e);
                                    }
                                }
                            }
                        }

                        onComplete.accept(finalUsage);
                        return null;
                    });

        } catch (Exception e) {
            log.error("DeepSeek streaming request failed", e);
            onError.accept(e.getMessage());
        }
    }

    public static class DeepSeekResult {
        private final String content;
        private final TokenUsage usage;

        public DeepSeekResult(String content, TokenUsage usage) {
            this.content = content;
            this.usage = usage;
        }
        public String getContent() { return content; }
        public TokenUsage getUsage() { return usage; }
    }
}
