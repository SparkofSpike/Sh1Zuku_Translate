package com.shizuku.translate.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Cached full translation results keyed by (userId, cacheKey).
 * cacheKey = SHA-256 of userId + model + systemPrompt + sourceText,
 * so the same novel with the same settings is served instantly without
 * calling DeepSeek again.
 */
@Entity
@Table(name = "translation_cache")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslationCache {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String cacheKey;

    @Column(nullable = false, length = 50)
    private String model;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String translatedText;

    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
