package com.shizuku.translate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public class TranslateRequest {
    @NotBlank @Size(max = 16777215)
    private String sourceText;
    @NotBlank
    private String model;
    private String customPrompt;
    private List<String> presets;
    /** DeepSeek v4 thinking mode: "enabled" | "disabled" (null = server default) */
    private String thinkingType;

    public String getSourceText() { return sourceText; }
    public void setSourceText(String sourceText) { this.sourceText = sourceText; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getCustomPrompt() { return customPrompt; }
    public void setCustomPrompt(String customPrompt) { this.customPrompt = customPrompt; }
    public List<String> getPresets() { return presets; }
    public void setPresets(List<String> presets) { this.presets = presets; }
    public String getThinkingType() { return thinkingType; }
    public void setThinkingType(String thinkingType) { this.thinkingType = thinkingType; }

}
