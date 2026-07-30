package com.shizuku.translate.controller;

import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.dto.LoginRequest;
import com.shizuku.translate.dto.RegisterRequest;
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

    public AuthController(UserService userService, AppConfig.AppProperties appProperties) {
        this.userService = userService;
        this.appProperties = appProperties;
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

}
