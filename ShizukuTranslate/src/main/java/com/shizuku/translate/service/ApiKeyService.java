package com.shizuku.translate.service;

import com.shizuku.translate.entity.ApiKey;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.repository.ApiKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Service
public class ApiKeyService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ApiKeyRepository apiKeyRepository;
    private final UserService userService;

    public ApiKeyService(ApiKeyRepository apiKeyRepository, UserService userService) {
        this.apiKeyRepository = apiKeyRepository;
        this.userService = userService;
    }

    @Transactional
    public CreatedApiKey createApiKey(String username, String name) {
        User user = userService.findByUsername(username);
        String rawKey = generateApiKey();

        ApiKey apiKey = ApiKey.builder()
                .keyValue(maskPrefix(rawKey))
                .keyHash(hashApiKey(rawKey))
                .name(name != null && !name.isBlank() ? name : "unnamed")
                .user(user)
                .createdAt(LocalDateTime.now())
                .active(true)
                .build();

        return new CreatedApiKey(apiKeyRepository.save(apiKey), rawKey);
    }

    public Optional<ApiKey> authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        String trimmed = rawKey.trim();
        Optional<ApiKey> hashed = apiKeyRepository.findByKeyHashAndActiveTrue(hashApiKey(trimmed));
        if (hashed.isPresent()) {
            return hashed;
        }
        return apiKeyRepository.findByKeyValueAndActiveTrue(trimmed);
    }

    public List<ApiKey> listApiKeys(String username) {
        User user = userService.findByUsername(username);
        return apiKeyRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    @Transactional
    public void deleteApiKey(String username, Long keyId) {
        User user = userService.findByUsername(username);
        ApiKey apiKey = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new RuntimeException("API key not found"));
        if (!apiKey.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("API key does not belong to user");
        }
        apiKeyRepository.delete(apiKey);
    }

    @Transactional
    public void deactivateApiKey(String username, Long keyId) {
        User user = userService.findByUsername(username);
        ApiKey apiKey = apiKeyRepository.findById(keyId)
                .orElseThrow(() -> new RuntimeException("API key not found"));
        if (!apiKey.getUser().getId().equals(user.getId())) {
            throw new RuntimeException("API key does not belong to user");
        }
        apiKey.setActive(false);
        apiKeyRepository.save(apiKey);
    }

    public static String hashApiKey(String rawKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawKey.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String maskPrefix(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return "";
        }
        if (rawKey.endsWith("...")) {
            return rawKey;
        }
        int length = Math.min(12, rawKey.length());
        return rawKey.substring(0, length) + "...";
    }

    private static String generateApiKey() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return "sk-st-" + HexFormat.of().formatHex(bytes);
    }

    public record CreatedApiKey(ApiKey entity, String rawKey) {}
}
