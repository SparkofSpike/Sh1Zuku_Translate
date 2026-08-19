package com.shizuku.translate.controller;

import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.dto.AnnouncementRequest;
import com.shizuku.translate.service.AnnouncementService;
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

    public AdminController(AnnouncementService announcementService, AppConfig.AppProperties appProperties) {
        this.announcementService = announcementService;
        this.appProperties = appProperties;
    }

    private void checkAdmin(Principal principal) {
        if (principal == null || !appProperties.isAdmin(principal.getName())) {
            throw new RuntimeException("无管理员权限");
        }
    }

    @PostMapping("/announcements")
    public ResponseEntity<?> createAnnouncement(@Valid @RequestBody AnnouncementRequest request,
                                                Principal principal) {
        checkAdmin(principal);
        return ResponseEntity.ok(announcementService.create(request));
    }

    @DeleteMapping("/announcements/{id}")
    public ResponseEntity<?> deleteAnnouncement(@PathVariable Long id, Principal principal) {
        checkAdmin(principal);
        announcementService.delete(id);
        return ResponseEntity.ok(Map.of("message", "公告已删除"));
    }
}
