package com.shizuku.translate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AnnouncementRequest {
    @NotBlank(message = "公告标题不能为空")
    @Size(max = 100, message = "公告标题不能超过100个字符")
    private String title;

    @NotBlank(message = "公告内容不能为空")
    private String content;

    /** Whether users must confirm this announcement once when they visit the site. */
    private boolean requireConfirmation;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public boolean isRequireConfirmation() { return requireConfirmation; }
    public void setRequireConfirmation(boolean requireConfirmation) { this.requireConfirmation = requireConfirmation; }
}
