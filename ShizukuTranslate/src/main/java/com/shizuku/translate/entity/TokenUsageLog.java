package com.shizuku.translate.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "token_usage_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsageLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 20)
    private String provider;

    /** LIVE, CACHE_BACKFILL, or RECORD_ESTIMATE. Nullable for pre-migration rows. */
    @Column(length = 30)
    private String sourceType;

    /** ID of the source cache/record for idempotent historical backfill. */
    private Long sourceId;

    /** True when the token count was estimated from stored text length. */
    private Boolean estimated;

    @Column(nullable = false, length = 200)
    private String model;

    @Column(nullable = false)
    private int promptTokens;

    @Column(nullable = false)
    private int completionTokens;

    @Column(nullable = false)
    private int totalTokens;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
