package com.shizuku.translate.dto;

import java.time.LocalDateTime;

public class TranslateResponse {
    private Long id;
    private String translatedText;
    private String model;
    private LocalDateTime createdAt;
    private TokenUsage tokenUsage;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTranslatedText() { return translatedText; }
    public void setTranslatedText(String translatedText) { this.translatedText = translatedText; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public TokenUsage getTokenUsage() { return tokenUsage; }
    public void setTokenUsage(TokenUsage tokenUsage) { this.tokenUsage = tokenUsage; }
}
