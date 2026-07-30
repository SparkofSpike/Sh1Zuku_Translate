package com.shizuku.translate.controller;

import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.service.SurveyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final SurveyService surveyService;
    private final AppConfig.AppProperties appProperties;

    public AdminController(SurveyService surveyService, AppConfig.AppProperties appProperties) {
        this.surveyService = surveyService;
        this.appProperties = appProperties;
    }

    private void checkAdmin(Principal principal) {
        if (principal == null || !appProperties.isAdmin(principal.getName())) {
            throw new RuntimeException("无管理员权限");
        }
    }

    @GetMapping("/surveys")
    public ResponseEntity<?> getSurveys(Principal principal) {
        checkAdmin(principal);
        return ResponseEntity.ok(surveyService.getStatistics());
    }
}
