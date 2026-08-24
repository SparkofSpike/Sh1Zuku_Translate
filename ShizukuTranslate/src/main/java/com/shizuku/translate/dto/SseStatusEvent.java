package com.shizuku.translate.dto;

public class SseStatusEvent {
    private String status;

    public SseStatusEvent() {}

    public SseStatusEvent(String status) {
        this.status = status;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
