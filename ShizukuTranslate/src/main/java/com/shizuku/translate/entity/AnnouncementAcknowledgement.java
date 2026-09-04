package com.shizuku.translate.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "announcement_acknowledgements",
        uniqueConstraints = @UniqueConstraint(name = "uk_announcement_user",
                columnNames = {"announcement_id", "user_id"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnnouncementAcknowledgement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "announcement_id", nullable = false)
    private Announcement announcement;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private LocalDateTime acknowledgedAt;

    @PrePersist
    protected void onCreate() {
        acknowledgedAt = LocalDateTime.now();
    }
}
