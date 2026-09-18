package com.shizuku.translate.dto;

import java.time.LocalDateTime;

public class TranslateResponse {
    private Long id;
    private String translatedText;
    private String model;
    private LocalDateTime createdAt;
    private TokenUsage tokenUsage;

    /**
     * True when the text was served from another user's earlier translation of the same source
     * text into the same target language (no model call happened). Distinct from a personal
     * cache hit, so the UI can label the result accordingly.
     */
    private boolean fromSharedTranslation;

    /** True when the text was replayed from this user's own translation cache. */
    private boolean fromCache;

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
    public boolean isFromSharedTranslation() { return fromSharedTranslation; }
    public void setFromSharedTranslation(boolean fromSharedTranslation) { this.fromSharedTranslation = fromSharedTranslation; }
    public boolean isFromCache() { return fromCache; }
    public void setFromCache(boolean fromCache) { this.fromCache = fromCache; }
}
