package com.shizuku.translate.dto;

public class SseTokenEvent {
    private String token;

    public SseTokenEvent() {}

    public SseTokenEvent(String token) {
        this.token = token;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
