package com.shizuku.translate.service;

import com.shizuku.translate.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailVerificationServiceTest {

    @Mock
    private MailService mailService;

    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        when(mailService.isConfigured()).thenReturn(true);
        service = new EmailVerificationService(mailService);
    }

    private String sentCodeFor(String email) {
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService).sendVerificationCode(eq(email), codeCaptor.capture());
        return codeCaptor.getValue();
    }

    @Test
    void sendsCodeThenVerifiesIt() {
        service.sendCode("Alice@Example.COM", "1.2.3.4");
        String code = sentCodeFor("alice@example.com");

        // Verifies against the normalized (lower-cased) address and consumes the code.
        service.verify("ALICE@example.com", code);
    }

    @Test
    void rejectsWrongCodeAndConsumesCodeAfterTooManyAttempts() {
        service.sendCode("alice@example.com", "1.2.3.4");
        String code = sentCodeFor("alice@example.com");
        String wrong = code.equals("000000") ? "000001" : "000000";

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.verify("alice@example.com", wrong));
        assertTrue(ex.getMessage().contains("验证码错误"));

        for (int i = 0; i < 4; i++) {
            assertThrows(BusinessException.class,
                    () -> service.verify("alice@example.com", wrong));
        }
        // The code has been invalidated by too many failed attempts.
        assertThrows(BusinessException.class,
                () -> service.verify("alice@example.com", code));
    }

    @Test
    void codeIsSingleUse() {
        service.sendCode("alice@example.com", "1.2.3.4");
        String code = sentCodeFor("alice@example.com");

        service.verify("alice@example.com", code);
        assertThrows(BusinessException.class,
                () -> service.verify("alice@example.com", code));
    }

    @Test
    void resendIsCooldownLimited() {
        service.sendCode("alice@example.com", "1.2.3.4");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendCode("alice@example.com", "1.2.3.4"));
        assertTrue(ex.getMessage().contains("发送过于频繁"));
        verify(mailService, times(1)).sendVerificationCode(anyString(), anyString());
    }

    @Test
    void rejectsWhenMailServiceNotConfigured() {
        when(mailService.isConfigured()).thenReturn(false);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sendCode("alice@example.com", "1.2.3.4"));
        assertTrue(ex.getMessage().contains("邮件服务未配置"));
        verify(mailService, never()).sendVerificationCode(anyString(), anyString());
    }

    @Test
    void rejectsUnknownCodeAndMalformedAddress() {
        assertThrows(BusinessException.class,
                () -> service.verify("alice@example.com", "123456"));
        assertThrows(BusinessException.class,
                () -> service.sendCode("not-an-email", "1.2.3.4"));
    }
}
