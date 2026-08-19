package com.shizuku.translate.repository;

import com.shizuku.translate.entity.TokenUsageLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TokenUsageLogRepository extends JpaRepository<TokenUsageLog, Long> {
    List<TokenUsageLog> findByUserIdOrderByCreatedAtDesc(Long userId);
}
