package com.shizuku.translate.controller;

import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.dto.LanguageOption;
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

    /**
     * Target languages a client may request. Served from configuration so the UI never hardcodes
     * a language list that could drift from {@code app.translation.target-languages}.
     */
    @GetMapping("/translation/languages")
    public List<LanguageOption> getTargetLanguages() {
        return appProperties.getTargetLanguages().stream()
                .map(item -> new LanguageOption(item.getCode(), item.getLabel()))
                .toList();
    }
}
