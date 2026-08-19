package com.shizuku.translate.service;

import com.shizuku.translate.dto.AnnouncementRequest;
import com.shizuku.translate.entity.Announcement;
import com.shizuku.translate.repository.AnnouncementRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class AnnouncementService {

    private final AnnouncementRepository announcementRepository;

    public AnnouncementService(AnnouncementRepository announcementRepository) {
        this.announcementRepository = announcementRepository;
    }

    public List<Map<String, Object>> list() {
        return announcementRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toMap)
                .toList();
    }

    public Map<String, Object> create(AnnouncementRequest request) {
        Announcement announcement = Announcement.builder()
                .title(request.getTitle().trim())
                .content(request.getContent().trim())
                .build();
        return toMap(announcementRepository.save(announcement));
    }

    public void delete(Long id) {
        announcementRepository.deleteById(id);
    }

    private Map<String, Object> toMap(Announcement announcement) {
        return Map.of(
                "id", announcement.getId(),
                "title", announcement.getTitle(),
                "content", announcement.getContent(),
                "createdAt", announcement.getCreatedAt().toString()
        );
    }
}
