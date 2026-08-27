package com.shizuku.translate.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_model_profiles")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiModelProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 20)
    private String provider;

    /**
     * Legacy inline key. New profiles should reference personalModelApiKey.
     * Kept nullable so existing databases and clients remain compatible.
     */
    @Column(length = 255)
    private String apiKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "personal_api_key_id")
    private PersonalModelApiKey personalModelApiKey;

    @Column(length = 500)
    private String baseUrl;

    @Column(nullable = false, length = 200)
    private String model;

    /** JSON array of models belonging to this single configuration. */
    @Column(length = 4000)
    private String models;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
