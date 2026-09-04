package com.shizuku.translate.repository;

import com.shizuku.translate.entity.AnnouncementAcknowledgement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnnouncementAcknowledgementRepository
        extends JpaRepository<AnnouncementAcknowledgement, Long> {

    Optional<AnnouncementAcknowledgement> findByAnnouncementIdAndUserId(Long announcementId, Long userId);

    List<AnnouncementAcknowledgement> findByAnnouncementIdOrderByAcknowledgedAtDesc(Long announcementId);

    long countByAnnouncementId(Long announcementId);

    void deleteByAnnouncementId(Long announcementId);
}
