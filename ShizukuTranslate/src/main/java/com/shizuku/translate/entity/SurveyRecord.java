package com.shizuku.translate.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "survey_records")
public class SurveyRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    private int translationQuality;
    private int experienceQuality;
    private String favoriteFeature;
    @Column(columnDefinition = "TEXT")
    private String suggestion;
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public int getTranslationQuality() { return translationQuality; }
    public void setTranslationQuality(int translationQuality) { this.translationQuality = translationQuality; }
    public int getExperienceQuality() { return experienceQuality; }
    public void setExperienceQuality(int experienceQuality) { this.experienceQuality = experienceQuality; }
    public String getFavoriteFeature() { return favoriteFeature; }
    public void setFavoriteFeature(String favoriteFeature) { this.favoriteFeature = favoriteFeature; }
    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
