package com.shizuku.translate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Admin request body for creating or updating a translation preset. */
public class PresetRequest {
    @NotBlank(message = "预设名称不能为空")
    @Size(max = 100, message = "预设名称不能超过100个字符")
    private String name;

    @NotBlank(message = "预设内容不能为空")
    private String prompt;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
}
