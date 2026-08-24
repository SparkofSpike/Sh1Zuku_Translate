package com.shizuku.translate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@Configuration
public class DeepSeekConfig {

    @Bean
    @ConfigurationProperties(prefix = "deepseek.api")
    public DeepSeekProperties deepSeekProperties() {
        return new DeepSeekProperties();
    }

    @Bean
    public RestClient deepSeekRestClient(DeepSeekProperties props) {
        // DeepSeek can be overloaded (HTTP 503 "Service is too busy").
        // Without explicit timeouts a request hangs for minutes and the
        // SSE stream looks frozen; fail fast so we can retry instead.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(15_000);
        // The model may spend several minutes in pre-fill before sending the
        // first byte for a long novel. This is an inactivity timeout, not a
        // total request limit; the browser still owns user cancellation.
        factory.setReadTimeout(600_000); // 10 minutes between upstream bytes
        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "Bearer " + props.getKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public static class DeepSeekProperties {
        private String key;
        private String baseUrl;
        private String defaultModel;
        /** DeepSeek v4 thinking: "enabled" (AI reasoning, ~6x slower) | "disabled" (fast) */
        private String thinkingType = "disabled";

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getDefaultModel() { return defaultModel; }
        public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
        public String getThinkingType() { return thinkingType; }
        public void setThinkingType(String thinkingType) { this.thinkingType = thinkingType; }
    }
}
