package com.shizuku.translate.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * A named translation preset. The system prompt appends the preset's prompt
 * whenever the user selects its name, so admins can add or tune style rules
 * without editing the configuration file.
 */
@Entity
@Table(name = "presets")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Preset {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Selected by name in translate requests; unique. */
    @Column(nullable = false, unique = true, length = 100)
    private String name;

    /** Instructions appended to the system prompt when the preset is selected. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
