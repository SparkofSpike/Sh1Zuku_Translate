package com.shizuku.translate.dto;

public class SseDoneEvent {
    private boolean done;
    private Long id;
    private String translatedText;
    private TokenUsage tokenUsage;

    public SseDoneEvent() {}

    public SseDoneEvent(Long id, String translatedText, TokenUsage tokenUsage) {
        this.done = true;
        this.id = id;
        this.translatedText = translatedText;
        this.tokenUsage = tokenUsage;
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
}
