package com.shizuku.translate.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

public class TranslateRequest {
    /** Optional for image translation; regular translation paths validate it explicitly. */
    @Size(max = 16777215)
    private String sourceText;
    /** Optional: when omitted, use the model configured in the user's profile. */
    private String model;
    /** Optional saved user model profile; when present it is authoritative. */
    private Long modelProfileId;
    private String customPrompt;
    private List<String> presets;
    /** DeepSeek v4 thinking mode: "enabled" | "disabled" (null = server default) */
    private String thinkingType;
    /** Skip reading an existing translation cache entry, but still write the new result. */
    private boolean skipCache;

    public String getSourceText() { return sourceText; }
    public void setSourceText(String sourceText) { this.sourceText = sourceText; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Long getModelProfileId() { return modelProfileId; }
    public void setModelProfileId(Long modelProfileId) { this.modelProfileId = modelProfileId; }
    public String getCustomPrompt() { return customPrompt; }
    public void setCustomPrompt(String customPrompt) { this.customPrompt = customPrompt; }
    public List<String> getPresets() { return presets; }
    public void setPresets(List<String> presets) { this.presets = presets; }
    public String getThinkingType() { return thinkingType; }
    public void setThinkingType(String thinkingType) { this.thinkingType = thinkingType; }
    public boolean isSkipCache() { return skipCache; }
    public void setSkipCache(boolean skipCache) { this.skipCache = skipCache; }

}
