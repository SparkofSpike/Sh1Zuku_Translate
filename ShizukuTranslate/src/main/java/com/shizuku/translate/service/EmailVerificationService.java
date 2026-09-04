package com.shizuku.translate.service;

import com.shizuku.translate.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory email verification codes with rate limiting.
 *
 * <p>Single-instance backend, so an in-memory map is sufficient: a code
 * survives only while the process stays up, which is fine for a 10-minute
 * TTL. Codes are stored hashed (SHA-256), are single-use, expire after
 * 10 minutes, and are invalidated after too many failed attempts.</p>
 *
 * <p>Anti-abuse limits protect both the SMTP account and third-party
 * mailboxes (address bombing via the anonymous registration endpoint):
 * a 60 s resend cooldown, a daily cap per address, a per-IP window cap,
 * and a global hourly cap.</p>
 */
@Service
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final int MAX_ATTEMPTS = 5;
    private static final int DAILY_MAX_PER_EMAIL = 10;
    private static final int IP_MAX_PER_WINDOW = 5;
    private static final Duration IP_WINDOW = Duration.ofMinutes(10);
    private static final int GLOBAL_MAX_PER_HOUR = 300;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final MailService mailService;

    /** key: normalized email -> active code entry */
    private final Map<String, CodeEntry> codes = new ConcurrentHashMap<>();
    /** normalized email -> codes sent today */
    private final Map<String, DailyCounter> dailyByEmail = new ConcurrentHashMap<>();
    /** ip address -> recent send timestamps */
    private final Map<String, Deque<Long>> sendsByIp = new ConcurrentHashMap<>();
    /** recent send timestamps (global hourly cap) */
    private final Deque<Long> globalSends = new ArrayDeque<>();

    public EmailVerificationService(MailService mailService) {
        this.mailService = mailService;
    }

    /** Generate, persist and mail a new code for {@code email}. */
    public void sendCode(String email, String clientIp) {
        String normalized = normalize(email);
        if (normalized == null) {
            throw new BusinessException("邮箱格式不正确");
        }
        if (!mailService.isConfigured()) {
            throw new BusinessException("邮件服务未配置，暂时无法发送验证码，请联系管理员");
        }
        Instant now = Instant.now();
        CodeEntry existing = codes.get(normalized);
        if (existing != null && Duration.between(existing.createdAt, now).compareTo(RESEND_COOLDOWN) < 0) {
            long remaining = RESEND_COOLDOWN.toSeconds()
                    - Duration.between(existing.createdAt, now).toSeconds();
            throw new BusinessException("发送过于频繁，请 " + Math.max(1, remaining) + " 秒后重试");
        }
        synchronized (this) {
            // Daily cap per address.
            DailyCounter daily = dailyByEmail.computeIfAbsent(normalized, key -> new DailyCounter());
            if (!LocalDate.now().equals(daily.date)) {
                daily.date = LocalDate.now();
                daily.count = 0;
            }
            if (daily.count >= DAILY_MAX_PER_EMAIL) {
                throw new BusinessException("该邮箱今日发送验证码次数已达上限");
            }
            // Per-IP window cap.
            if (clientIp != null && !clientIp.isBlank()) {
                Deque<Long> stamps = sendsByIp.computeIfAbsent(clientIp, key -> new ArrayDeque<>());
                long cutoff = now.toEpochMilli() - IP_WINDOW.toMillis();
                while (!stamps.isEmpty() && stamps.peekFirst() < cutoff) {
                    stamps.pollFirst();
                }
                if (stamps.size() >= IP_MAX_PER_WINDOW) {
                    throw new BusinessException("发送过于频繁，请稍后再试");
                }
            }
            // Global hourly cap (protects the SMTP account).
            long cutoff = now.toEpochMilli() - Duration.ofHours(1).toMillis();
            while (!globalSends.isEmpty() && globalSends.peekFirst() < cutoff) {
                globalSends.pollFirst();
            }
            if (globalSends.size() >= GLOBAL_MAX_PER_HOUR) {
                log.warn("Global verification-code hourly limit reached; rejecting send to {}", normalized);
                throw new BusinessException("验证码服务繁忙，请稍后再试");
            }

            String code = generateCode();
            mailService.sendVerificationCode(normalized, code);
            codes.put(normalized, CodeEntry.create(code));
            daily.count++;
            if (clientIp != null && !clientIp.isBlank()) {
                sendsByIp.computeIfAbsent(clientIp, key -> new ArrayDeque<>()).addLast(now.toEpochMilli());
            }
            globalSends.addLast(now.toEpochMilli());
        }
    }

    /** Validate a code for {@code email}; consumes the code on success. */
    public void verify(String email, String code) {
        String normalized = normalize(email);
        if (normalized == null || code == null || code.isBlank()) {
            throw new BusinessException("验证码错误或已过期，请重新获取");
        }
        CodeEntry entry = codes.get(normalized);
        if (entry == null) {
            throw new BusinessException("验证码不存在或已过期，请重新获取");
        }
        if (Instant.now().isAfter(entry.expiresAt)) {
            codes.remove(normalized);
            throw new BusinessException("验证码已过期，请重新获取");
        }
        if (!entry.matches(code)) {
            entry.attempts++;
            if (entry.attempts >= MAX_ATTEMPTS) {
                codes.remove(normalized);
                log.info("Verification code for {} invalidated after too many attempts", normalized);
            }
            throw new BusinessException("验证码错误");
        }
        codes.remove(normalized);
    }

    /** Drop the current code for an address without validation (e.g. account reuse). */
    public void discard(String email) {
        String normalized = normalize(email);
        if (normalized != null) {
            codes.remove(normalized);
        }
    }

    @Scheduled(fixedDelay = 30 * 60 * 1000L)
    public synchronized void purgeExpired() {
        Instant now = Instant.now();
        codes.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt));
        dailyByEmail.entrySet().removeIf(entry -> !LocalDate.now().equals(entry.getValue().date));
        sendsByIp.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        long cutoff = now.toEpochMilli() - IP_WINDOW.toMillis();
        sendsByIp.forEach((ip, stamps) -> {
            while (!stamps.isEmpty() && stamps.peekFirst() < cutoff) {
                stamps.pollFirst();
            }
        });
        sendsByIp.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        long globalCutoff = now.toEpochMilli() - Duration.ofHours(1).toMillis();
        while (!globalSends.isEmpty() && globalSends.peekFirst() < globalCutoff) {
            globalSends.pollFirst();
        }
    }

    private String normalize(String email) {
        if (email == null || email.isBlank()) return null;
        String trimmed = email.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() > 100 || !trimmed.contains("@")) return null;
        return trimmed;
    }

    private String generateCode() {
        return String.format(Locale.ROOT, "%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    private static final class CodeEntry {
        private final String codeHash;
        private final Instant createdAt;
        private final Instant expiresAt;
        private int attempts;

        private CodeEntry(String codeHash, Instant createdAt, Instant expiresAt) {
            this.codeHash = codeHash;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
        }

        static CodeEntry create(String rawCode) {
            Instant now = Instant.now();
            return new CodeEntry(ApiKeyService.hashApiKey(rawCode), now, now.plus(CODE_TTL));
        }

        boolean matches(String rawCode) {
            return codeHash.equals(ApiKeyService.hashApiKey(rawCode));
        }
    }

    private static final class DailyCounter {
        private LocalDate date = LocalDate.now();
        private int count;
    }
}
