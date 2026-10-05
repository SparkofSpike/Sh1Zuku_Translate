package com.shizuku.translate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for the translation quality feedback pipeline.
 *
 * <p>Two data streams land in {@code app.feedback.dir} as UTC-dated JSONL files: samples
 * (redacted source/target pairs, rate-sampled) and behaviour events (rating, copy,
 * re-translate, edit). Sampling is layered: ordinary traffic is kept at {@code sampleRate},
 * while dissatisfaction signals — a rating at or below 3, a re-translation, an edit — keep
 * their sample in full.
 */
@Configuration
public class FeedbackConfig {

    @Bean
    @ConfigurationProperties(prefix = "app.feedback")
    public FeedbackProperties feedbackProperties() {
        return new FeedbackProperties();
    }

    public static class FeedbackProperties {
        /** Master switch; when false the pipeline is completely inert. */
        private boolean enabled = true;
        /** Fraction of model-calling translations kept as samples (1-5% recommended). */
        private double sampleRate = 0.02;
        /** Hard daily ceiling on rate-sampled rows, so a traffic spike cannot flood storage. */
        private int dailySampleCap = 5000;
        /** How many un-sampled recent translations stay in memory awaiting a dissatisfaction signal. */
        private int pendingCacheSize = 5000;
        /** Minutes an un-sampled translation stays eligible for a low-rating / re-translate keep. */
        private int pendingTtlMinutes = 1440;
        /** Bounded write queue; copies beyond it are dropped with a counter rather than blocking. */
        private int queueCapacity = 20000;
        /** Directory for the JSONL files (relative paths resolve against the process working dir). */
        private String dir = "./data/feedback";
        /** Pending (in-memory) sample texts are truncated to this many chars to bound memory. */
        private int maxPendingTextChars = 20000;
        /** Texts are truncated to this many chars when flushed to disk, so one long novel cannot bloat a day file. */
        private int maxStoredTextChars = 50000;
        private final Redaction redaction = new Redaction();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public double getSampleRate() { return sampleRate; }
        public void setSampleRate(double sampleRate) { this.sampleRate = sampleRate; }
        public int getDailySampleCap() { return dailySampleCap; }
        public void setDailySampleCap(int dailySampleCap) { this.dailySampleCap = dailySampleCap; }
        public int getPendingCacheSize() { return pendingCacheSize; }
        public void setPendingCacheSize(int pendingCacheSize) { this.pendingCacheSize = pendingCacheSize; }
        public int getPendingTtlMinutes() { return pendingTtlMinutes; }
        public void setPendingTtlMinutes(int pendingTtlMinutes) { this.pendingTtlMinutes = pendingTtlMinutes; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int queueCapacity) { this.queueCapacity = queueCapacity; }
        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
        public int getMaxPendingTextChars() { return maxPendingTextChars; }
        public void setMaxPendingTextChars(int maxPendingTextChars) { this.maxPendingTextChars = maxPendingTextChars; }
        public int getMaxStoredTextChars() { return maxStoredTextChars; }
        public void setMaxStoredTextChars(int maxStoredTextChars) { this.maxStoredTextChars = maxStoredTextChars; }
        public Redaction getRedaction() { return redaction; }

        public static class Redaction {
            private boolean enabled = true;
            /** Extra literal terms replaced with {@code <TERM>} on top of the built-in rules. */
            private List<String> extraTerms = new ArrayList<>();

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public List<String> getExtraTerms() { return extraTerms; }
            public void setExtraTerms(List<String> extraTerms) { this.extraTerms = extraTerms; }
        }
    }
}
