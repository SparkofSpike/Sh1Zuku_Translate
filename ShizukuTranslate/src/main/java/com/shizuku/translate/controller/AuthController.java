package com.shizuku.translate.controller;

import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.dto.LoginRequest;
import com.shizuku.translate.dto.RegisterRequest;
import com.shizuku.translate.service.ApiKeyService;
import com.shizuku.translate.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;
    private final AppConfig.AppProperties appProperties;
    private final ApiKeyService apiKeyService;

    public AuthController(UserService userService,
                          AppConfig.AppProperties appProperties,
                          ApiKeyService apiKeyService) {
        this.userService = userService;
        this.appProperties = appProperties;
        this.apiKeyService = apiKeyService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterRequest request) {
        userService.register(request);
        return ResponseEntity.ok(Map.of("message", "Registration successful"));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest request) {
        String token = userService.login(request);
        return ResponseEntity.ok(Map.of("token", token));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Principal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        String username = principal.getName();
        boolean isAdmin = appProperties.isAdmin(username);
        return ResponseEntity.ok(Map.of("username", username, "isAdmin", isAdmin));
    }

    // ──────────────────────────────────────────────
    //  API Key management (requires JWT login)
    // ──────────────────────────────────────────────

    /** Generate a new API key */
    @PostMapping("/api-key")
    public ResponseEntity<?> createApiKey(Principal principal, @RequestBody Map<String, String> body) {
        String username = principal.getName();
        String name = body.getOrDefault("name", "unnamed");
        var key = apiKeyService.createApiKey(username, name);
        var resp = new java.util.LinkedHashMap<String, Object>();
        resp.put("id", key.getId());
        resp.put("keyValue", key.getKeyValue());
        resp.put("name", key.getName());
        resp.put("createdAt", key.getCreatedAt() != null ? key.getCreatedAt().toString() : null);
        resp.put("expiresAt", null);
        return ResponseEntity.ok(resp);
    }

    /** List all API keys for the current user */
    @GetMapping("/api-keys")
    public ResponseEntity<?> listApiKeys(Principal principal) {
        String username = principal.getName();
        var keys = apiKeyService.listApiKeys(username).stream()
                .map(k -> {
                    var m = new java.util.LinkedHashMap<String, Object>();
                    m.put("id", k.getId());
                    m.put("name", k.getName());
                    m.put("createdAt", k.getCreatedAt() != null ? k.getCreatedAt().toString() : null);
                    m.put("expiresAt", k.getExpiresAt() != null ? k.getExpiresAt().toString() : null);
                    m.put("active", k.isActive());
                    m.put("keyPrefix", k.getKeyValue().substring(0, Math.min(12, k.getKeyValue().length())) + "...");
                    return m;
                })
                .toList();
        return ResponseEntity.ok(Map.of("apiKeys", keys));
    }

    /** Delete an API key */
    @DeleteMapping("/api-key/{id}")
    public ResponseEntity<?> deleteApiKey(Principal principal, @PathVariable Long id) {
        apiKeyService.deleteApiKey(principal.getName(), id);
        return ResponseEntity.ok(Map.of("message", "API key deleted"));
    }
}
