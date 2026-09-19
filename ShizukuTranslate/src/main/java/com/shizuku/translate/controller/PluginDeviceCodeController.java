package com.shizuku.translate.controller;

import com.shizuku.translate.exception.EmailNotVerifiedException;
import com.shizuku.translate.service.PluginDeviceCodeService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Device-code endpoints the browser extension uses to obtain an API key on its own.
 *
 * <p>Every branch answers explicitly with a status body. Nothing here is allowed to 404:
 * unmatched paths fall through to the SPA handler in {@code WebMvcConfig} and come back as
 * index.html with HTTP 200, so a 404 would be indistinguishable from a wrong answer.
 */
@RestController
@RequestMapping("/api/v1/plugin")
public class PluginDeviceCodeController {

    private final PluginDeviceCodeService deviceCodeService;

    public PluginDeviceCodeController(PluginDeviceCodeService deviceCodeService) {
        this.deviceCodeService = deviceCodeService;
    }

    /** Step 1 — anonymous: ask for a code to show the user. */
    @PostMapping("/device-code")
    public ResponseEntity<?> requestDeviceCode() {
        PluginDeviceCodeService.DeviceCodeIssued issued = deviceCodeService.issue();
        return ResponseEntity.ok(Map.of(
                "code", issued.code(),
                "expiresIn", issued.expiresIn(),
                "interval", issued.interval(),
                "verificationPath", issued.verificationPath()));
    }

    /** Step 2 — anonymous: poll until the status is no longer PENDING. */
    @GetMapping("/device-code/{code}")
    public ResponseEntity<?> pollDeviceCode(@PathVariable String code) {
        PluginDeviceCodeService.PollResult result = deviceCodeService.poll(code);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", result.status().name());
        if (result.status() == PluginDeviceCodeService.PollStatus.APPROVED) {
            body.put("keyValue", result.keyValue());
            body.put("keyName", result.keyName());
        }
        return ResponseEntity.ok(body);
    }

    /** Step 3 — requires JWT: the logged-in user approves the code shown in the plugin. */
    @PostMapping("/device-code/approve")
    public ResponseEntity<?> approveDeviceCode(Principal principal,
                                               @RequestBody(required = false) Map<String, String> body) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "unauthorized"));
        }
        String code = body == null ? null : body.get("code");

        PluginDeviceCodeService.ApproveResult result;
        try {
            result = deviceCodeService.approve(principal.getName(), code);
        } catch (EmailNotVerifiedException ex) {
            // Body is part of the frozen contract, so it wins over the generic handler's message.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "email_not_verified"));
        }

        return switch (result.status()) {
            case APPROVED -> ResponseEntity.ok(Map.of(
                    "status", "APPROVED",
                    "keyName", result.keyName()));
            case INVALID_OR_EXPIRED -> ResponseEntity.badRequest().body(Map.of(
                    "error", "invalid_or_expired_code"));
            case ALREADY_APPROVED -> ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "code_already_approved"));
        };
    }
}
