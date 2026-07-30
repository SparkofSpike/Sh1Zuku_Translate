package com.shizuku.translate.service;

import com.shizuku.translate.dto.SurveyRequest;
import com.shizuku.translate.entity.SurveyRecord;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.repository.SurveyRecordRepository;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
public class SurveyService {

    private final SurveyRecordRepository surveyRepository;
    private final UserService userService;

    public SurveyService(SurveyRecordRepository surveyRepository, UserService userService) {
        this.surveyRepository = surveyRepository;
        this.userService = userService;
    }

    public void submit(String username, SurveyRequest request) {
        User user = userService.findByUsername(username);
        SurveyRecord record = new SurveyRecord();
        record.setUser(user);
        record.setTranslationQuality(request.getTranslationQuality());
        record.setExperienceQuality(request.getExperienceQuality());
        record.setFavoriteFeature(request.getFavoriteFeature());
        record.setSuggestion(request.getSuggestion());
        surveyRepository.save(record);
    }

    public Map<String, Object> getStatistics() {
        List<SurveyRecord> all = surveyRepository.findAllByOrderByCreatedAtDesc();
        Double avgTQ = surveyRepository.getAvgTranslationQuality();
        Double avgEQ = surveyRepository.getAvgExperienceQuality();

        return Map.of(
                "total", all.size(),
                "avgTranslationQuality", avgTQ != null ? Math.round(avgTQ * 10) / 10.0 : 0,
                "avgExperienceQuality", avgEQ != null ? Math.round(avgEQ * 10) / 10.0 : 0,
                "records", all.stream().map(r -> Map.of(
                        "id", r.getId(),
                        "username", r.getUser().getUsername(),
                        "translationQuality", r.getTranslationQuality(),
                        "experienceQuality", r.getExperienceQuality(),
                        "favoriteFeature", r.getFavoriteFeature() != null ? r.getFavoriteFeature() : "",
                        "suggestion", r.getSuggestion() != null ? r.getSuggestion() : "",
                        "createdAt", r.getCreatedAt().toString()
                )).toList()
        );
    }
}
