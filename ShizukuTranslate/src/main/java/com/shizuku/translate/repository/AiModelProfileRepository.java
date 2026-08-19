package com.shizuku.translate.repository;

import com.shizuku.translate.entity.AiModelProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiModelProfileRepository extends JpaRepository<AiModelProfile, Long> {
    List<AiModelProfile> findByUserIdOrderByCreatedAtAsc(Long userId);
    Optional<AiModelProfile> findByIdAndUserId(Long id, Long userId);
}
