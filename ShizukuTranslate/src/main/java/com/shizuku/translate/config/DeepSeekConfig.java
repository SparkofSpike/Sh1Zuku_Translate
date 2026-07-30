package com.shizuku.translate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class DeepSeekConfig {

    @Bean
    @ConfigurationProperties(prefix = "deepseek.api")
    public DeepSeekProperties deepSeekProperties() {
        return new DeepSeekProperties();
    }

    @Bean
    public RestClient deepSeekRestClient(DeepSeekProperties props) {
        return RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader("Authorization", "Bearer " + props.getKey())
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    public static class DeepSeekProperties {
        private String key;
        private String baseUrl;
        private String defaultModel;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getDefaultModel() { return defaultModel; }
        public void setDefaultModel(String defaultModel) { this.defaultModel = defaultModel; }
    }
}
