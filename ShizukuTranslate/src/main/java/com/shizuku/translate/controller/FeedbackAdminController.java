package com.shizuku.translate.controller;

import com.shizuku.translate.service.feedback.FeedbackAdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;

/**
 * Read-only admin endpoints over the feedback JSONL files. Admin gating reuses the
 * {@link AdminController} check so the definition of "admin" stays in one place.
 */
@RestController
@RequestMapping("/api/v1/admin/feedback")
public class FeedbackAdminController {

    private final FeedbackAdminService feedbackAdminService;
    private final com.shizuku.translate.config.AppConfig.AppProperties appProperties;

    public FeedbackAdminController(FeedbackAdminService feedbackAdminService,
                                   com.shizuku.translate.config.AppConfig.AppProperties appProperties) {
        this.feedbackAdminService = feedbackAdminService;
        this.appProperties = appProperties;
    }

    @GetMapping("/summary")
    public ResponseEntity<?> summary(@RequestParam(defaultValue = "7") int days, Principal principal) {
        AdminController.checkAdmin(appProperties, principal);
        return ResponseEntity.ok(feedbackAdminService.summary(days));
    }

    @GetMapping("/samples")
    public ResponseEntity<?> samples(@RequestParam(defaultValue = "50") int limit, Principal principal) {
        AdminController.checkAdmin(appProperties, principal);
        return ResponseEntity.ok(feedbackAdminService.recentSamples(limit));
    }

    @GetMapping("/events")
    public ResponseEntity<?> events(@RequestParam(defaultValue = "50") int limit, Principal principal) {
        AdminController.checkAdmin(appProperties, principal);
        return ResponseEntity.ok(feedbackAdminService.recentEvents(limit));
    }
}
