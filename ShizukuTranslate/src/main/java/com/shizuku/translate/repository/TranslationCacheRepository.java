package com.shizuku.translate.repository;

import com.shizuku.translate.entity.TranslationCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface TranslationCacheRepository extends JpaRepository<TranslationCache, Long> {

    java.util.List<TranslationCache> findByUserIdAndCacheKeyOrderByCreatedAtDesc(Long userId, String cacheKey);

    int deleteByCreatedAtBefore(LocalDateTime before);
}
