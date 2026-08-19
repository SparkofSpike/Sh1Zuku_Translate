package com.shizuku.translate.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.dto.TokenUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class AiModelClient {
    private static final Logger log = LoggerFactory.getLogger(AiModelClient.class);
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private final ObjectMapper objectMapper;
    private final SimpleClientHttpRequestFactory requestFactory;

    public AiModelClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.requestFactory = new SimpleClientHttpRequestFactory();
        this.requestFactory.setConnectTimeout(15_000);
        this.requestFactory.setReadTimeout(120_000);
    }

    public DeepSeekResult chat(String systemPrompt, String userMessage, AiModelConfig config) {
        RestClient client = clientFor(config);
        Map<String, Object> request = requestBody(systemPrompt, userMessage, config, false);
        Map<String, Object> response = client.post()
                .uri(config.isAnthropic() ? "/messages" : "/chat/completions")
                .headers(headers -> addAuth(headers, config))
                .body(request)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new RuntimeException("模型返回为空");
        }
        String content = config.isAnthropic()
                ? anthropicContent(response)
                : openAiContent(response);
        return new DeepSeekResult(content, parseUsage((Map<String, Object>) response.get("usage"), config));
    }

    public void chatStream(String systemPrompt, String userMessage, AiModelConfig config,
                           Consumer<String> onToken, Consumer<TokenUsage> onComplete,
                           Consumer<String> onError) {
        final int maxRetries = 3;
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                RestClient client = clientFor(config);
                Map<String, Object> request = requestBody(systemPrompt, userMessage, config, true);
                client.post()
                        .uri(config.isAnthropic() ? "/messages" : "/chat/completions")
                        .headers(headers -> addAuth(headers, config))
                        .body(request)
                        .exchange((requestMessage, response) -> {
                            TokenUsage[] usage = new TokenUsage[1];
                            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                                    response.getBody(), StandardCharsets.UTF_8))) {
                                String line;
                                String eventType = "";
                                while ((line = reader.readLine()) != null) {
                                    if (line.startsWith("event:")) {
                                        eventType = line.substring(6).trim();
                                    } else if (line.startsWith("data:")) {
                                        String data = line.substring(5).trim();
                                        if ("[DONE]".equals(data)) {
                                            break;
                                        }
                                        try {
                                            Map<String, Object> chunk = objectMapper.readValue(data, Map.class);
                                            String token = config.isAnthropic()
                                                    ? anthropicStreamToken(chunk, eventType)
                                                    : openAiStreamToken(chunk);
                                            if (token != null && !token.isEmpty()) {
                                                onToken.accept(token);
                                            }
                                            TokenUsage parsed = parseStreamUsage(chunk, config);
                                            if (parsed != null) {
                                                usage[0] = mergeUsage(usage[0], parsed, config);
                                            }
                                        } catch (Exception parseError) {
                                            log.debug("忽略无法解析的模型 SSE 事件: {}", data, parseError);
                                        }
                                        eventType = "";
                                    }
                                }
                            }
                            onComplete.accept(usage[0]);
                            return null;
                        });
                return;
            } catch (HttpServerErrorException e) {
                if (e.getStatusCode().value() == 503 && attempt < maxRetries) {
                    log.warn("模型服务繁忙，正在重试 {}/{}", attempt, maxRetries);
                    try {
                        Thread.sleep(1500L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        onError.accept("模型请求被中断");
                        return;
                    }
                    continue;
                }
                onError.accept("模型服务请求失败 (" + e.getStatusCode().value() + ")");
                return;
            } catch (Exception e) {
                log.error("模型流式请求失败", e);
                onError.accept(e.getMessage() == null ? "模型请求失败" : e.getMessage());
                return;
            }
        }
    }

    private Map<String, Object> requestBody(String systemPrompt, String userMessage,
                                             AiModelConfig config, boolean stream) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", config.getModel());
        request.put("temperature", 0.3);
        request.put("max_tokens", config.isAnthropic() ? 8192 : 100000);
        request.put("stream", stream);
        if (config.isAnthropic()) {
            request.put("system", systemPrompt);
            request.put("messages", List.of(Map.of("role", "user", "content", userMessage)));
        } else {
            request.put("messages", List.of(
                    Map.of("role", "system", "content", systemPrompt),
                    Map.of("role", "user", "content", userMessage)
            ));
            if (stream) {
                request.put("stream_options", Map.of("include_usage", true));
            }
            if ("enabled".equalsIgnoreCase(config.getThinkingType()) && "deepseek".equals(config.getProvider())) {
                request.put("thinking", Map.of("type", "enabled"));
            }
        }
        return request;
    }

    private RestClient clientFor(AiModelConfig config) {
        return RestClient.builder()
                .baseUrl(config.getBaseUrl().replaceAll("/+$", ""))
                .requestFactory(requestFactory)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    private void addAuth(HttpHeaders headers, AiModelConfig config) {
        if (config.isAnthropic()) {
            headers.set("x-api-key", config.getApiKey());
            headers.set("anthropic-version", ANTHROPIC_VERSION);
        } else {
            headers.setBearerAuth(config.getApiKey());
        }
    }

    @SuppressWarnings("unchecked")
    private String openAiContent(Map<String, Object> response) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new RuntimeException("模型返回中没有 choices");
        }
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        Object content = message == null ? null : message.get("content");
        return content == null ? "" : String.valueOf(content);
    }

    @SuppressWarnings("unchecked")
    private String anthropicContent(Map<String, Object> response) {
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        if (content == null) return "";
        StringBuilder result = new StringBuilder();
        for (Map<String, Object> block : content) {
            if (block.get("text") != null) result.append(block.get("text"));
        }
        return result.toString();
    }

    @SuppressWarnings("unchecked")
    private String openAiStreamToken(Map<String, Object> chunk) {
        List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
        if (choices == null || choices.isEmpty()) return null;
        Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
        Object content = delta == null ? null : delta.get("content");
        return content == null ? null : String.valueOf(content);
    }

    @SuppressWarnings("unchecked")
    private String anthropicStreamToken(Map<String, Object> chunk, String eventType) {
        if (!"content_block_delta".equals(eventType)
                && !"content_block_delta".equals(String.valueOf(chunk.get("type")))) return null;
        Map<String, Object> delta = (Map<String, Object>) chunk.get("delta");
        Object text = delta == null ? null : delta.get("text");
        return text == null ? null : String.valueOf(text);
    }

    @SuppressWarnings("unchecked")
    private TokenUsage parseStreamUsage(Map<String, Object> chunk, AiModelConfig config) {
        Map<String, Object> usage = (Map<String, Object>) chunk.get("usage");
        if (usage == null && config.isAnthropic()) {
            Map<String, Object> message = (Map<String, Object>) chunk.get("message");
            if (message != null) usage = (Map<String, Object>) message.get("usage");
        }
        return parseUsage(usage, config);
    }

    private TokenUsage mergeUsage(TokenUsage current, TokenUsage next, AiModelConfig config) {
        if (current == null || !config.isAnthropic()) return next;
        TokenUsage merged = new TokenUsage();
        merged.setPromptTokens(Math.max(current.getPromptTokens(), next.getPromptTokens()));
        merged.setCompletionTokens(Math.max(current.getCompletionTokens(), next.getCompletionTokens()));
        merged.setTotalTokens(merged.getPromptTokens() + merged.getCompletionTokens());
        return merged;
    }

    private TokenUsage parseUsage(Map<String, Object> usage, AiModelConfig config) {
        if (usage == null) return null;
        Number prompt = config.isAnthropic()
                ? number(usage, "input_tokens") : number(usage, "prompt_tokens");
        Number completion = config.isAnthropic()
                ? number(usage, "output_tokens") : number(usage, "completion_tokens");
        Number total = number(usage, "total_tokens");
        if (total == null && prompt != null && completion != null) {
            total = prompt.intValue() + completion.intValue();
        }
        if (prompt == null && completion == null && total == null) return null;
        TokenUsage result = new TokenUsage();
        result.setPromptTokens(prompt == null ? 0 : prompt.intValue());
        result.setCompletionTokens(completion == null ? 0 : completion.intValue());
        result.setTotalTokens(total == null ? result.getPromptTokens() + result.getCompletionTokens() : total.intValue());
        return result;
    }

    private Number number(Map<String, Object> values, String key) {
        Object value = values.get(key);
        return value instanceof Number ? (Number) value : null;
    }

    public static class AiModelConfig {
        private final String provider;
        private final String apiKey;
        private final String baseUrl;
        private final String model;
        private final String thinkingType;

        public AiModelConfig(String provider, String apiKey, String baseUrl, String model, String thinkingType) {
            this.provider = provider;
            this.apiKey = apiKey;
            this.baseUrl = baseUrl;
            this.model = model;
            this.thinkingType = thinkingType;
        }

        public String getProvider() { return provider; }
        public String getApiKey() { return apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public String getModel() { return model; }
        public String getThinkingType() { return thinkingType; }
        public boolean isAnthropic() { return "anthropic".equals(provider); }
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
