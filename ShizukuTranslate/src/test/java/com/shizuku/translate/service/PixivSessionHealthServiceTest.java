package com.shizuku.translate.service;

import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.dto.PixivSearchItem;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.exception.BusinessException;
import com.shizuku.translate.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The Pixiv session watchdog: silent when there is no session to watch, exactly one alert per
 * outage, and a fresh alert after a recovery.
 */
@ExtendWith(MockitoExtension.class)
class PixivSessionHealthServiceTest {

    @Mock
    private PixivNovelService pixivNovelService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private MailService mailService;

    private AppConfig.AppProperties properties;
    private PixivSessionHealthService service;

    @BeforeEach
    void setUp() {
        properties = new AppConfig.AppProperties();
        properties.setAdminUsernames(List.of("shizuku"));
        User admin = new User();
        admin.setUsername("shizuku");
        admin.setEmail("admin@example.com");
        org.mockito.Mockito.lenient().when(userRepository.findByUsernameIgnoreCase("shizuku"))
                .thenReturn(Optional.of(admin));
        service = new PixivSessionHealthService(pixivNovelService, properties, userRepository, mailService);
    }

    private void configureCookie(String value) {
        AppConfig.AppProperties.PixivProperties pixiv = new AppConfig.AppProperties.PixivProperties();
        pixiv.setSessionCookie(value);
        properties.setPixiv(pixiv);
    }

    private PixivSearchItem item(int xRestrict) {
        return new PixivSearchItem("1", "t", "a", List.of(), xRestrict, "", 100);
    }

    @Test
    void noCookieMeansNoCheckAtAll() {
        service.checkSessionHealth();
        verifyNoInteractions(pixivNovelService);
        verifyNoInteractions(mailService);
    }

    @Test
    void healthySessionDoesNotAlert() {
        configureCookie("sess");
        when(pixivNovelService.searchNovels("R-18"))
                .thenReturn(List.of(item(1), item(1), item(0)));

        service.checkSessionHealth();

        verify(mailService, never()).sendAdminAlert(anyString(), anyString(), anyString());
    }

    @Test
    void deadSessionAlertsExactlyOnce() {
        configureCookie("sess");
        when(pixivNovelService.searchNovels("R-18"))
                .thenReturn(List.of(item(0), item(0)));

        service.checkSessionHealth();
        service.checkSessionHealth();
        service.checkSessionHealth();

        verify(mailService, times(1))
                .sendAdminAlert(eq("admin@example.com"), anyString(), anyString());
    }

    @Test
    void searchFailureAlsoAlertsOnce() {
        configureCookie("sess");
        when(pixivNovelService.searchNovels("R-18"))
                .thenThrow(new BusinessException("Pixiv 搜索失败（HTTP 403）"));

        service.checkSessionHealth();
        service.checkSessionHealth();

        verify(mailService, times(1))
                .sendAdminAlert(eq("admin@example.com"), anyString(), anyString());
    }

    @Test
    void recoveryResetsTheOutageStateSoTheNextBreakAlertsAgain() {
        configureCookie("sess");
        when(pixivNovelService.searchNovels("R-18")).thenReturn(List.of(item(0)));
        service.checkSessionHealth();
        verify(mailService, times(1)).sendAdminAlert(anyString(), anyString(), anyString());

        // Session fixed: probe turns healthy, state clears without another mail.
        when(pixivNovelService.searchNovels("R-18")).thenReturn(List.of(item(1)));
        service.checkSessionHealth();
        verify(mailService, times(1)).sendAdminAlert(anyString(), anyString(), anyString());

        // Breaks again later: a second outage gets its own alert.
        when(pixivNovelService.searchNovels("R-18")).thenReturn(List.of(item(0)));
        service.checkSessionHealth();
        verify(mailService, times(2)).sendAdminAlert(anyString(), anyString(), anyString());
    }

    @Test
    void missingAdminEmailStillLogsInsteadOfThrowing() {
        configureCookie("sess");
        when(userRepository.findByUsernameIgnoreCase("shizuku")).thenReturn(Optional.empty());
        when(pixivNovelService.searchNovels("R-18")).thenReturn(List.of(item(0)));

        service.checkSessionHealth();

        verify(mailService, never()).sendAdminAlert(anyString(), anyString(), anyString());
    }
}
