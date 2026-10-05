package com.shizuku.translate.dto;

import java.util.Map;

/** Body of {@code POST /api/v1/feedback/event}: a behaviour event other than a rating. */
public class FeedbackEventRequest {
    private String requestId;
    private String event;
    private Map<String, Object> payload;
    private String deviceFp;
    private String permalink;

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    public String getDeviceFp() { return deviceFp; }
    public void setDeviceFp(String deviceFp) { this.deviceFp = deviceFp; }
    public String getPermalink() { return permalink; }
    public void setPermalink(String permalink) { this.permalink = permalink; }
}
