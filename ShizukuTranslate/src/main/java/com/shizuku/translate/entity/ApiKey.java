package com.shizuku.translate.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "api_keys")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Non-sensitive display prefix retained for management screens. */
    @Column(name = "key_prefix", nullable = false, unique = true, length = 32)
    private String keyPrefix;

    /** SHA-256 digest of the complete API key; the plaintext is never persisted. */
    @Column(name = "key_hash", unique = true, length = 64)
    private String keyHash;

    /** Deprecated plaintext column retained only while legacy rows are migrated. */
    @Deprecated
    @Column(name = "key_value", length = 64)
    private String legacyKeyValue;

    @Column(nullable = false, length = 50)
    private String name;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
    private boolean active;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
