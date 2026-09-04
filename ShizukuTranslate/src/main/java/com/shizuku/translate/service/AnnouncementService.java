package com.shizuku.translate.service;

import com.shizuku.translate.dto.AnnouncementRequest;
import com.shizuku.translate.entity.Announcement;
import com.shizuku.translate.entity.AnnouncementAcknowledgement;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.exception.ResourceNotFoundException;
import com.shizuku.translate.repository.AnnouncementAcknowledgementRepository;
import com.shizuku.translate.repository.AnnouncementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementAcknowledgementRepository acknowledgementRepository;
    private final UserService userService;

    public AnnouncementService(AnnouncementRepository announcementRepository,
                               AnnouncementAcknowledgementRepository acknowledgementRepository,
                               UserService userService) {
        this.announcementRepository = announcementRepository;
        this.acknowledgementRepository = acknowledgementRepository;
        this.userService = userService;
    }

    public List<Map<String, Object>> list() {
        return announcementRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toMap)
                .toList();
    }

    /** Announcements flagged as requiring confirmation that the given user has not confirmed yet. */
    public List<Map<String, Object>> pendingFor(String username) {
        User user = userService.findByUsername(username);
        return announcementRepository.findPendingForUser(user.getId()).stream()
                .map(this::toMap)
                .toList();
    }

    public Map<String, Object> create(AnnouncementRequest request) {
        Announcement announcement = Announcement.builder()
                .title(request.getTitle().trim())
                .content(request.getContent().trim())
                .requireConfirmation(request.isRequireConfirmation())
                .build();
        return toMap(announcementRepository.save(announcement));
    }

    @Transactional
    public void delete(Long id) {
        if (!announcementRepository.existsById(id)) {
            throw new ResourceNotFoundException("Announcement not found");
        }
        // Remove acknowledgement rows first so no orphans outlive the announcement.
        acknowledgementRepository.deleteByAnnouncementId(id);
        announcementRepository.deleteById(id);
    }

    /**
     * Record that the user confirmed the announcement. Idempotent: confirming twice is a no-op.
     * Announcements that do not require confirmation are simply accepted without a record.
     */
    @Transactional
    public void acknowledge(String username, Long announcementId) {
        User user = userService.findByUsername(username);
        Announcement announcement = announcementRepository.findById(announcementId)
                .orElseThrow(() -> new ResourceNotFoundException("Announcement not found"));
        if (!announcement.requiresConfirmation()) {
            return;
        }
        if (acknowledgementRepository.findByAnnouncementIdAndUserId(announcementId, user.getId()).isPresent()) {
            return;
        }
        acknowledgementRepository.save(AnnouncementAcknowledgement.builder()
                .announcement(announcement)
                .user(user)
                .build());
    }

    /** Admin view: users who confirmed the announcement, newest first, plus a total count. */
    public Map<String, Object> acknowledgements(Long announcementId) {
        Announcement announcement = announcementRepository.findById(announcementId)
                .orElseThrow(() -> new ResourceNotFoundException("Announcement not found"));
        List<Map<String, Object>> users = acknowledgementRepository
                .findByAnnouncementIdOrderByAcknowledgedAtDesc(announcementId).stream()
                .map(k -> Map.<String, Object>of(
                        "username", k.getUser().getUsername(),
                        "email", k.getUser().getEmail(),
                        "acknowledgedAt", k.getAcknowledgedAt().toString()
                )).toList();
        return Map.of(
                "announcementId", announcement.getId(),
                "requireConfirmation", announcement.requiresConfirmation(),
                "total", users.size(),
                "users", users
        );
    }

    private Map<String, Object> toMap(Announcement announcement) {
        return Map.of(
                "id", announcement.getId(),
                "title", announcement.getTitle(),
                "content", announcement.getContent(),
                "requireConfirmation", announcement.requiresConfirmation(),
                "createdAt", announcement.getCreatedAt().toString()
        );
    }
}
