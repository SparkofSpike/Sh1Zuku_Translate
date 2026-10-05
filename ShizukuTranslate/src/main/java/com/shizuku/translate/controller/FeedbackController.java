package com.shizuku.translate.controller;

import com.shizuku.translate.dto.FeedbackEventRequest;
import com.shizuku.translate.dto.FeedbackRateRequest;
import com.shizuku.translate.exception.BusinessException;
import com.shizuku.translate.service.feedback.TranslationFeedbackService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Anonymous endpoints behind the rating widget and behaviour tracking on translation results.
 *
 * <p>Deliberately unauthenticated: the acceptance criteria require rating to work without a
 * login, and the anti-abuse story is the per-device frequency cap plus the
 * (request, device) dedup inside {@link TranslationFeedbackService}. Both endpoints return
 * immediately — persistence is asynchronous.
 */
@RestController
@RequestMapping("/api/v1/feedback")
public class FeedbackController {

    /** Events clients may report; {@code rate} has its own endpoint with stricter validation. */
    private static final Set<String> ALLOWED_EVENTS = Set.of("copy", "retranslate", "edit", "manual");

    private final TranslationFeedbackService feedbackService;

    public FeedbackController(TranslationFeedbackService feedbackService) {
        this.feedbackService = feedbackService;
    }

    @PostMapping("/rate")
    public ResponseEntity<?> rate(@RequestBody FeedbackRateRequest request) {
        if (request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BusinessException("缺少评分关联的 requestId");
        }
        if (request.getRating() == null || request.getRating() < 1 || request.getRating() > 5) {
            throw new BusinessException("评分必须在 1 到 5 之间");
        }
        feedbackService.submitRate(request.getRequestId(), request.getRating(), request.getTags(),
                request.getComment(), request.getDeviceFp(), request.getPermalink());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/event")
    public ResponseEntity<?> event(@RequestBody FeedbackEventRequest request) {
        if (request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BusinessException("缺少事件关联的 requestId");
        }
        if (request.getEvent() == null || !ALLOWED_EVENTS.contains(request.getEvent())) {
            throw new BusinessException("不支持的事件类型");
        }
        Map<String, Object> payload = request.getPayload() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getPayload());
        if (request.getDeviceFp() != null) {
            payload.putIfAbsent("device_fp", request.getDeviceFp());
        }
        if (request.getPermalink() != null) {
            payload.putIfAbsent("permalink", request.getPermalink());
        }
        feedbackService.submitEvent(request.getRequestId(), request.getEvent(), payload);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        return ResponseEntity.ok(body);
    }
}
