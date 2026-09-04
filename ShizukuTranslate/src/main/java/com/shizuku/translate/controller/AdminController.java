package com.shizuku.translate.controller;

import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.dto.AnnouncementRequest;
import com.shizuku.translate.service.AnnouncementService;
import com.shizuku.translate.service.UsageService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AnnouncementService announcementService;
    private final AppConfig.AppProperties appProperties;
    private final UsageService usageService;

    public AdminController(AnnouncementService announcementService,
                           AppConfig.AppProperties appProperties,
                           UsageService usageService) {
        this.announcementService = announcementService;
        this.appProperties = appProperties;
        this.usageService = usageService;
    }

    private void checkAdmin(Principal principal) {
        if (principal == null || !appProperties.isAdmin(principal.getName())) {
            throw new RuntimeException("无管理员权限");
        }
    }

    @GetMapping("/usage")
    public ResponseEntity<?> usage(Principal principal) {
        checkAdmin(principal);
        return ResponseEntity.ok(usageService.getAdminUsage());
    }

    @GetMapping("/usage/users/{userId}")
    public ResponseEntity<?> userUsage(@PathVariable Long userId, Principal principal) {
        checkAdmin(principal);
        return ResponseEntity.ok(usageService.getAdminUserUsage(userId));
    }

    @PostMapping("/announcements")
    public ResponseEntity<?> createAnnouncement(@Valid @RequestBody AnnouncementRequest request,
                                                Principal principal) {
        checkAdmin(principal);
        return ResponseEntity.ok(announcementService.create(request));
    }

    @GetMapping("/announcements/{id}/acknowledgements")
    public ResponseEntity<?> announcementAcknowledgements(@PathVariable Long id, Principal principal) {
        checkAdmin(principal);
        return ResponseEntity.ok(announcementService.acknowledgements(id));
    }

    @DeleteMapping("/announcements/{id}")
    public ResponseEntity<?> deleteAnnouncement(@PathVariable Long id, Principal principal) {
        checkAdmin(principal);
        announcementService.delete(id);
        return ResponseEntity.ok(Map.of("message", "公告已删除"));
    }
}
