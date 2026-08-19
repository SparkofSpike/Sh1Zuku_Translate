package com.shizuku.translate.repository;

import com.shizuku.translate.entity.TranslationRecord;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TranslationRecordRepository extends JpaRepository<TranslationRecord, Long> {
    Page<TranslationRecord> findByUserIdOrderByCreatedAtDesc(Long userId, Pageable pageable);
    Optional<TranslationRecord> findByIdAndUserId(Long id, Long userId);

    @Modifying
    @Query("update TranslationRecord r set r.customPrompt = null "
            + "where r.customPrompt like '请将以下%小说内容翻译为%'")
    int clearKnownPluginPrompts();
}
