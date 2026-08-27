package com.shizuku.translate.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

@Configuration
public class CorsConfig {

    private final List<String> allowedOriginPatterns;

    public CorsConfig(@Value("${app.cors.allowed-origin-patterns}") String allowedOriginPatterns) {
        this.allowedOriginPatterns = Arrays.stream(allowedOriginPatterns.split(","))
                .map(String::trim)
                .filter(pattern -> !pattern.isBlank())
                .map(pattern -> pattern.toLowerCase(Locale.ROOT))
                .toList();
        if (this.allowedOriginPatterns.isEmpty()) {
            throw new IllegalStateException("app.cors.allowed-origin-patterns must not be empty");
        }
    }

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // MV3 extension pages use an opaque `chrome-extension://<id>`
        // origin. If it is not explicitly allowed, Spring's CORS filter
        // rejects the authenticated plugin POST before the API-key filter
        // can run, producing 403 "Invalid CORS request".
        config.setAllowedOriginPatterns(allowedOriginPatterns);
        if (!allowedOriginPatterns.contains("chrome-extension://*")) {
            config.addAllowedOriginPattern("chrome-extension://*");
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsFilter(source);
    }
}
