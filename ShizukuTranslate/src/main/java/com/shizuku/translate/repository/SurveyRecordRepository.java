package com.shizuku.translate.repository;

import com.shizuku.translate.entity.SurveyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;

public interface SurveyRecordRepository extends JpaRepository<SurveyRecord, Long> {
    List<SurveyRecord> findAllByOrderByCreatedAtDesc();

    @Query("SELECT AVG(s.translationQuality) FROM SurveyRecord s")
    Double getAvgTranslationQuality();

    @Query("SELECT AVG(s.experienceQuality) FROM SurveyRecord s")
    Double getAvgExperienceQuality();
}
