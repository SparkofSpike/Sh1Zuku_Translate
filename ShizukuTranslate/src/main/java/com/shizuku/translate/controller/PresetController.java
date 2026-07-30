package com.shizuku.translate.controller;

import com.shizuku.translate.config.AppConfig;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class PresetController {

    private final AppConfig.AppProperties appProperties;

    public PresetController(AppConfig.AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    @GetMapping("/presets")
    public List<String> getPresets() {
        return appProperties.getPresetNames();
    }
}
