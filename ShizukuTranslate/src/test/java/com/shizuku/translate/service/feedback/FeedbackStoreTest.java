package com.shizuku.translate.service.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.config.FeedbackConfig.FeedbackProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end store behaviour: rows land as UTC-dated JSONL, text is redacted on the way out,
 * and the queue drains without any request-thread involvement.
 */
class FeedbackStoreTest {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    @TempDir
    Path tempDir;

    private FeedbackStore newStore(FeedbackProperties properties) {
        FeedbackStore store = new FeedbackStore(properties, new ObjectMapper(), new TextRedactor(properties));
        store.start();
        return store;
    }

    @Test
    void writesSamplesAndEventsToUtcDatedFiles() throws Exception {
        FeedbackProperties properties = new FeedbackProperties();
        properties.setDir(tempDir.toString());
        FeedbackStore store = newStore(properties);
        try {
            FeedbackSample sample = new FeedbackSample("r-1", Instant.now(), "auto", "zh-CN",
                    "deepseek", "deepseek-flash", Map.of("temperature", 0.3), 12, "short", 88L,
                    "联系 foo@bar.com", "联系 <EMAIL>", "abc123", "policy:rate", false, "disabled");
            store.submitSample(sample);
            store.submitEvent(new FeedbackEvent("r-1", Instant.now(), "rate",
                    Map.of("rating", 3, "tags", List.of("accuracy"), "comment", "call 13812345678",
                            "device_fp", "fp", "permalink", "/translate")));
            store.flushForTest();
        } finally {
            store.stop();
        }

        String day = DAY.format(LocalDate.now(ZoneOffset.UTC));
        Path samples = tempDir.resolve("samples_" + day + ".jsonl");
        Path events = tempDir.resolve("events_" + day + ".jsonl");
        assertTrue(Files.isRegularFile(samples), "samples file missing: " + samples);
        assertTrue(Files.isRegularFile(events), "events file missing: " + events);

        ObjectMapper mapper = new ObjectMapper();
        Map<?, ?> sampleRow = mapper.readValue(Files.readString(samples, StandardCharsets.UTF_8).lines().findFirst().orElseThrow(), Map.class);
        assertEquals("r-1", sampleRow.get("request_id"));
        assertEquals("policy:rate", sampleRow.get("sampled_by"));
        assertEquals(12, sampleRow.get("char_count"));
        assertEquals("short", ((Map<?, ?>) sampleRow.get("bucket")).get("length"));
        // Redaction is applied in the writer thread, before the row touches disk.
        assertEquals("联系 <EMAIL>", sampleRow.get("source_text"));

        Map<?, ?> eventRow = mapper.readValue(Files.readString(events, StandardCharsets.UTF_8).lines().findFirst().orElseThrow(), Map.class);
        assertEquals("rate", eventRow.get("event"));
        Map<?, ?> payload = (Map<?, ?>) eventRow.get("payload");
        assertEquals(3, payload.get("rating"));
        assertEquals("call <PHONE>", payload.get("comment"));
    }

    @Test
    void truncatesOversizedTextsAndFlagsTheRow() throws Exception {
        FeedbackProperties properties = new FeedbackProperties();
        properties.setDir(tempDir.toString());
        properties.setMaxStoredTextChars(1000);
        FeedbackStore store = newStore(properties);
        try {
            String longText = "长".repeat(5000);
            store.submitSample(new FeedbackSample("r-long", Instant.now(), "auto", "zh-CN",
                    "deepseek", "deepseek-flash", Map.of(), 5000, "long", 10L,
                    longText, longText, "sha", "policy:rate", false, null));
            store.flushForTest();
        } finally {
            store.stop();
        }

        String day = DAY.format(LocalDate.now(ZoneOffset.UTC));
        Path samples = tempDir.resolve("samples_" + day + ".jsonl");
        String line = Files.readString(samples, StandardCharsets.UTF_8).strip();
        Map<?, ?> row = new ObjectMapper().readValue(line, Map.class);
        assertEquals(true, row.get("truncated"));
        assertEquals(1000, String.valueOf(row.get("source_text")).length());
        assertEquals(1000, String.valueOf(row.get("target_text")).length());
    }

    @Test
    void queueOverflowDropsRowsInsteadOfBlocking() {
        FeedbackProperties properties = new FeedbackProperties();
        properties.setDir(tempDir.toString());
        properties.setQueueCapacity(2);
        // Never start the writer: the queue must fill and then report drops.
        FeedbackStore store = new FeedbackStore(properties, new ObjectMapper(), new TextRedactor(properties));
        FeedbackEvent event = new FeedbackEvent("r", Instant.now(), "copy", Map.of());
        assertTrue(store.submitEvent(event));
        assertTrue(store.submitEvent(event));
        assertFalse(store.submitEvent(event));
        assertEquals(1, store.getDroppedRows());
        assertEquals(0, store.getWrittenEvents());
    }

    @Test
    void disabledPipelineRejectsSubmissions() {
        FeedbackProperties properties = new FeedbackProperties();
        properties.setEnabled(false);
        FeedbackStore store = new FeedbackStore(properties, new ObjectMapper(), new TextRedactor(properties));
        assertFalse(store.submitEvent(new FeedbackEvent("r", Instant.now(), "copy", Map.of())));
        try (Stream<Path> files = Files.list(tempDir)) {
            assertEquals(0, files.count());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
