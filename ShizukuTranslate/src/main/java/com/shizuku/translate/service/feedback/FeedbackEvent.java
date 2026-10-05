package com.shizuku.translate.service.feedback;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One behaviour event row (one JSONL line in {@code events_YYYYMMDD.jsonl}): rating, copy,
 * re-translate or edit, all joined to a translation sample by {@code request_id}.
 */
public record FeedbackEvent(String requestId, Instant ts, String event, Map<String, Object> payload) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("request_id", requestId);
        map.put("ts", FeedbackSample.TS_FORMAT.format(ts));
        map.put("event", event);
        map.put("payload", payload == null ? Map.of() : payload);
        return map;
    }
}
