package com.shizuku.translate.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Short-lived code that lets the browser extension obtain an API key without the
 * user copy-pasting one: the plugin asks for a code, the logged-in user approves
 * it in the web UI, and the plugin polls until the key is handed over.
 */
@Entity
@Table(name = "plugin_device_codes")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginDeviceCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Human-typable 8-char code; ambiguous glyphs (0/O/1/I/L) are never generated. */
    @Column(name = "code", nullable = false, unique = true, length = 8)
    private String code;

    /** Owner, written at approval time; null while the code is still pending. */
    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private boolean approved;

    @Column(nullable = false)
    private boolean consumed;

    @Column(name = "key_name", length = 50)
    private String keyName;

    /**
     * Plaintext API key, held ONLY until the plugin picks it up or the code expires —
     * whichever happens first. It exists because the key has to travel from the server
     * to the plugin somehow, and it is cleared back to null the moment the poll that
     * delivers it commits. Never surfaced anywhere except that single poll response.
     */
    @Column(name = "pending_key", length = 128)
    private String pendingKey;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
