package com.shizuku.translate.service.feedback;

import com.shizuku.translate.config.FeedbackConfig.FeedbackProperties;
import com.shizuku.translate.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sampling-policy tests: the random layer keeps only model calls, the pending layer keeps
 * everything briefly, and dissatisfaction signals (rating <= 3, re-translate, edit) promote a
 * pending translation into a full sample exactly once. Rating dedup and the per-device
 * frequency cap are covered too.
 */
class TranslationFeedbackServiceTest {

    private FeedbackProperties properties;
    private FeedbackStore store;
    private TranslationFeedbackService service;

    @BeforeEach
    void setUp() {
        properties = new FeedbackProperties();
        store = mock(FeedbackStore.class);
        when(store.submitSample(any())).thenReturn(true);
        when(store.submitEvent(any())).thenReturn(true);
        service = new TranslationFeedbackService(properties, store);
    }

    private void register(String requestId, boolean modelCall) {
        service.registerTranslation(requestId, "原文文本", "译文文本", "deepseek", "deepseek-flash",
                "disabled", "zh-CN", 120L, modelCall);
    }

    @Test
    void modelCallIsSampledWhenRateIsFull() {
        properties.setSampleRate(1.0);
        register("r-1", true);

        ArgumentCaptor<FeedbackSample> captor = ArgumentCaptor.forClass(FeedbackSample.class);
        verify(store).submitSample(captor.capture());
        assertEquals("policy:rate", captor.getValue().sampledBy());
        assertEquals("r-1", captor.getValue().requestId());
        assertEquals("short", captor.getValue().bucketLength());
        assertEquals(0, service.pendingSize());
    }

    @Test
    void modelCallIsNotSampledWhenRateIsZeroButStaysPending() {
        properties.setSampleRate(0.0);
        register("r-1", true);

        verify(store, never()).submitSample(any());
        assertEquals(1, service.pendingSize());
    }

    @Test
    void cacheReplayNeverSamplesRandomlyButStaysPending() {
        properties.setSampleRate(1.0);
        register("r-1", false);

        verify(store, never()).submitSample(any());
        assertEquals(1, service.pendingSize());
    }

    @Test
    void lowRatingPromotesPendingSample() {
        properties.setSampleRate(0.0);
        register("r-1", true);

        service.submitRate("r-1", 3, List.of("accuracy"), null, "fp-1", "/translate");

        ArgumentCaptor<FeedbackSample> captor = ArgumentCaptor.forClass(FeedbackSample.class);
        verify(store).submitSample(captor.capture());
        assertEquals("low_rating", captor.getValue().sampledBy());
        assertEquals(0, service.pendingSize());
    }

    @Test
    void highRatingDoesNotPromote() {
        properties.setSampleRate(0.0);
        register("r-1", true);

        service.submitRate("r-1", 5, List.of(), null, "fp-1", "/translate");

        verify(store, never()).submitSample(any());
        assertEquals(1, service.pendingSize());
    }

    @Test
    void retranslateAndEditPromotePendingSample() {
        properties.setSampleRate(0.0);
        register("r-1", true);
        register("r-2", true);

        service.submitEvent("r-1", "retranslate", Map.of());
        service.submitEvent("r-2", "edit", Map.of("edited_text", "改后的文本"));

        ArgumentCaptor<FeedbackSample> captor = ArgumentCaptor.forClass(FeedbackSample.class);
        verify(store, times(2)).submitSample(captor.capture());
        List<String> origins = captor.getAllValues().stream().map(FeedbackSample::sampledBy).toList();
        assertTrue(origins.contains("retranslate"));
        assertTrue(origins.contains("edit"));
        assertEquals(0, service.pendingSize());
    }

    @Test
    void lowRatingWithoutPendingSampleOnlyWritesEvent() {
        properties.setSampleRate(0.0);
        // Never registered (e.g. expired) -> only the event row is written.
        service.submitRate("r-unknown", 2, List.of(), null, "fp-1", "/translate");

        verify(store, never()).submitSample(any());
        verify(store).submitEvent(any());
    }

    @Test
    void sameRatingResubmittedIsNotCountedTwice() {
        properties.setSampleRate(0.0);
        service.submitRate("r-1", 4, List.of("tone"), null, "fp-1", "/translate");
        service.submitRate("r-1", 4, List.of("tone"), null, "fp-1", "/translate");

        verify(store, times(1)).submitEvent(any());
    }

    @Test
    void changedRatingOverwritesAndIsWrittenAgain() {
        properties.setSampleRate(0.0);
        service.submitRate("r-1", 4, List.of(), null, "fp-1", "/translate");
        service.submitRate("r-1", 2, List.of("accuracy"), null, "fp-1", "/translate");

        verify(store, times(2)).submitEvent(any());
    }

    @Test
    void perDeviceRateLimitRejectsTwentyFirstSubmission() {
        for (int i = 0; i < 20; i++) {
            service.submitRate("r-" + i, 5, List.of(), null, "fp-flood", "/translate");
        }
        assertThrows(BusinessException.class,
                () -> service.submitRate("r-21", 5, List.of(), null, "fp-flood", "/translate"));
        // Another device is unaffected.
        service.submitRate("r-21", 5, List.of(), null, "fp-other", "/translate");
    }

    @Test
    void disabledPipelineDoesNothing() {
        properties.setEnabled(false);
        register("r-1", true);
        service.submitRate("r-1", 1, List.of(), null, "fp-1", "/translate");

        verify(store, never()).submitSample(any());
        verify(store, never()).submitEvent(any());
    }

    @Test
    void reTranslatedFromIsIgnoredWhenBlank() {
        service.onRetranslate(null);
        service.onRetranslate("  ");

        verify(store, never()).submitEvent(any());
    }
}
