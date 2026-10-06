package com.shizuku.translate.dto;

/**
 * Status/progress event for the streaming endpoints.
 *
 * <p>The original single-field form ({@code {"status":"ai-connected"}}) is kept for
 * compatibility. The long-novel pipeline adds the structured fields so the web UI can show
 * a three-stage progress strip (term extraction → chunked translation → consistency audit)
 * without parsing free text. {@code detail} is a human-readable fallback; clients that
 * localise should prefer building the text from {@code stage}/{@code current}/{@code total}/
 * {@code count}.
 */
public class SseStatusEvent {
    private String status;
    /** Long-novel pipeline stage: "extract" | "translate" | "audit". Null for plain statuses. */
    private String stage;
    /** Human-readable detail, e.g. "已提取 23 个专有名词". May be null. */
    private String detail;
    /** Current step (1-based) within the stage, when countable. */
    private Integer current;
    /** Total steps within the stage, when countable. */
    private Integer total;
    /** Countable outcome of a finished stage: extracted terms, repaired occurrences. */
    private Integer count;

    public SseStatusEvent() {}

    public SseStatusEvent(String status) {
        this.status = status;
    }

    public SseStatusEvent(String status, String stage, String detail,
                          Integer current, Integer total, Integer count) {
        this.status = status;
        this.stage = stage;
        this.detail = detail;
        this.current = current;
        this.total = total;
        this.count = count;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Integer getCurrent() { return current; }
    public void setCurrent(Integer current) { this.current = current; }
    public Integer getTotal() { return total; }
    public void setTotal(Integer total) { this.total = total; }
    public Integer getCount() { return count; }
    public void setCount(Integer count) { this.count = count; }
}
