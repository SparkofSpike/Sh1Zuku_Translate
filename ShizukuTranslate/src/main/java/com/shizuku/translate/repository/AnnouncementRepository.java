package com.shizuku.translate.repository;

import com.shizuku.translate.entity.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {
    List<Announcement> findAllByOrderByCreatedAtDesc();

    /** Announcements that require confirmation but have not yet been confirmed by the given user. */
    @Query("select a from Announcement a " +
            "where a.requireConfirmation = true and not exists (" +
            "  select 1 from AnnouncementAcknowledgement k " +
            "  where k.announcement = a and k.user.id = :userId) " +
            "order by a.createdAt desc")
    List<Announcement> findPendingForUser(@Param("userId") Long userId);
}
