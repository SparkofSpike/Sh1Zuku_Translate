package com.shizuku.translate.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true, nullable = false, length = 50)
    private String username;
    @Column(unique = true, nullable = false, length = 100)
    private String email;
    @Column(nullable = false)
    private String passwordHash;
    /** True once the address in {@code email} has been confirmed by a verification code.
     *  Nullable wrapper on purpose: existing rows stay NULL (treated as unverified) and
     *  Hibernate's schema update does not need a NOT-NULL default for a new column. */
    private Boolean emailVerified;
    /** Personal model API key; null/blank falls back to the server DeepSeek configuration. */
    @Column(length = 255)
    private String aiApiKey;
    /** deepseek, openai, or anthropic. */
    @Column(length = 20)
    private String aiProvider;
    @Column(length = 500)
    private String aiBaseUrl;
    @Column(length = 200)
    private String aiModel;
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
