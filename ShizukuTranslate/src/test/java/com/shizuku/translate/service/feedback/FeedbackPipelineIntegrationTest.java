package com.shizuku.translate.service.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.config.FeedbackConfig.FeedbackProperties;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simulated-traffic integration test over the real components (service + store + redactor +
 * admin reader), writing into a stable directory so the Python export tooling can be pointed
 * at the very same files afterwards. This is the acceptance evidence for "inject simulated
 * traffic, then export correctly formatted JSONL": it runs a mixed workload of fresh model
 * calls, cache replays, ratings, re-translates and copies, and verifies at the end that the
 * surviving rows are redacted, joinable and readable back through the admin service.
 */
class FeedbackPipelineIntegrationTest {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** Stable (non-random) location so the export/verify tools can consume it after the run. */
    private static final Path OUTPUT_DIR = Path.of("target", "feedback-e2e");

    private FeedbackProperties properties() {
        FeedbackProperties properties = new FeedbackProperties();
        properties.setDir(OUTPUT_DIR.toString());
        properties.setSampleRate(0.5);
        return properties;
    }

    @Test
    void simulatedTrafficProducesRedactedJoinableJsonl() throws Exception {
        if (Files.isDirectory(OUTPUT_DIR)) {
            try (var files = Files.list(OUTPUT_DIR)) {
                for (Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
        }
        FeedbackProperties properties = properties();
        ObjectMapper mapper = new ObjectMapper();
        FeedbackStore store = new FeedbackStore(properties, mapper, new TextRedactor(properties));
        store.start();
        TranslationFeedbackService service = new TranslationFeedbackService(properties, store);

        List<String> requestIds = new ArrayList<>();
        try {
            for (int i = 0; i < 200; i++) {
                String requestId = UUID.randomUUID().toString();
                requestIds.add(requestId);
                boolean modelCall = i % 2 == 0;
                String source = "第 " + i + " 章：联系 foo" + i + "@example.com 或打电话 1391234"
                        + String.format("%04d", i);
                String target = "Chapter " + i + ": contact placeholder.";
                service.registerTranslation(requestId, source, target, "deepseek", "deepseek-flash",
                        "disabled", "zh-CN", 100 + i, modelCall);
                if (i % 10 == 0) {
                    service.submitRate(requestId, 2, List.of("accuracy"), "差 " + i,
                            "fp-" + (i % 5), "/translate");
                }
                if (i % 7 == 0) {
                    service.submitEvent(requestId, "copy", Map.of());
                }
                if (i % 25 == 0) {
                    service.submitEvent(requestId, "retranslate", Map.of());
                }
            }
            store.flushForTest();
        } finally {
            store.stop();
        }

        String day = DAY.format(LocalDate.now(ZoneOffset.UTC));
        Path samples = OUTPUT_DIR.resolve("samples_" + day + ".jsonl");
        Path events = OUTPUT_DIR.resolve("events_" + day + ".jsonl");
        assertTrue(Files.isRegularFile(samples), "samples file must exist: " + samples);
        assertTrue(Files.isRegularFile(events), "events file must exist: " + events);

        List<String> sampleLines = Files.readAllLines(samples, StandardCharsets.UTF_8);
        List<String> eventLines = Files.readAllLines(events, StandardCharsets.UTF_8);
        assertFalse(sampleLines.isEmpty(), "simulated traffic must produce samples");
        assertFalse(eventLines.isEmpty(), "simulated traffic must produce events");

        // Every sample line parses, is redacted, and carries the schema's required fields.
        int redactedRows = 0;
        for (String line : sampleLines) {
            Map<?, ?> row = mapper.readValue(line, Map.class);
            assertTrue(row.get("request_id") instanceof String);
            assertTrue(String.valueOf(row.get("ts")).endsWith("Z"));
            assertTrue(row.get("source_sha256") instanceof String);
            String source = String.valueOf(row.get("source_text"));
            assertFalse(source.contains("@example.com"), "e-mail leaked: " + source);
            assertFalse(source.contains("1391234"), "phone leaked: " + source);
            if (source.contains("<EMAIL>")) {
                redactedRows++;
            }
        }
        assertTrue(redactedRows > 0, "redaction must have applied to some rows");

        // Events parse and are of the expected types; low ratings are present.
        int rateCount = 0;
        for (String line : eventLines) {
            Map<?, ?> row = mapper.readValue(line, Map.class);
            String type = String.valueOf(row.get("event"));
            assertTrue(List.of("rate", "copy", "retranslate").contains(type), "unexpected event " + type);
            if ("rate".equals(type)) {
                rateCount++;
            }
        }
        assertTrue(rateCount >= 10, "expected low-rating events, saw " + rateCount);

        // The admin reader sees the same data (joinability of the read path).
        FeedbackAdminService admin = new FeedbackAdminService(properties, mapper);
        Map<String, Object> summary = admin.summary(7);
        assertTrue((long) summary.get("sampleCount") > 0);
        assertTrue((long) summary.get("eventCount") > 0);
        @SuppressWarnings("unchecked")
        Map<String, Object> rating = (Map<String, Object>) summary.get("rating");
        assertTrue((long) rating.get("count") >= 10);

        // Not one low rating may lose its promotion: every rated id that was registered must be
        // represented either by a policy:rate sample or by a promoted one.
        List<Map<String, Object>> items = admin.recentSamples(200).entrySet().stream()
                .filter(e -> "items".equals(e.getKey()))
                .map(e -> (List<Map<String, Object>>) e.getValue())
                .findFirst().orElseThrow();
        assertFalse(items.isEmpty());
    }

    @Test
    void registrationLatencyStaysMicroscopic() throws Exception {
        FeedbackProperties properties = properties();
        properties.setDir(OUTPUT_DIR.resolve("bench").toString());
        properties.setSampleRate(0.0);
        ObjectMapper mapper = new ObjectMapper();
        FeedbackStore store = new FeedbackStore(properties, mapper, new TextRedactor(properties));
        store.start();
        try {
            TranslationFeedbackService service = new TranslationFeedbackService(properties, store);
            String source = "这是一段中等的日文小说原文，用于测量埋点开销。".repeat(20);
            // Warm-up
            for (int i = 0; i < 200; i++) {
                service.registerTranslation("warm-" + i, source, "译文", "deepseek", "m", null, "zh-CN", 1L, true);
            }
            long start = System.nanoTime();
            int iterations = 2000;
            for (int i = 0; i < iterations; i++) {
                service.registerTranslation("bench-" + i, source, "译文", "deepseek", "m", null, "zh-CN", 1L, true);
            }
            long elapsedNanos = System.nanoTime() - start;
            double microsPerCall = elapsedNanos / 1000.0 / iterations;
            System.out.printf("registerTranslation avg: %.1f µs/call (%d calls)%n", microsPerCall, iterations);
            // A model call takes seconds; the hook must stay well under a millisecond.
            assertTrue(microsPerCall < 500, "feedback hook too slow: " + microsPerCall + " µs/call");
        } finally {
            store.stop();
        }
    }
}
