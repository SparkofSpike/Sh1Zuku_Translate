package com.shizuku.translate.service.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.config.FeedbackConfig.FeedbackProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Read side of the feedback pipeline for the admin UI: aggregates the JSONL files into a
 * summary and returns recent samples / events. Reads stream line by line so a day file with
 * thousands of long samples never lands in memory at once, and a half-written last line
 * (the writer appends without locking) is skipped instead of failing the request.
 */
@Service
public class FeedbackAdminService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackAdminService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** How far back "the latest N" search walks day files before giving up. */
    private static final int RECENT_WINDOW_DAYS = 30;

    private final FeedbackProperties properties;
    private final ObjectMapper objectMapper;

    public FeedbackAdminService(FeedbackProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Aggregate counts over the last {@code days} UTC days (1-90, default caller-side). */
    public Map<String, Object> summary(int days) {
        int boundedDays = Math.max(1, Math.min(days, 90));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate since = today.minusDays(boundedDays - 1L);

        long[] counters = new long[2]; // [samples, events]
        Map<String, Long> eventsByType = new TreeMap<>();
        Map<String, Long> languagePairs = new TreeMap<>();
        Map<String, Long> models = new TreeMap<>();
        Map<String, Long> samplesByOrigin = new TreeMap<>();
        // Latest rating per (request_id, device_fp), mirroring the export tool's dedup, so a
        // changed rating overwrites rather than double counts.
        Map<String, Object[]> latestRatings = new LinkedHashMap<>();

        for (LocalDate day = since; !day.isAfter(today); day = day.plusDays(1L)) {
            String suffix = DAY.format(day);
            forEachRow("samples_" + suffix + ".jsonl", row -> {
                counters[0]++;
                increment(languagePairs, asString(row.get("target_lang")));
                increment(models, asString(row.get("model")));
                increment(samplesByOrigin, asString(row.get("sampled_by")));
            });
            forEachRow("events_" + suffix + ".jsonl", row -> {
                counters[1]++;
                String event = asString(row.get("event"));
                increment(eventsByType, event);
                if ("rate".equals(event)) {
                    Map<String, Object> payload = asMap(row.get("payload"));
                    Object rating = payload == null ? null : payload.get("rating");
                    if (rating instanceof Number number) {
                        String key = asString(row.get("request_id")) + "|"
                                + (payload.get("device_fp") == null ? "" : String.valueOf(payload.get("device_fp")));
                        String ts = asString(row.get("ts"));
                        Object[] previous = latestRatings.get(key);
                        boolean newer = previous == null
                                || (ts != null && (previous[0] == null || ts.compareTo((String) previous[0]) > 0));
                        if (newer) {
                            latestRatings.put(key, new Object[]{ts, number.intValue()});
                        }
                    }
                }
            });
        }

        long ratingCount = 0;
        long ratingSum = 0;
        long lowCount = 0;
        Map<String, Long> distribution = new TreeMap<>();
        for (int i = 1; i <= 5; i++) {
            distribution.put(String.valueOf(i), 0L);
        }
        for (Object[] value : latestRatings.values()) {
            int rating = (Integer) value[1];
            ratingCount++;
            ratingSum += rating;
            if (rating <= 3) {
                lowCount++;
            }
            distribution.merge(String.valueOf(rating), 1L, Long::sum);
        }

        Map<String, Object> rating = new LinkedHashMap<>();
        rating.put("count", ratingCount);
        rating.put("average", ratingCount == 0 ? null : Math.round((ratingSum / (double) ratingCount) * 100.0) / 100.0);
        rating.put("lowRate", ratingCount == 0 ? 0.0 : Math.round((lowCount / (double) ratingCount) * 10000.0) / 10000.0);
        rating.put("distribution", distribution);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("days", boundedDays);
        result.put("since", since.toString());
        result.put("until", today.toString());
        result.put("sampleCount", counters[0]);
        result.put("eventCount", counters[1]);
        result.put("eventsByType", eventsByType);
        result.put("languagePairs", languagePairs);
        result.put("models", models);
        result.put("samplesByOrigin", samplesByOrigin);
        result.put("rating", rating);
        return result;
    }

    /** Newest {@code limit} samples, most recent first. */
    public Map<String, Object> recentSamples(int limit) {
        return recent("samples_", limit);
    }

    /** Newest {@code limit} events, most recent first. */
    public Map<String, Object> recentEvents(int limit) {
        return recent("events_", limit);
    }

    private Map<String, Object> recent(String prefix, int limit) {
        int bounded = Math.max(1, Math.min(limit, 200));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Map<String, Object>> items = new ArrayList<>();
        outer:
        for (int back = 0; back < RECENT_WINDOW_DAYS; back++) {
            String fileName = prefix + DAY.format(today.minusDays(back)) + ".jsonl";
            // Newest rows sit at the tail of each day file; read the file fully but walk it
            // backwards and stop as soon as enough rows are collected.
            List<Map<String, Object>> rows = new ArrayList<>();
            forEachRow(fileName, rows::add);
            for (int i = rows.size() - 1; i >= 0; i--) {
                items.add(rows.get(i));
                if (items.size() >= bounded) {
                    break outer;
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", items.size());
        return result;
    }

    private void forEachRow(String fileName, Consumer<Map<String, Object>> consumer) {
        Path file = Path.of(properties.getDir()).resolve(fileName);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> row = objectMapper.readValue(line, Map.class);
                    consumer.accept(row);
                } catch (Exception e) {
                    // A half-written trailing line (writer mid-append) or a corrupt row: skip it.
                    log.debug("反馈数据行无法解析，已跳过: {}", fileName, e);
                }
            }
        } catch (Exception e) {
            log.warn("读取反馈数据文件失败 {}", file, e);
        }
    }

    private static void increment(Map<String, Long> map, String key) {
        if (key != null && !key.isBlank() && !"null".equals(key)) {
            map.merge(key, 1L, Long::sum);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }
}
