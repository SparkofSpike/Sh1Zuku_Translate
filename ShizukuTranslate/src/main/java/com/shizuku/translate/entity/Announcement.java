package com.shizuku.translate.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "announcements")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Announcement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** True when every user must confirm this announcement once before it stops popping up.
     *  Nullable wrapper on purpose: existing rows stay NULL (treated as not required) and
     *  Hibernate's schema update does not need a NOT-NULL default for a new column. */
    private Boolean requireConfirmation;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public boolean requiresConfirmation() {
        return Boolean.TRUE.equals(requireConfirmation);
    }
}
