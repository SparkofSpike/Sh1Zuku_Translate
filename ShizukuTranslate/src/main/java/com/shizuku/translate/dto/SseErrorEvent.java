package com.shizuku.translate.dto;

public class SseErrorEvent {
    private String error;

    public SseErrorEvent() {}

    public SseErrorEvent(String error) {
        this.error = error;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
