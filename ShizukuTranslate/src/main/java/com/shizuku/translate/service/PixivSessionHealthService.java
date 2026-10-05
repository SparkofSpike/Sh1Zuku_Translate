package com.shizuku.translate.service;

import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.dto.PixivSearchItem;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Watches the configured Pixiv session cookie so a silent expiry does not go unnoticed.
 *
 * <p>Runs once a day (plus once shortly after startup): searches the site with the same
 * site-level cookie the importer uses and asks whether any R-18 result came back. A working
 * session sees restricted works ({@code xRestrict > 0}) on this tag essentially always; once
 * the session dies — or the account's R-18 display setting is switched back off — the same
 * probe returns only all-ages rows. Both are exactly the conditions that break R-18 imports,
 * so both alert. The daily probe also keeps the session active, which is what makes such
 * cookies last for months in the first place.
 *
 * <p>Alerts are edge-triggered: one e-mail when the session breaks, nothing while it stays
 * broken, and a log line when it recovers. Sending never throws, and with no cookie
 * configured (public-works mode) the check is a no-op.
 */
@Service
public class PixivSessionHealthService {

    private static final Logger log = LoggerFactory.getLogger(PixivSessionHealthService.class);
    /**
     * Canary tag: a first page of this search is almost entirely restricted works while the
     * session is healthy, so "no restricted rows at all" is a reliable failure signal.
     */
    private static final String CANARY_KEYWORD = "R-18";

    private final PixivNovelService pixivNovelService;
    private final AppConfig.AppProperties appProperties;
    private final UserRepository userRepository;
    private final MailService mailService;

    /** True once an alert went out for the current outage; cleared on recovery. */
    private final AtomicBoolean alertActive = new AtomicBoolean(false);

    public PixivSessionHealthService(PixivNovelService pixivNovelService,
                                     AppConfig.AppProperties appProperties,
                                     UserRepository userRepository,
                                     MailService mailService) {
        this.pixivNovelService = pixivNovelService;
        this.appProperties = appProperties;
        this.userRepository = userRepository;
        this.mailService = mailService;
    }

    /** Startup probe after 5 minutes, then once a day. */
    @Scheduled(initialDelay = 5 * 60 * 1000L, fixedDelay = 24 * 60 * 60 * 1000L)
    public void checkSessionHealth() {
        String cookie = appProperties.getPixivSessionCookie();
        if (cookie == null || cookie.isBlank()) {
            return;
        }
        try {
            List<PixivSearchItem> items = pixivNovelService.searchNovels(CANARY_KEYWORD);
            boolean canSeeRestricted = items.stream().anyMatch(item -> item.xRestrict() > 0);
            if (canSeeRestricted) {
                if (alertActive.compareAndSet(true, false)) {
                    log.info("Pixiv 会话已恢复正常：R-18 结果重新可见");
                }
                return;
            }
            alertOnce("Pixiv 搜索未返回任何 R-18 结果（会话可能已失效，或账号的 R-18 显示设置被关闭）");
        } catch (Exception e) {
            alertOnce("Pixiv 会话检查失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** Sends at most one alert per outage; safe to call from the scheduled probe only. */
    private void alertOnce(String reason) {
        if (!alertActive.compareAndSet(false, true)) {
            return;
        }
        log.error("⚠️ Pixiv 会话异常：{}（R-18 导入将不可用；请更新 PIXIV_SESSION_COOKIE）", reason);
        String subject = "【Shizuku翻译】Pixiv 会话可能已失效，R-18 导入受影响";
        String body = "服务器上的 Pixiv 会话（PIXIV_SESSION_COOKIE）可能已失效，"
                + "或该账号的 R-18 显示设置被关闭。\n"
                + "当前表现：R-18 小说的导入与搜索不可用（公开作品不受影响）。\n\n"
                + "检测详情：" + reason + "\n\n"
                + "修复步骤（约 1 分钟）：\n"
                + "1. 用该账号在浏览器登录 pixiv.net，确认「設定 → 閲覧設定」中已开启 R-18 显示；\n"
                + "2. F12 → Application → Cookies → https://www.pixiv.net → 复制 PHPSESSID 的值；\n"
                + "3. 编辑服务器的 /opt/shizuku-translate/env，更新 PIXIV_SESSION_COOKIE=新值；\n"
                + "4. 执行 systemctl restart shizuku-backend。\n\n"
                + "本告警每个失效周期最多发送一次；会话恢复后将自动停止。";
        for (String email : adminEmails()) {
            mailService.sendAdminAlert(email, subject, body);
        }
    }

    /** Deduplicated, non-blank e-mail addresses of the configured administrators. */
    private Set<String> adminEmails() {
        Set<String> emails = new LinkedHashSet<>();
        List<String> adminNames = appProperties.getAdminUsernames();
        if (adminNames == null) {
            return emails;
        }
        for (String name : adminNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                User user = userRepository.findByUsernameIgnoreCase(name).orElse(null);
                if (user != null && user.getEmail() != null && !user.getEmail().isBlank()) {
                    emails.add(user.getEmail());
                }
            } catch (Exception e) {
                log.warn("查询管理员 {} 的邮箱失败", name, e);
            }
        }
        if (emails.isEmpty()) {
            log.warn("没有可用的管理员邮箱，本次 Pixiv 会话告警只写入了日志");
        }
        return emails;
    }
}
