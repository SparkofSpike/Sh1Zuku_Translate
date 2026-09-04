package com.shizuku.translate.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.dto.SseDoneEvent;
import com.shizuku.translate.dto.SseErrorEvent;
import com.shizuku.translate.dto.SseTokenEvent;
import com.shizuku.translate.dto.SseStatusEvent;
import com.shizuku.translate.dto.TranslateRequest;
import com.shizuku.translate.dto.TranslateResponse;
import com.shizuku.translate.service.TranslationService;
import com.shizuku.translate.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Qualifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.security.Principal;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/v1")
public class TranslateController {

    private static final Logger log = LoggerFactory.getLogger(TranslateController.class);

    private final TranslationService translationService;
    private final ObjectMapper objectMapper;
    private final UserService userService;
    private final org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor translationStreamExecutor;

    public TranslateController(TranslationService translationService,
                               ObjectMapper objectMapper,
                               UserService userService,
                               @Qualifier("translationStreamExecutor") org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor translationStreamExecutor) {
        this.translationService = translationService;
        this.objectMapper = objectMapper;
        this.userService = userService;
        this.translationStreamExecutor = translationStreamExecutor;
    }

    @PostMapping("/translate/image")
    public ResponseEntity<TranslateResponse> translateImage(@RequestPart("image") MultipartFile image,
                                                              @RequestPart("request") @Valid TranslateRequest request,
                                                              Principal principal, HttpServletRequest httpRequest) throws IOException {
        userService.requireEmailVerified(principal.getName());
        return ResponseEntity.ok(translationService.translateImage(principal.getName(), request, image, isPluginRequest(httpRequest)));
    }

    @PostMapping("/translate")
    public ResponseEntity<TranslateResponse> translate(@Valid @RequestBody TranslateRequest request,
                                                       Principal principal,
                                                       HttpServletRequest httpRequest) {
        userService.requireEmailVerified(principal.getName());
        TranslateResponse response = translationService.translate(
                principal.getName(), request, isPluginRequest(httpRequest));
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/translate/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter translateStream(@Valid @RequestBody TranslateRequest request,
                                      Principal principal,
                                      HttpServletRequest httpRequest) {
        String username = principal.getName();
        boolean pluginRequest = isPluginRequest(httpRequest);
        // Paid-feature gate: must run before the emitter is created so the
        // failure surfaces as a normal HTTP 403 response instead of a stream.
        userService.requireEmailVerified(username);
        // Long model pre-fill plus generation can exceed five minutes. Keep
        // the SSE request alive long enough for the upstream inactivity
        // timeout; the client can still cancel it at any time.
        SseEmitter emitter = new SseEmitter(1800000L);
        AtomicBoolean closed = new AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<Future<?>> taskRef = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable cancelTask = () -> {
            closed.set(true);
            Future<?> task = taskRef.get();
            if (task != null) {
                task.cancel(true);
            }
        };
        emitter.onCompletion(cancelTask);
        emitter.onTimeout(() -> {
            if (closed.compareAndSet(false, true)) {
                Future<?> task = taskRef.get();
                if (task != null) {
                    task.cancel(true);
                }
                // A timeout callback may race with container completion. Send
                // the terminal event before completing, and treat a failed
                // send as a disconnect rather than hiding the timeout.
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(writeJson(new SseErrorEvent("Translation timed out"))));
                } catch (IOException | IllegalStateException e) {
                    log.debug("Unable to send timeout event because the client disconnected", e);
                } finally {
                    emitter.complete();
                }
            }
        });
        emitter.onError(error -> cancelTask.run());

        // Flush the response headers immediately so the client sees a
        // connected stream even before the first token arrives. Without
        // this, the browser's fetch() stays pending while DeepSeek is
        // still pre-filling long texts (which can take minutes), making
        // the translation look "stuck".
        if (!sendEvent(emitter, closed, SseEmitter.event().comment("connected"))) {
            IOException e = new IOException("Client disconnected before stream started");
            log.warn("Client disconnected before stream started", e);
            emitter.completeWithError(e);
            return emitter;
        }

        try {
            Future<?> task = translationStreamExecutor.submit(() -> {
                try {
                translationService.translateStream(username, request, pluginRequest,
                        token -> {
                            String json = writeJson(new SseTokenEvent(token));
                            sendOrDisconnect(emitter, closed, SseEmitter.event().data(json));
                        },
                        response -> {
                            if (!closed.get()) {
                                String json = writeJson(new SseDoneEvent(response.getId(), response.getTranslatedText(), response.getTokenUsage()));
                                if (sendEvent(emitter, closed, SseEmitter.event().data(json))) {
                                    emitter.complete();
                                }
                            }
                        },
                        error -> {
                            if (!closed.get()) {
                                String json = writeJson(new SseErrorEvent(error));
                                if (sendEvent(emitter, closed, SseEmitter.event().data(json))) {
                                    emitter.complete();
                                }
                            }
                        },
                        () -> {
                            String json = writeJson(new SseStatusEvent("ai-connected"));
                            sendOrDisconnect(emitter, closed, SseEmitter.event().data(json));
                        },
                        closed::get
                );
            } catch (ClientDisconnectedException e) {
                log.info("Streaming translation cancelled by client");
                closed.set(true);
            } catch (Exception e) {
                if (!closed.get()) {
                    log.error("Streaming translation failed", e);
                    emitter.completeWithError(e);
                }
                }
            });
            taskRef.set(task);
        } catch (RejectedExecutionException e) {
            log.warn("Streaming executor rejected request", e);
            closed.set(true);
            emitter.completeWithError(new IllegalStateException("Too many active translation streams"));
        }

        return emitter;
    }

    private String writeJson(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize SSE event", e);
        }
    }

    private void sendOrDisconnect(SseEmitter emitter, AtomicBoolean closed, SseEmitter.SseEventBuilder event) {
        if (!sendEvent(emitter, closed, event)) {
            throw new ClientDisconnectedException();
        }
    }

    private boolean sendEvent(SseEmitter emitter, AtomicBoolean closed, SseEmitter.SseEventBuilder event) {
        if (closed.get()) {
            return false;
        }
        try {
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            closed.set(true);
            return false;
        }
    }

    private static class ClientDisconnectedException extends RuntimeException {}

    private boolean isPluginRequest(HttpServletRequest request) {
        return StringUtils.hasText(request.getHeader("X-API-Key"))
                || StringUtils.hasText(request.getParameter("api_key"));
    }
}
