package com.shizuku.translate.dto;

public class SseDoneEvent {
    private boolean done;
    private Long id;
    private String translatedText;
    private TokenUsage tokenUsage;
    /** @see TranslateResponse#fromSharedTranslation */
    private boolean fromSharedTranslation;
    /** @see TranslateResponse#fromCache */
    private boolean fromCache;

    public SseDoneEvent() {}

    public SseDoneEvent(Long id, String translatedText, TokenUsage tokenUsage) {
        this.done = true;
        this.id = id;
        this.translatedText = translatedText;
        this.tokenUsage = tokenUsage;
    }

    public SseDoneEvent(Long id, String translatedText, TokenUsage tokenUsage,
                        boolean fromSharedTranslation, boolean fromCache) {
        this.done = true;
        this.id = id;
        this.translatedText = translatedText;
        this.tokenUsage = tokenUsage;
        this.fromSharedTranslation = fromSharedTranslation;
        this.fromCache = fromCache;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTranslatedText() {
        return translatedText;
    }

    public void setTranslatedText(String translatedText) {
        this.translatedText = translatedText;
    }

    public TokenUsage getTokenUsage() {
        return tokenUsage;
    }

    public void setTokenUsage(TokenUsage tokenUsage) {
        this.tokenUsage = tokenUsage;
    }

    public boolean isFromSharedTranslation() {
        return fromSharedTranslation;
    }

    public void setFromSharedTranslation(boolean fromSharedTranslation) {
        this.fromSharedTranslation = fromSharedTranslation;
    }

    public boolean isFromCache() {
        return fromCache;
    }

    public void setFromCache(boolean fromCache) {
        this.fromCache = fromCache;
    }
}
