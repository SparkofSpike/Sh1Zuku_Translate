package com.shizuku.translate.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.dto.SseDoneEvent;
import com.shizuku.translate.dto.SseErrorEvent;
import com.shizuku.translate.dto.SseTokenEvent;
import com.shizuku.translate.dto.TranslateRequest;
import com.shizuku.translate.dto.TranslateResponse;
import com.shizuku.translate.service.TranslationService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.security.Principal;

@RestController
@RequestMapping("/api/v1")
public class TranslateController {

    private static final Logger log = LoggerFactory.getLogger(TranslateController.class);

    private final TranslationService translationService;
    private final ObjectMapper objectMapper;

    public TranslateController(TranslationService translationService, ObjectMapper objectMapper) {
        this.translationService = translationService;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/translate")
    public ResponseEntity<TranslateResponse> translate(@Valid @RequestBody TranslateRequest request,
                                                       Principal principal) {
        TranslateResponse response = translationService.translate(principal.getName(), request);
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/translate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter translateStream(@Valid @RequestBody TranslateRequest request, Principal principal) {
        String username = principal.getName();
        SseEmitter emitter = new SseEmitter(300000L);

        new Thread(() -> {
            try {
                translationService.translateStream(username, request,
                        token -> {
                            try {
                                String json = objectMapper.writeValueAsString(new SseTokenEvent(token));
                                emitter.send(SseEmitter.event().data(json));
                            } catch (IOException e) {
                                throw new RuntimeException("Client disconnected", e);
                            }
                        },
                        response -> {
                            try {
                                String json = objectMapper.writeValueAsString(
                                        new SseDoneEvent(response.getId(), response.getTranslatedText(), response.getTokenUsage()));
                                emitter.send(SseEmitter.event().data(json));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            try {
                                String json = objectMapper.writeValueAsString(new SseErrorEvent(error));
                                emitter.send(SseEmitter.event().data(json));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        }
                );
            } catch (Exception e) {
                log.error("Streaming translation failed", e);
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }
}
