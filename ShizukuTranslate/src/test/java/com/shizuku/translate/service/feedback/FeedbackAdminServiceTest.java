package com.shizuku.translate.service.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.config.FeedbackConfig.FeedbackProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Read-side tests: aggregation, rate dedup, ordering of the recent lists and empty-state. */
class FeedbackAdminServiceTest {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private FeedbackAdminService service;

    @BeforeEach
    void setUp() {
        FeedbackProperties properties = new FeedbackProperties();
        properties.setDir(tempDir.toString());
        service = new FeedbackAdminService(properties, mapper);
    }

    private String today() {
        return DAY.format(LocalDate.now(ZoneOffset.UTC));
    }

    private void append(String fileName, Map<String, Object> row) throws Exception {
        Files.writeString(tempDir.resolve(fileName),
                mapper.writeValueAsString(row) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void sample(String requestId, String ts, String targetLang, String model, String origin) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("request_id", requestId);
        row.put("ts", ts);
        row.put("source_lang", "auto");
        row.put("target_lang", targetLang);
        row.put("model", model);
        row.put("sampled_by", origin);
        row.put("source_text", "原文-" + requestId);
        row.put("target_text", "译文-" + requestId);
        append("samples_" + today() + ".jsonl", row);
    }

    private void rate(String requestId, String ts, int rating, String deviceFp) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("request_id", requestId);
        row.put("ts", ts);
        row.put("event", "rate");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rating", rating);
        payload.put("tags", List.of());
        payload.put("device_fp", deviceFp);
        row.put("payload", payload);
        append("events_" + today() + ".jsonl", row);
    }

    private void event(String requestId, String ts, String type) throws Exception {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("request_id", requestId);
        row.put("ts", ts);
        row.put("event", type);
        row.put("payload", Map.of());
        append("events_" + today() + ".jsonl", row);
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryAggregatesCountsAndDedupsRatings() throws Exception {
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        sample("r-1", day + "T10:00:00.000Z", "zh-CN", "Index-Translate-35B-A3B", "policy:rate");
        sample("r-2", day + "T10:01:00.000Z", "zh-CN", "deepseek-flash", "low_rating");
        // Same (request, device): the later rating wins and counts once.
        rate("r-1", day + "T10:02:00.000Z", 2, "fp-a");
        rate("r-1", day + "T10:03:00.000Z", 5, "fp-a");
        rate("r-2", day + "T10:04:00.000Z", 1, "fp-b");
        event("r-2", day + "T10:05:00.000Z", "retranslate");

        Map<String, Object> summary = service.summary(7);

        assertEquals(2L, summary.get("sampleCount"));
        assertEquals(4L, summary.get("eventCount"));
        Map<String, Object> rating = (Map<String, Object>) summary.get("rating");
        assertEquals(2L, rating.get("count"), "duplicate (request, device) rating must collapse");
        assertEquals(3.0, rating.get("average"));
        assertEquals(0.5, rating.get("lowRate"));
        Map<String, Object> distribution = (Map<String, Object>) rating.get("distribution");
        assertEquals(1L, distribution.get("5"));
        assertEquals(1L, distribution.get("1"));
        Map<String, Object> languagePairs = (Map<String, Object>) summary.get("languagePairs");
        assertEquals(2L, languagePairs.get("zh-CN"));
        Map<String, Object> origins = (Map<String, Object>) summary.get("samplesByOrigin");
        assertEquals(1L, origins.get("policy:rate"));
        assertEquals(1L, origins.get("low_rating"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recentListsReturnNewestFirst() throws Exception {
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        sample("r-old", day + "T09:00:00.000Z", "zh-CN", "m", "policy:rate");
        sample("r-new", day + "T09:30:00.000Z", "zh-CN", "m", "policy:rate");
        event("r-old", day + "T09:01:00.000Z", "copy");
        event("r-new", day + "T09:31:00.000Z", "copy");

        Map<String, Object> samples = service.recentSamples(10);
        List<Map<String, Object>> sampleItems = (List<Map<String, Object>>) samples.get("items");
        assertEquals(2, sampleItems.size());
        assertEquals("r-new", sampleItems.get(0).get("request_id"));
        assertEquals("r-old", sampleItems.get(1).get("request_id"));

        Map<String, Object> events = service.recentEvents(10);
        List<Map<String, Object>> eventItems = (List<Map<String, Object>>) events.get("items");
        assertEquals(2, eventItems.size());
        assertEquals("r-new", eventItems.get(0).get("request_id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void limitIsRespected() throws Exception {
        String day = LocalDate.now(ZoneOffset.UTC).toString();
        for (int i = 0; i < 5; i++) {
            sample("r-" + i, day + "T09:0" + i + ":00.000Z", "zh-CN", "m", "policy:rate");
        }
        Map<String, Object> samples = service.recentSamples(3);
        List<Map<String, Object>> items = (List<Map<String, Object>>) samples.get("items");
        assertEquals(3, items.size());
        assertEquals("r-4", items.get(0).get("request_id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyDirectoryReturnsZeroCounts() {
        Map<String, Object> summary = service.summary(7);
        assertEquals(0L, summary.get("sampleCount"));
        assertEquals(0L, summary.get("eventCount"));
        Map<String, Object> rating = (Map<String, Object>) summary.get("rating");
        assertEquals(0L, rating.get("count"));
        assertEquals(0.0, rating.get("lowRate"));
        assertTrue(((List<?>) service.recentSamples(10).get("items")).isEmpty());
    }
}
