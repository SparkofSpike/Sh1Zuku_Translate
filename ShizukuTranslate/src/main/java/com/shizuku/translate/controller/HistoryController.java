package com.shizuku.translate.controller;

import com.shizuku.translate.dto.HistoryResponse;
import com.shizuku.translate.service.TranslationService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@RestController
@RequestMapping("/api/v1/translations")
public class HistoryController {

    private final TranslationService translationService;

    public HistoryController(TranslationService translationService) {
        this.translationService = translationService;
    }

    @GetMapping
    public ResponseEntity<Page<HistoryResponse>> getHistory(Principal principal, Pageable pageable) {
        return ResponseEntity.ok(translationService.getHistory(principal.getName(), pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<HistoryResponse> getDetail(@PathVariable Long id, Principal principal) {
        return ResponseEntity.ok(translationService.getDetail(id, principal.getName()));
    }
}
