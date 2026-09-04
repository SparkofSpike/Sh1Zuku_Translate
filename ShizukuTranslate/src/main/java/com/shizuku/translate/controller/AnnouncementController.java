package com.shizuku.translate.controller;

import com.shizuku.translate.service.AnnouncementService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class AnnouncementController {

    private final AnnouncementService announcementService;

    public AnnouncementController(AnnouncementService announcementService) {
        this.announcementService = announcementService;
    }

    @GetMapping("/announcements")
    public ResponseEntity<?> getAnnouncements() {
        return ResponseEntity.ok(announcementService.list());
    }

    /** Authenticated: announcements the current user still has to confirm (pop-up list). */
    @GetMapping("/announcements/pending")
    public ResponseEntity<?> getPendingAnnouncements(Principal principal) {
        return ResponseEntity.ok(announcementService.pendingFor(principal.getName()));
    }

    /** Authenticated: mark the announcement as confirmed for the current user (idempotent). */
    @PostMapping("/announcements/{id}/acknowledge")
    public ResponseEntity<?> acknowledgeAnnouncement(@PathVariable Long id, Principal principal) {
        announcementService.acknowledge(principal.getName(), id);
        return ResponseEntity.ok(Map.of("message", "已确认"));
    }
}
