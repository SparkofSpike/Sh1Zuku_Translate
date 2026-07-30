package com.shizuku.translate.controller;

import com.shizuku.translate.dto.SurveyRequest;
import com.shizuku.translate.service.SurveyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class SurveyController {

    private final SurveyService surveyService;

    public SurveyController(SurveyService surveyService) {
        this.surveyService = surveyService;
    }

    @PostMapping("/survey")
    public ResponseEntity<?> submit(@Valid @RequestBody SurveyRequest request, Principal principal) {
        surveyService.submit(principal.getName(), request);
        return ResponseEntity.ok(Map.of("message", "提交成功，感谢您的反馈！"));
    }
}
