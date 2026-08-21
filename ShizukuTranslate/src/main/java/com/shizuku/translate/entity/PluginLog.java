package com.shizuku.translate.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "plugin_logs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PluginLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Submitter username — resolved from the authenticated API key / JWT. */
    @Column(nullable = false, length = 50)
    private String username;

    /** Extension version, e.g. 1.2.0+abc1234. */
    @Column(length = 50)
    private String version;

    /** Error detail reported by the extension. */
    @Column(nullable = false, length = 4000)
    private String errorMessage;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}