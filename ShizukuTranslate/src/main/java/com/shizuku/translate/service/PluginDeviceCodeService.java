package com.shizuku.translate.service;

import com.shizuku.translate.entity.PluginDeviceCode;
import com.shizuku.translate.repository.PluginDeviceCodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

/**
 * Device-code flow behind {@code /api/v1/plugin/device-code}: an anonymous plugin asks for a
 * code, a logged-in user approves it in the browser, and the plugin polls until the freshly
 * created API key is handed over exactly once.
 *
 * <p>Expiry is lazy — every read compares {@code expiresAt} against now, so there is no
 * scheduled cleanup job to run (or to forget to run).
 */
@Service
public class PluginDeviceCodeService {

    /** Name given to the key created through this flow; the web UI shows the same name. */
    public static final String PLUGIN_KEY_NAME = "pixiv-plugin";

    /** Codes live for 10 minutes; the plugin is expected to poll every {@link #POLL_INTERVAL_SECONDS}s. */
    public static final long CODE_TTL_SECONDS = 600L;
    public static final int POLL_INTERVAL_SECONDS = 2;

    /** Where the user approves the code in the web UI. */
    public static final String VERIFICATION_PATH = "/plugin-link";

    /** Digits/letters a human can read back without mixing up 0/O or 1/I/L. */
    private static final char[] CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LENGTH = 8;
    private static final int CODE_GENERATION_ATTEMPTS = 5;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final PluginDeviceCodeRepository deviceCodeRepository;
    private final ApiKeyService apiKeyService;
    private final UserService userService;

    public PluginDeviceCodeService(PluginDeviceCodeRepository deviceCodeRepository,
                                   ApiKeyService apiKeyService,
                                   UserService userService) {
        this.deviceCodeRepository = deviceCodeRepository;
        this.apiKeyService = apiKeyService;
        this.userService = userService;
    }

    /** Issues a fresh code. Anonymous — the plugin has no credentials yet, that is the point. */
    @Transactional
    public DeviceCodeIssued issue() {
        LocalDateTime now = LocalDateTime.now();
        PluginDeviceCode deviceCode = PluginDeviceCode.builder()
                .code(generateUniqueCode())
                .approved(false)
                .consumed(false)
                .createdAt(now)
                .expiresAt(now.plusSeconds(CODE_TTL_SECONDS))
                .build();
        deviceCodeRepository.save(deviceCode);
        return new DeviceCodeIssued(deviceCode.getCode(), CODE_TTL_SECONDS, POLL_INTERVAL_SECONDS, VERIFICATION_PATH);
    }

    /**
     * Poll a code. A code that was never issued, one that has expired, and one that is still
     * pending all answer with HTTP 200 and a status — never 404, so the SPA fallback that
     * serves index.html for unmatched paths can never be mistaken for a real answer.
     */
    @Transactional
    public PollResult poll(String rawCode) {
        PluginDeviceCode deviceCode = findByCode(rawCode);
        if (deviceCode == null || isExpired(deviceCode)) {
            return new PollResult(PollStatus.EXPIRED, null, null);
        }
        if (deviceCode.isConsumed()) {
            return new PollResult(PollStatus.CONSUMED, null, null);
        }
        if (deviceCode.isApproved() && deviceCode.getPendingKey() != null) {
            String keyValue = deviceCode.getPendingKey();
            String keyName = deviceCode.getKeyName();
            // The plaintext column dies here: this response is the only place it ever appears.
            deviceCode.setPendingKey(null);
            deviceCode.setConsumed(true);
            deviceCodeRepository.save(deviceCode);
            return new PollResult(PollStatus.APPROVED, keyValue, keyName);
        }
        if (deviceCode.isApproved()) {
            return new PollResult(PollStatus.CONSUMED, null, null);
        }
        return new PollResult(PollStatus.PENDING, null, null);
    }

    /**
     * Approve a code for the logged-in user: creates the API key and parks its plaintext until
     * the plugin collects it.
     *
     * @throws com.shizuku.translate.exception.EmailNotVerifiedException when the account has not
     *         verified its email; the controller turns that into 403 {@code email_not_verified}.
     */
    @Transactional
    public ApproveResult approve(String username, String rawCode) {
        PluginDeviceCode deviceCode = findByCode(rawCode);
        if (deviceCode == null || isExpired(deviceCode)) {
            return new ApproveResult(ApproveStatus.INVALID_OR_EXPIRED, null);
        }
        if (deviceCode.isApproved() || deviceCode.isConsumed()) {
            return new ApproveResult(ApproveStatus.ALREADY_APPROVED, null);
        }

        userService.requireEmailVerified(username);

        ApiKeyService.CreatedApiKey created = apiKeyService.createApiKey(username, PLUGIN_KEY_NAME);
        deviceCode.setUserId(created.entity().getUser().getId());
        deviceCode.setApproved(true);
        deviceCode.setKeyName(PLUGIN_KEY_NAME);
        deviceCode.setPendingKey(created.rawKey());
        deviceCodeRepository.save(deviceCode);
        return new ApproveResult(ApproveStatus.APPROVED, PLUGIN_KEY_NAME);
    }

    private PluginDeviceCode findByCode(String rawCode) {
        String code = normalize(rawCode);
        return code == null ? null : deviceCodeRepository.findByCode(code).orElse(null);
    }

    private static boolean isExpired(PluginDeviceCode deviceCode) {
        return deviceCode.getExpiresAt() != null && deviceCode.getExpiresAt().isBefore(LocalDateTime.now());
    }

    /** Codes typed by a human may arrive lower-cased or padded; the stored form is canonical. */
    private static String normalize(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return null;
        }
        return rawCode.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private String generateUniqueCode() {
        for (int attempt = 0; attempt < CODE_GENERATION_ATTEMPTS; attempt++) {
            String code = randomCode();
            if (!deviceCodeRepository.existsByCode(code)) {
                return code;
            }
        }
        throw new IllegalStateException("Could not allocate a unique plugin device code");
    }

    private static String randomCode() {
        StringBuilder builder = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            builder.append(CODE_ALPHABET[SECURE_RANDOM.nextInt(CODE_ALPHABET.length)]);
        }
        return builder.toString();
    }

    public record DeviceCodeIssued(String code, long expiresIn, int interval, String verificationPath) {}

    public enum PollStatus { PENDING, APPROVED, CONSUMED, EXPIRED }

    public record PollResult(PollStatus status, String keyValue, String keyName) {}

    public enum ApproveStatus { APPROVED, INVALID_OR_EXPIRED, ALREADY_APPROVED }

    public record ApproveResult(ApproveStatus status, String keyName) {}
}
