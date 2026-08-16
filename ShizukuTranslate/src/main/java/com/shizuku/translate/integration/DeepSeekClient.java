package com.shizuku.translate.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.config.DeepSeekConfig;
import com.shizuku.translate.dto.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpServerErrorException;

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

    public DeepSeekResult chat(String systemPrompt, String userMessage, String model, String thinkingType) {
        return chat(systemPrompt, userMessage, model, thinkingType, null);
    }

    public DeepSeekResult chat(String systemPrompt, String userMessage, String model, String thinkingType, String apiKey) {
        Map<String, Object> request = Map.of(
                "model", model != null ? model : properties.getDefaultModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userMessage)
                ),
                "temperature", 0.3,
                "max_tokens", 100000,
                "thinking", Map.of("type", thinkingType != null ? thinkingType : properties.getThinkingType())
        );

        Map<String, Object> response = clientFor(apiKey).post()
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

    // DeepSeek v4 internal thinking ("enabled") roughly 6x-slows JSON-structured
    // output (measured: 7.7s vs 1.3s for 10 paragraphs). Default is disabled;
    // the client (extension) can opt into reasoning per request.
    public void chatStream(String systemPrompt, String userMessage, String model, String thinkingType,
                           Consumer<String> onToken, Consumer<TokenUsage> onComplete,
                           Consumer<String> onError) {
        chatStream(systemPrompt, userMessage, model, thinkingType, null, onToken, onComplete, onError);
    }

    public void chatStream(String systemPrompt, String userMessage, String model, String thinkingType, String apiKey,
                           Consumer<String> onToken, Consumer<TokenUsage> onComplete,
                           Consumer<String> onError) {
        // DeepSeek overloads frequently (HTTP 503 'Service is too busy').
        // Retry with backoff — the exchange throws before any token is
        // emitted, so a retry never duplicates output.
        final int MAX_RETRIES = 3;
        long startMs = System.currentTimeMillis();
        RestClient client = clientFor(apiKey);
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
        try {
            Map<String, Object> request = Map.of(
                    "model", model != null ? model : properties.getDefaultModel(),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userMessage)
                    ),
                    "temperature", 0.3,
                    "max_tokens", 100000,
                    "stream", true,
                    "thinking", Map.of("type", thinkingType != null ? thinkingType : properties.getThinkingType())
            );
            log.info("DeepSeek stream start (attempt {}), model={}, promptChars={}",
                    attempt, model != null ? model : properties.getDefaultModel(),
                    userMessage != null ? userMessage.length() : 0);

            client.post()
                    .uri("/chat/completions")
                    .body(request)
                    .exchange((clientRequest, clientResponse) -> {
                        TokenUsage finalUsage = null;

                        boolean clientDisconnected = false;
                        boolean firstTokenLogged = false;
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(clientResponse.getBody(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null && !clientDisconnected) {
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
                                                    if (!firstTokenLogged) {
                                                        firstTokenLogged = true;
                                                        log.info("DeepSeek first token after {} ms",
                                                                System.currentTimeMillis() - startMs);
                                                    }
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
                                        String msg = e.getMessage();
                                        // The client (browser) aborted the SSE connection — stop
                                        // consuming the DeepSeek stream instead of logging the
                                        // send failure as a parse error and burning API tokens.
                                        if (msg != null && (msg.contains("Client disconnected")
                                                || msg.contains("has already completed")
                                                || msg.contains("ClientAbortException"))) {
                                            log.info("Client disconnected, stopping DeepSeek stream");
                                            clientDisconnected = true;
                                        } else {
                                            log.warn("Failed to parse SSE chunk: {}", data, e);
                                        }
                                    }
                                }
                            }
                        }

                        // Only report completion if the client is still connected; otherwise
                        // the completion send would throw on an already-closed emitter.
                        if (!clientDisconnected) {
                            onComplete.accept(finalUsage);
                        }
                        return null;
                    });

        } catch (HttpServerErrorException e) {
                if (e.getStatusCode().value() == 503 && attempt < MAX_RETRIES) {
                    log.warn("DeepSeek busy (503), retrying {}/{}", attempt, MAX_RETRIES);
                    try {
                        Thread.sleep(1500L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                log.error("DeepSeek streaming request failed (attempt {}/{})", attempt, MAX_RETRIES, e);
                onError.accept("DeepSeek 服务繁忙，请稍后重试 (" + e.getStatusCode().value() + ")");
                break;
            } catch (Exception e) {
                log.error("DeepSeek streaming request failed (attempt {}/{})", attempt, MAX_RETRIES, e);
                onError.accept(e.getMessage());
                break;
            }
        }
    }

    private RestClient clientFor(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return restClient;
        }
        // Request-level header would also override the default, but mutate()
        // keeps the per-user client immutable and easy to reason about.
        return restClient.mutate().defaultHeader("Authorization", "Bearer " + apiKey.trim()).build();
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
