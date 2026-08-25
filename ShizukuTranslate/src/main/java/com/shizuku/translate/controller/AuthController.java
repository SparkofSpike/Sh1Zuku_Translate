package com.shizuku.translate.controller;

import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.dto.LoginRequest;
import com.shizuku.translate.dto.RegisterRequest;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.service.ApiKeyService;
import com.shizuku.translate.service.UserService;
import com.shizuku.translate.service.UsageService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;
    private final AppConfig.AppProperties appProperties;
    private final ApiKeyService apiKeyService;
    private final UsageService usageService;

    public AuthController(UserService userService,
                          AppConfig.AppProperties appProperties,
                          ApiKeyService apiKeyService,
                          UsageService usageService) {
        this.userService = userService;
        this.appProperties = appProperties;
        this.apiKeyService = apiKeyService;
        this.usageService = usageService;
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

    /** User profile (requires JWT login) */
    @GetMapping("/profile")
    public ResponseEntity<?> profile(Principal principal, HttpServletRequest request) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        User user = userService.findByUsername(principal.getName());
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("username", user.getUsername());
        data.put("email", user.getEmail());
        data.put("hasAiApiKey", user.getAiApiKey() != null && !user.getAiApiKey().isBlank());
        boolean pluginRequest = StringUtils.hasText(request.getHeader("X-API-Key"))
                || StringUtils.hasText(request.getParameter("api_key"));
        data.put("apiKeyPreview", pluginRequest ? "" : userService.getAiApiKeyPreview(principal.getName()));
        data.put("provider", user.getAiProvider() == null ? "deepseek" : user.getAiProvider());
        data.put("baseUrl", user.getAiBaseUrl() == null ? "" : user.getAiBaseUrl());
        data.put("model", user.getAiModel() == null ? "" : user.getAiModel());
        data.put("createdAt", user.getCreatedAt() != null ? user.getCreatedAt().toString() : null);
        return ResponseEntity.ok(data);
    }

    @GetMapping("/model-profiles")
    public ResponseEntity<?> modelProfiles(Principal principal, HttpServletRequest request) {
        boolean pluginRequest = StringUtils.hasText(request.getHeader("X-API-Key"))
                || StringUtils.hasText(request.getParameter("api_key"));
        return ResponseEntity.ok(userService.listModelProfiles(principal.getName()).stream()
                .map(profile -> modelProfileResponse(profile, pluginRequest))
                .toList());
    }

    @PostMapping("/model-profiles")
    public ResponseEntity<?> createModelProfile(Principal principal, @RequestBody Map<String, String> body) {
        var profile = userService.createModelProfile(principal.getName(), body.get("name"), body.get("provider"),
                body.get("baseUrl"), body.get("model"), body.get("apiKey"));
        return ResponseEntity.ok(modelProfileResponse(profile, false));
    }

    @PutMapping("/model-profiles/{id}")
    public ResponseEntity<?> updateModelProfile(@PathVariable Long id, Principal principal,
                                                 @RequestBody Map<String, String> body) {
        boolean clearApiKey = "true".equalsIgnoreCase(body.get("clearApiKey"));
        var profile = userService.updateModelProfile(principal.getName(), id, body.get("name"), body.get("provider"),
                body.get("baseUrl"), body.get("model"), body.get("apiKey"), clearApiKey);
        return ResponseEntity.ok(modelProfileResponse(profile, false));
    }

    @DeleteMapping("/model-profiles/{id}")
    public ResponseEntity<?> deleteModelProfile(@PathVariable Long id, Principal principal) {
        userService.deleteModelProfile(principal.getName(), id);
        return ResponseEntity.ok(Map.of("message", "模型配置已删除"));
    }

    private Map<String, Object> modelProfileResponse(com.shizuku.translate.entity.AiModelProfile profile,
                                                      boolean pluginRequest) {
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("id", profile.getId());
        data.put("name", profile.getName());
        data.put("provider", profile.getProvider());
        data.put("baseUrl", profile.getBaseUrl() == null ? "" : profile.getBaseUrl());
        data.put("model", profile.getModel());
        data.put("hasApiKey", profile.getApiKey() != null && !profile.getApiKey().isBlank());
        data.put("apiKeyPreview", pluginRequest ? "" : UserService.maskApiKey(profile.getApiKey()));
        return data;
    }

    /** Save the legacy single model configuration for old clients. */
    @PutMapping("/profile/model")
    public ResponseEntity<?> updateModelConfig(Principal principal, @RequestBody Map<String, String> body) {
        userService.updateAiModelConfig(principal.getName(), body.get("provider"), body.get("baseUrl"), body.get("model"), body.get("apiKey"));
        return ResponseEntity.ok(Map.of("message", "模型配置已保存"));
    }

    /** Set / clear the user's own model API key (empty string clears it). */
    @PutMapping("/profile/ai-key")
    public ResponseEntity<?> updateAiApiKey(Principal principal, @RequestBody Map<String, String> body) {
        userService.updateAiApiKey(principal.getName(), body.get("aiApiKey"));
        return ResponseEntity.ok(Map.of("message", "AI API key updated"));
    }

    /** Current user's token usage summary. */
    @GetMapping("/usage")
    public ResponseEntity<?> usage(Principal principal) {
        return ResponseEntity.ok(usageService.getUserUsage(principal.getName()));
    }

    // ──────────────────────────────────────────────
    //  API Key management (requires JWT login)
    // ──────────────────────────────────────────────

    /** Generate a new API key */
    @PostMapping("/api-key")
    public ResponseEntity<?> createApiKey(Principal principal, @RequestBody Map<String, String> body) {
        String username = principal.getName();
        String name = body.getOrDefault("name", "unnamed");
        var created = apiKeyService.createApiKey(username, name);
        var key = created.entity();
        var resp = new java.util.LinkedHashMap<String, Object>();
        resp.put("id", key.getId());
        resp.put("keyValue", created.rawKey());
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
                    m.put("keyPrefix", k.getKeyPrefix());
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
