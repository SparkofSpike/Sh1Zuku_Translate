package com.shizuku.translate.controller;

import com.shizuku.translate.dto.TranslateRequest;
import com.shizuku.translate.dto.TranslateResponse;
import com.shizuku.translate.service.TranslationService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.security.Principal;

@RestController
@RequestMapping("/api/v1")
public class TranslateController {

    private final TranslationService translationService;

    public TranslateController(TranslationService translationService) {
        this.translationService = translationService;
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
                                emitter.send(SseEmitter.event().data("{\"token\":\"" + escapeJson(token) + "\"}"));
                            } catch (IOException e) {
                                throw new RuntimeException("Client disconnected", e);
                            }
                        },
                        response -> {
                            try {
                                String json = "{\"done\":true,\"id\":" + response.getId()
                                        + ",\"translatedText\":\"" + escapeJson(response.getTranslatedText()) + "\"";
                                if (response.getTokenUsage() != null) {
                                    json += ",\"tokenUsage\":{\"promptTokens\":" + response.getTokenUsage().getPromptTokens()
                                            + ",\"completionTokens\":" + response.getTokenUsage().getCompletionTokens()
                                            + ",\"totalTokens\":" + response.getTokenUsage().getTotalTokens() + "}";
                                }
                                json += "}";
                                emitter.send(SseEmitter.event().data(json));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            try {
                                emitter.send(SseEmitter.event().data("{\"error\":\"" + escapeJson(error) + "\"}"));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        }
                );
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }).start();

        return emitter;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
