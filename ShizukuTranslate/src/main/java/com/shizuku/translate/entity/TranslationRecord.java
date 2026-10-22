package com.shizuku.translate.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "translation_records")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TranslationRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(columnDefinition = "TEXT", nullable = false)
    private String sourceText;
    @Column(columnDefinition = "TEXT")
    private String translatedText;
    @Column(nullable = false, length = 200)
    private String model;
    @Column(columnDefinition = "TEXT")
    private String customPrompt;
    /**
     * Resolved target-language tag (e.g. "zh-CN") the record was produced for. Older rows may
     * predate this column; null rows simply never match a shared-translation lookup, which is
     * the safe default — retranslating an unknown-language record is cheap compared to serving
     * a translation made for a different language.
     */
    @Column(length = 32)
    private String targetLanguage;
    /**
     * Client-visible correlation id (UUID) for the feedback pipeline: translation samples,
     * behaviour events and ratings all join on this value. Nullable so rows written before
     * the column existed (or by legacy paths) simply have none.
     */
    @Column(length = 36)
    private String requestId;
    /**
     * True when the model stopped at its output cap, so {@code translatedText} is only a prefix
     * of the translation. Nullable on purpose: Hibernate's schema update adds the column as
     * nullable, and every row written before it existed is "not known to be truncated".
     */
    private Boolean truncated;
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
