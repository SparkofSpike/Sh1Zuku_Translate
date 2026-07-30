package com.shizuku.translate.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public class SurveyRequest {
    @NotNull @Min(1) @Max(5)
    private Integer translationQuality;
    @NotNull @Min(1) @Max(5)
    private Integer experienceQuality;
    private String favoriteFeature;
    private String suggestion;

    public Integer getTranslationQuality() { return translationQuality; }
    public void setTranslationQuality(Integer translationQuality) { this.translationQuality = translationQuality; }
    public Integer getExperienceQuality() { return experienceQuality; }
    public void setExperienceQuality(Integer experienceQuality) { this.experienceQuality = experienceQuality; }
    public String getFavoriteFeature() { return favoriteFeature; }
    public void setFavoriteFeature(String favoriteFeature) { this.favoriteFeature = favoriteFeature; }
    public String getSuggestion() { return suggestion; }
    public void setSuggestion(String suggestion) { this.suggestion = suggestion; }
}
