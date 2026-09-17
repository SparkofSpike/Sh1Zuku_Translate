package com.shizuku.translate.controller;

import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.dto.LanguageOption;
import com.shizuku.translate.entity.Preset;
import com.shizuku.translate.service.PresetService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class PresetController {

    private final PresetService presetService;
    private final AppConfig.AppProperties appProperties;

    public PresetController(PresetService presetService,
                            AppConfig.AppProperties appProperties) {
        this.presetService = presetService;
        this.appProperties = appProperties;
    }

    /** Preset names selectable in translate requests, now served from the database. */
    @GetMapping("/presets")
    public List<String> getPresets() {
        return presetService.listNames();
    }

    /** Admin-only full preset list including prompts. */
    @GetMapping("/admin/presets")
    public List<Preset> adminList() {
        AdminController.checkAdmin(appProperties, null);
        return presetService.listAll();
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
