package com.shizuku.translate.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class PluginLogRequest {

    @Size(max = 50)
    private String version;

    @NotBlank(message = "错误信息不能为空")
    @Size(max = 4000, message = "错误信息过长")
    private String errorMessage;

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}