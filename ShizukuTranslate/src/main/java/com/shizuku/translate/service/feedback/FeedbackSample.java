package com.shizuku.translate.service.feedback;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One translation sample row (one JSONL line in {@code samples_YYYYMMDD.jsonl}).
 *
 * <p>Text fields are stored <em>after</em> redaction; the writer applies
 * {@link TextRedactor} before serialisation. Timestamps are UTC ISO8601 with millisecond
 * precision, matching the export tooling's expectations.
 */
public record FeedbackSample(
        String requestId,
        Instant ts,
        String sourceLang,
        String targetLang,
        String engine,
        String model,
        Map<String, Object> params,
        int charCount,
        String bucketLength,
        Long latencyMs,
        String sourceText,
        String targetText,
        String sourceSha256,
        String sampledBy,
        boolean truncated,
        String thinking) {

    static final DateTimeFormatter TS_FORMAT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    /** Fixed-field-order map matching the documented JSONL schema. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("request_id", requestId);
        map.put("ts", TS_FORMAT.format(ts));
        map.put("source_lang", sourceLang);
        map.put("target_lang", targetLang);
        map.put("engine", engine);
        map.put("model", model);
        map.put("params", params == null ? Map.of() : params);
        map.put("char_count", charCount);
        // LinkedHashMap, not Map.of: scene is deliberately null for now.
        Map<String, Object> bucket = new LinkedHashMap<>();
        bucket.put("length", bucketLength);
        bucket.put("scene", null);
        map.put("bucket", bucket);
        map.put("latency_ms", latencyMs);
        map.put("source_text", sourceText);
        map.put("target_text", targetText);
        map.put("source_sha256", sourceSha256);
        map.put("sampled_by", sampledBy);
        if (truncated) {
            map.put("truncated", true);
        }
        if (thinking != null) {
            map.put("thinking", thinking);
        }
        return map;
    }
}
