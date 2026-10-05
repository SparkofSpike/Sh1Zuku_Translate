package com.shizuku.translate.service.feedback;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.config.FeedbackConfig.FeedbackProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Asynchronous JSONL writer behind the feedback pipeline.
 *
 * <p>Request threads only enqueue a small immutable record and return; a single daemon writer
 * thread redacts, serialises and appends in batches, so a slow disk can never affect the
 * translation endpoints' latency (a full queue drops rows and counts them instead of blocking).
 *
 * <p>Files are named by the row's own UTC date — {@code samples_YYYYMMDD.jsonl} and
 * {@code events_YYYYMMDD.jsonl} under {@code app.feedback.dir} — so the export tooling reads a
 * closed day simply by its file name.
 */
@Component
public class FeedbackStore {

    private static final Logger log = LoggerFactory.getLogger(FeedbackStore.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final FeedbackProperties properties;
    private final ObjectMapper objectMapper;
    private final TextRedactor redactor;

    private final BlockingQueue<QueuedItem> queue;
    private final AtomicLong droppedRows = new AtomicLong();
    private final AtomicLong writtenSamples = new AtomicLong();
    private final AtomicLong writtenEvents = new AtomicLong();
    private volatile boolean running = true;
    private Thread writerThread;

    public FeedbackStore(FeedbackProperties properties, ObjectMapper objectMapper, TextRedactor redactor) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.redactor = redactor;
        this.queue = new LinkedBlockingQueue<>(Math.max(1, properties.getQueueCapacity()));
    }

    /** A pending row; payload is either a sample or an event, decided by {@code sample}. */
    private record QueuedItem(boolean sample, FeedbackSample sampleRow, FeedbackEvent eventRow) {}

    @PostConstruct
    void start() {
        writerThread = new Thread(this::loop, "feedback-writer");
        writerThread.setDaemon(true);
        writerThread.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        Thread thread = writerThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(5000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Enqueues one sample; returns false when the bounded queue is full (row dropped). */
    public boolean submitSample(FeedbackSample sample) {
        if (sample == null || !properties.isEnabled()) {
            return false;
        }
        if (queue.offer(new QueuedItem(true, sample, null))) {
            return true;
        }
        droppedRows.incrementAndGet();
        log.warn("反馈样本队列已满，丢弃一条样本（累计丢弃 {}）", droppedRows.get());
        return false;
    }

    /** Enqueues one event; returns false when the bounded queue is full (row dropped). */
    public boolean submitEvent(FeedbackEvent event) {
        if (event == null || !properties.isEnabled()) {
            return false;
        }
        if (queue.offer(new QueuedItem(false, null, event))) {
            return true;
        }
        droppedRows.incrementAndGet();
        log.warn("反馈事件队列已满，丢弃一条事件（累计丢弃 {}）", droppedRows.get());
        return false;
    }

    public long getDroppedRows() { return droppedRows.get(); }
    public long getWrittenSamples() { return writtenSamples.get(); }
    public long getWrittenEvents() { return writtenEvents.get(); }

    /** Blocks until the queue is empty and every queued row has been flushed (tests / shutdown). */
    void flushForTest() {
        long deadline = System.currentTimeMillis() + 5000L;
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // Give the writer a moment to finish the batch it may already hold.
        try {
            Thread.sleep(50L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void loop() {
        List<QueuedItem> batch = new ArrayList<>(64);
        while (true) {
            QueuedItem item;
            try {
                item = queue.poll(300L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                if (!running && queue.isEmpty()) {
                    return;
                }
                continue;
            }
            if (item == null) {
                if (!running && queue.isEmpty()) {
                    return;
                }
                continue;
            }
            batch.clear();
            batch.add(item);
            queue.drainTo(batch, 500);
            writeBatch(batch);
        }
    }

    private void writeBatch(List<QueuedItem> batch) {
        Map<String, StringBuilder> byFile = new HashMap<>();
        for (QueuedItem item : batch) {
            try {
                String json = item.sample() ? serializeSample(item.sampleRow()) : serializeEvent(item.eventRow());
                if (json == null) {
                    continue;
                }
                Instant ts = item.sample() ? item.sampleRow().ts() : item.eventRow().ts();
                LocalDate day = ts.atZone(ZoneOffset.UTC).toLocalDate();
                String name = (item.sample() ? "samples_" : "events_") + DAY.format(day) + ".jsonl";
                byFile.computeIfAbsent(name, key -> new StringBuilder()).append(json).append('\n');
            } catch (Exception e) {
                log.warn("无法序列化一条反馈数据，已跳过", e);
            }
        }
        if (byFile.isEmpty()) {
            return;
        }
        Path dir = Path.of(properties.getDir());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("无法创建反馈数据目录 {}，本批 {} 个文件写入失败", dir, byFile.size(), e);
            return;
        }
        for (Map.Entry<String, StringBuilder> entry : byFile.entrySet()) {
            Path file = dir.resolve(entry.getKey());
            try {
                Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                for (QueuedItem item : batch) {
                    if (entry.getKey().startsWith(item.sample() ? "samples_" : "events_")) {
                        if (item.sample()) writtenSamples.incrementAndGet(); else writtenEvents.incrementAndGet();
                    }
                }
            } catch (IOException e) {
                log.error("反馈数据写入失败 {}", file, e);
            }
        }
    }

    /**
     * Redacts then serialises. Redaction happens here — in the writer thread — which is still
     * strictly before anything touches disk, so no un-redacted text is ever persisted.
     * Oversized texts (a whole novel arrives as one request) are truncated before serialisation
     * so a single day file cannot grow without bound; the row is flagged with {@code truncated}.
     */
    private String serializeSample(FeedbackSample sample) throws IOException {
        Map<String, Object> map = sample.toMap();
        String source = redactor.redact((String) map.get("source_text"));
        String target = redactor.redact((String) map.get("target_text"));
        int max = Math.max(1000, properties.getMaxStoredTextChars());
        boolean truncated = Boolean.TRUE.equals(map.get("truncated"));
        if (source != null && source.length() > max) {
            source = source.substring(0, max);
            truncated = true;
        }
        if (target != null && target.length() > max) {
            target = target.substring(0, max);
            truncated = true;
        }
        map.put("source_text", source);
        map.put("target_text", target);
        if (truncated) {
            map.put("truncated", true);
        }
        return objectMapper.writeValueAsString(map);
    }

    private String serializeEvent(FeedbackEvent event) throws IOException {
        FeedbackEvent redacted = event;
        Map<String, Object> payload = event.payload();
        if (payload != null && !payload.isEmpty()) {
            Map<String, Object> copy = new HashMap<>(payload);
            if (copy.get("comment") instanceof String comment) {
                copy.put("comment", redactor.redact(comment));
            }
            if (copy.get("edited_text") instanceof String edited) {
                copy.put("edited_text", redactor.redact(edited));
            }
            redacted = new FeedbackEvent(event.requestId(), event.ts(), event.event(), copy);
        }
        return objectMapper.writeValueAsString(redacted.toMap());
    }
}
