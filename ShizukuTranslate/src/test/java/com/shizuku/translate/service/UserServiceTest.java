package com.shizuku.translate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.config.AppConfig;
import com.shizuku.translate.config.DeepSeekConfig;
import com.shizuku.translate.dto.LoginRequest;
import com.shizuku.translate.dto.RegisterRequest;
import com.shizuku.translate.entity.User;
import com.shizuku.translate.exception.BusinessException;
import com.shizuku.translate.repository.AiModelProfileRepository;
import com.shizuku.translate.repository.PersonalModelApiKeyRepository;
import com.shizuku.translate.exception.EmailNotVerifiedException;
import com.shizuku.translate.repository.UserRepository;
import com.shizuku.translate.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for account registration, login, and the email-verification gate
 * (including the administrator exemption).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private DeepSeekConfig.DeepSeekProperties deepSeekProperties;

    @Mock
    private AiModelProfileRepository modelProfileRepository;

    @Mock
    private PersonalModelApiKeyRepository personalModelApiKeyRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private EmailVerificationService emailVerificationService;

    private AppConfig.AppProperties appProperties;

    @InjectMocks
    private UserService service;

    @BeforeEach
    void setUp() {
        appProperties = new AppConfig.AppProperties();
        appProperties.setAdminUsernames(java.util.List.of("shizuku"));
        service = new UserService(userRepository, passwordEncoder, tokenProvider,
                deepSeekProperties, modelProfileRepository, personalModelApiKeyRepository,
                objectMapper, emailVerificationService, appProperties);
        lenient().when(deepSeekProperties.getDefaultModel()).thenReturn("deepseek-flash");
        lenient().when(deepSeekProperties.getBaseUrl()).thenReturn("https://api.deepseek.com/v1");
        lenient().when(deepSeekProperties.getKey()).thenReturn("site-key");
    }

    private static RegisterRequest registerRequest(String username, String email, String code) {
        RegisterRequest request = new RegisterRequest();
        request.setUsername(username);
        request.setEmail(email);
        request.setPassword("pass1234");
        request.setCode(code);
        return request;
    }

    @Test
    void registrationVerifiesTheCodeAndCreatesAVerifiedAccount() {
        when(userRepository.existsByUsernameIgnoreCase("alice")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("alice@example.com")).thenReturn(false);
        when(passwordEncoder.encode("pass1234")).thenReturn("hashed");

        service.register(registerRequest("  alice ", "Alice@Example.com", "123456"));

        verify(emailVerificationService).verify("alice@example.com", "123456");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void registrationRejectsDuplicateUsernameOrEmailBeforeSpendingTheCode() {
        when(userRepository.existsByUsernameIgnoreCase("alice")).thenReturn(true);

        assertThrows(RuntimeException.class,
                () -> service.register(registerRequest("alice", "alice@example.com", "123456")));

        when(userRepository.existsByUsernameIgnoreCase("bob")).thenReturn(false);
        when(userRepository.existsByEmailIgnoreCase("bob@example.com")).thenReturn(true);
        assertThrows(RuntimeException.class,
                () -> service.register(registerRequest("bob", "bob@example.com", "123456")));

        verify(emailVerificationService, never()).verify(any(), any());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void loginAcceptsUsernameOrEmailAndRejectsWrongPasswords() {
        User user = new User();
        user.setUsername("alice");
        user.setPasswordHash("hashed");
        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(user));
        when(userRepository.findByEmailIgnoreCase("alice@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("pass1234", "hashed")).thenReturn(true);
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);
        when(tokenProvider.generateToken("alice")).thenReturn("jwt");

        LoginRequest byName = new LoginRequest();
        byName.setUsername("alice");
        byName.setPassword("pass1234");
        assertEquals("jwt", service.login(byName));

        LoginRequest byEmail = new LoginRequest();
        byEmail.setUsername("alice@example.com");
        byEmail.setPassword("pass1234");
        assertEquals("jwt", service.login(byEmail));

        LoginRequest wrongPassword = new LoginRequest();
        wrongPassword.setUsername("alice");
        wrongPassword.setPassword("wrong");
        assertThrows(RuntimeException.class, () -> service.login(wrongPassword));
    }

    @Test
    void verifiedEmailsUnlockPaidFeaturesAndAdminsAreExempt() {
        User verified = new User();
        verified.setUsername("alice");
        verified.setEmailVerified(true);
        when(userRepository.findByUsernameIgnoreCase("alice")).thenReturn(Optional.of(verified));

        User unverified = new User();
        unverified.setUsername("bob");
        unverified.setEmailVerified(false);
        when(userRepository.findByUsernameIgnoreCase("bob")).thenReturn(Optional.of(unverified));

        assertTrue(service.isEmailVerified("alice"));
        assertTrue(service.isEmailVerified("shizuku")); // admin exemption, no lookup needed
        assertEquals(false, service.isEmailVerified("bob"));

        service.requireEmailVerified("alice");
        service.requireEmailVerified("shizuku");
        assertThrows(EmailNotVerifiedException.class, () -> service.requireEmailVerified("bob"));
    }

    @Test
    void unverifiedAccountsWithoutTheFlagAreAlsoBlocked() {
        // Rows created before the verification feature have email_verified = NULL.
        User legacy = new User();
        legacy.setUsername("carol");
        legacy.setEmailVerified(null);
        when(userRepository.findByUsernameIgnoreCase("carol")).thenReturn(Optional.of(legacy));

        assertEquals(false, service.isEmailVerified("carol"));
        assertThrows(EmailNotVerifiedException.class, () -> service.requireEmailVerified("carol"));
    }

    @Test
    void maskedKeyPreviewsNeverExposeTheWholeSecret() {
        assertEquals("", UserService.maskApiKey(null));
        assertEquals("", UserService.maskApiKey("  "));
        String masked = UserService.maskApiKey("sk-1234567890abcdef");
        assertTrue(masked.startsWith("sk-1234"));
        assertTrue(masked.contains("*****"));
        assertTrue(masked.endsWith("cdef"));
        assertTrue(masked.length() < "sk-1234567890abcdef".length());
    }
}
