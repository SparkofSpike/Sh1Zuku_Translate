package com.shizuku.translate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DeepSeekConfig {

    @Bean
    @ConfigurationProperties(prefix = "deepseek.api")
    public DeepSeekProperties deepSeekProperties() {
        return new DeepSeekProperties();
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
