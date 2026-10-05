package com.shizuku.translate.service.feedback;

import com.shizuku.translate.config.FeedbackConfig.FeedbackProperties;
import com.shizuku.translate.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the sampling policy for the feedback pipeline.
 *
 * <p>Layering: a fraction of genuine model-calling translations ({@code sample-rate}) becomes
 * samples immediately. Everything else — including cache and shared-translation replays — is
 * kept briefly in a bounded in-memory pending map, because dissatisfaction signals arrive
 * <em>after</em> the fact: a rating at or below 3, a re-translation, or an edit promotes the
 * pending translation into a full sample (those are rare and are exactly the rows worth
 * keeping). If the pending window has already expired the event alone is recorded.
 *
 * <p>Nothing here touches the model path's latency: the only work done on a request thread is
 * an RNG draw, a small record construction and a {@code put}/{@code offer}; redaction, file
 * I/O and serialisation live in {@link FeedbackStore}'s writer thread.
 *
 * <p>All failure modes degrade quietly: a rejected rating (frequency cap) is the only case that
 * surfaces to the caller, and the translation flow never observes feedback errors at all.
 */
@Service
public class TranslationFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(TranslationFeedbackService.class);
    private static final int RATE_LIMIT_PER_MINUTE = 20;
    private static final int DEDUP_MAX_ENTRIES = 50000;

    private final FeedbackProperties properties;
    private final FeedbackStore store;

    /** requestId → pending sample, insertion-ordered so pruning can stop at the first live row. */
    private final LinkedHashMap<String, PendingSample> pending = new LinkedHashMap<>();
    /** {@code requestId|deviceFp} → rating signature, so an unchanged re-submit is not re-counted. */
    private final LinkedHashMap<String, String> ratingDedup = new LinkedHashMap<>();
    /** deviceFp → sliding one-minute window for the anti-spam cap. */
    private final LinkedHashMap<String, RateWindow> rateWindows = new LinkedHashMap<>();

    private final AtomicReference<DailyCounter> dailyCounter =
            new AtomicReference<>(new DailyCounter(LocalDate.now(ZoneOffset.UTC), new AtomicInteger()));

    public TranslationFeedbackService(FeedbackProperties properties, FeedbackStore store) {
        this.properties = properties;
        this.store = store;
    }

    private record PendingSample(Instant createdAt, String sourceText, String targetText,
                                 String sourceLang, String targetLang, String engine, String model,
                                 Map<String, Object> params, int charCount, String bucketLength,
                                 Long latencyMs, String sourceSha256, String thinking,
                                 boolean truncated) {}

    private record DailyCounter(LocalDate day, AtomicInteger count) {}

    private static final class RateWindow {
        long windowStartMs;
        int count;
    }

    /**
     * Called once per completed translation on every path (fresh model call, personal cache
     * hit, shared replay). Only genuine model calls enter the random sampling pool — replays
     * would flood the dataset with duplicates — but every path lands in the pending map so a
     * later low rating or re-translation can still keep that exact translation in full.
     *
     * @param engine provider tag of the producing engine ({@code deepseek}, {@code shared},
     *               {@code cache}, …) — for replays this names where the text actually came from
     * @param model  exact model that produced the shown text
     */
    public void registerTranslation(String requestId, String sourceText, String translatedText,
                                    String engine, String model, String thinking,
                                    String targetLanguage, long latencyMs, boolean modelCall) {
        if (!properties.isEnabled() || requestId == null || requestId.isBlank()) {
            return;
        }
        try {
            String source = sourceText == null ? "" : sourceText;
            String target = translatedText == null ? "" : translatedText;
            int charCount = source.length();
            String bucket = charCount <= 50 ? "short" : charCount <= 500 ? "para" : "long";
            Map<String, Object> params = Map.of("temperature", 0.3);
            String sha = sha256Hex(source);

            if (modelCall && sampleNow()) {
                FeedbackSample sample = new FeedbackSample(requestId, Instant.now(), "auto",
                        targetLanguage, engine, model, params, charCount,
                        bucket, latencyMs, source, target, sha, "policy:rate", false,
                        thinking);
                store.submitSample(sample);
                return;
            }

            int max = Math.max(100, properties.getMaxPendingTextChars());
            boolean truncated = false;
            String pendingSource = source;
            String pendingTarget = target;
            if (pendingSource.length() > max) {
                pendingSource = pendingSource.substring(0, max);
                truncated = true;
            }
            if (pendingTarget.length() > max) {
                pendingTarget = pendingTarget.substring(0, max);
                truncated = true;
            }
            PendingSample entry = new PendingSample(Instant.now(), pendingSource, pendingTarget,
                    "auto", targetLanguage, engine, model, params,
                    charCount, bucket, latencyMs, sha, thinking, truncated);
            synchronized (pending) {
                pending.put(requestId, entry);
                prunePendingLocked();
            }
        } catch (Exception e) {
            // Feedback must never break the translation it observes.
            log.warn("登记翻译反馈候选失败（requestId={}）", requestId, e);
        }
    }

    /** Re-translation signal: the caller re-ran a previous result, which keeps that sample. */
    public void onRetranslate(String previousRequestId) {
        if (previousRequestId == null || previousRequestId.isBlank()) {
            return;
        }
        submitEvent(previousRequestId, "retranslate", Map.of());
    }

    /** A user rating; frequency-capped per device and deduplicated per (request, device). */
    public void submitRate(String requestId, int rating, List<String> tags, String comment,
                           String deviceFp, String permalink) {
        if (!properties.isEnabled()) {
            return;
        }
        if (requestId == null || requestId.isBlank()) {
            throw new BusinessException("缺少评分关联的 requestId");
        }
        if (!allowRate(deviceFp)) {
            throw new BusinessException("评分过于频繁，请稍后再试");
        }
        List<String> safeTags = tags == null ? List.of() : List.copyOf(tags);
        String safeComment = sanitizeComment(comment);
        String key = requestId + "|" + (deviceFp == null ? "" : deviceFp);
        // The comment is part of the signature: "rated 3, then typed why" must land as its
        // own event instead of being swallowed as a duplicate of the bare rating.
        String signature = rating + "|" + String.join(",", safeTags) + "|"
                + (safeComment == null ? "" : safeComment);
        synchronized (ratingDedup) {
            String previous = ratingDedup.get(key);
            if (signature.equals(previous)) {
                // Same choice re-submitted (double click, refresh, tag re-click): keep one row.
                return;
            }
            ratingDedup.put(key, signature);
            if (ratingDedup.size() > DEDUP_MAX_ENTRIES) {
                Iterator<Map.Entry<String, String>> it = ratingDedup.entrySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rating", rating);
        payload.put("tags", safeTags);
        payload.put("comment", safeComment);
        payload.put("device_fp", deviceFp);
        payload.put("permalink", permalink);
        store.submitEvent(new FeedbackEvent(requestId, Instant.now(), "rate", payload));
        if (rating <= 3) {
            keepPending(requestId, "low_rating");
        }
    }

    /** Rating comments are optional free text; capped so one voter cannot bloat the event log. */
    static final int MAX_COMMENT_CODE_POINTS = 500;

    /**
     * Trims the optional reason, treats blank as absent, and caps the text at
     * {@link #MAX_COMMENT_CODE_POINTS} code points without splitting surrogate pairs.
     */
    static String sanitizeComment(String comment) {
        if (comment == null) {
            return null;
        }
        String trimmed = comment.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.codePointCount(0, trimmed.length()) <= MAX_COMMENT_CODE_POINTS) {
            return trimmed;
        }
        return trimmed.substring(0, trimmed.offsetByCodePoints(0, MAX_COMMENT_CODE_POINTS));
    }

    /**
     * Any other behaviour event; {@code retranslate} and {@code edit} also promote the pending
     * sample, because those actions mean the user was not satisfied with the shown result.
     */
    public void submitEvent(String requestId, String event, Map<String, Object> payload) {
        if (!properties.isEnabled() || requestId == null || requestId.isBlank() || event == null) {
            return;
        }
        store.submitEvent(new FeedbackEvent(requestId, Instant.now(), event,
                payload == null ? Map.of() : payload));
        if ("retranslate".equals(event) || "edit".equals(event)) {
            keepPending(requestId, event);
        }
    }

    /** Test/diagnostic view of the pending cache size. */
    int pendingSize() {
        synchronized (pending) {
            return pending.size();
        }
    }

    // ── internals ──────────────────────────────────────────────────────────

    /** Promotes a pending translation into a full sample; no-op when it has expired or was sampled. */
    private void keepPending(String requestId, String sampledBy) {
        PendingSample entry;
        synchronized (pending) {
            entry = pending.remove(requestId);
        }
        if (entry == null) {
            return;
        }
        FeedbackSample sample = new FeedbackSample(requestId, Instant.now(), entry.sourceLang(),
                entry.targetLang(), entry.engine(), entry.model(), entry.params(), entry.charCount(),
                entry.bucketLength(), entry.latencyMs(), entry.sourceText(), entry.targetText(),
                entry.sourceSha256(), sampledBy, entry.truncated(), entry.thinking());
        store.submitSample(sample);
    }

    /** One random draw plus the daily ceiling; only called for genuine model translations. */
    private boolean sampleNow() {
        double rate = properties.getSampleRate();
        if (rate <= 0) {
            return false;
        }
        if (ThreadLocalRandom.current().nextDouble() >= Math.min(rate, 1.0)) {
            return false;
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        while (true) {
            DailyCounter current = dailyCounter.get();
            if (!current.day().equals(today)) {
                if (dailyCounter.compareAndSet(current, new DailyCounter(today, new AtomicInteger()))) {
                    continue;
                }
                continue;
            }
            int used = current.count().get();
            if (used >= properties.getDailySampleCap()) {
                return false;
            }
            if (current.count().compareAndSet(used, used + 1)) {
                return true;
            }
        }
    }

    /** Drops the oldest pending entries until both TTL and size constraints hold. */
    private void prunePendingLocked() {
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(Math.max(1, properties.getPendingTtlMinutes())));
        Iterator<Map.Entry<String, PendingSample>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, PendingSample> entry = it.next();
            if (entry.getValue().createdAt().isBefore(cutoff)) {
                it.remove();
            } else {
                // Insertion order: the first live entry means every later one is live too.
                break;
            }
        }
        int max = Math.max(1, properties.getPendingCacheSize());
        while (pending.size() > max) {
            Iterator<Map.Entry<String, PendingSample>> oldest = pending.entrySet().iterator();
            if (!oldest.hasNext()) {
                break;
            }
            oldest.next();
            oldest.remove();
        }
    }

    private boolean allowRate(String deviceFp) {
        if (deviceFp == null || deviceFp.isBlank()) {
            return true;
        }
        long now = System.currentTimeMillis();
        synchronized (rateWindows) {
            RateWindow window = rateWindows.get(deviceFp);
            if (window == null || now - window.windowStartMs > 60_000L) {
                RateWindow fresh = new RateWindow();
                fresh.windowStartMs = now;
                fresh.count = 1;
                rateWindows.put(deviceFp, fresh);
                trimWindowsLocked();
                return true;
            }
            window.count++;
            return window.count <= RATE_LIMIT_PER_MINUTE;
        }
    }

    private void trimWindowsLocked() {
        if (rateWindows.size() <= DEDUP_MAX_ENTRIES) {
            return;
        }
        Iterator<Map.Entry<String, RateWindow>> it = rateWindows.entrySet().iterator();
        if (it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    static String sha256Hex(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
