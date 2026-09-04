package com.shizuku.translate.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end email-verification gate over the real Spring context
 * (filters, controllers, services, JPA) with an in-memory database.
 *
 * <p>Existing accounts (legacy rows with {@code email_verified = NULL}) are
 * locked out of translation until they verify the address on their account;
 * administrators stay exempt; freshly registered accounts are verified by
 * construction because registration requires a valid code.</p>
 */
@SpringBootTest(properties = {
        "app.jwt.secret=integration-test-secret-0123456789abcdefghijklmnopqrstuv",
        "app.jwt.issuer=shizuku-translate-test",
        "spring.datasource.url=jdbc:h2:mem:emailflow;DB_CLOSE_DELAY=-1",
        "deepseek.api.key=",
        "app.mail.host="
})
@AutoConfigureMockMvc
class EmailVerificationFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MailService mailService;

    @BeforeEach
    void seedUsers() {
        when(mailService.isConfigured()).thenReturn(true);
        jdbcTemplate.update("DELETE FROM users WHERE username LIKE 'flow%'");
        String hash = passwordEncoder.encode("pass1234");
        // Legacy account registered before email verification existed.
        jdbcTemplate.update("INSERT INTO users (username, email, password_hash, email_verified, created_at, updated_at) "
                + "VALUES ('flowlegacy', 'flowlegacy@example.com', ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", hash);
        // A user named like an admin but never verified.
        jdbcTemplate.update("INSERT INTO users (username, email, password_hash, email_verified, created_at, updated_at) "
                + "VALUES ('flowuser', 'flowuser@example.com', ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", hash);
    }

    private String login(String username) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pass1234\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private String capturedCode(String email) throws Exception {
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService).sendVerificationCode(eq(email), codeCaptor.capture());
        return codeCaptor.getValue();
    }

    @Test
    void unverifiedAccountIsBlockedFromTranslationUntilEmailVerified() throws Exception {
        // ── Legacy account: translation is blocked with the verification gate.
        String legacyToken = login("flowlegacy");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + legacyToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(false));

        MvcResult blocked = mockMvc.perform(post("/api/v1/translate")
                        .header("Authorization", "Bearer " + legacyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceText\":\"test\"}"))
                .andExpect(status().isForbidden())
                .andReturn();
        JsonNode blockedBody = objectMapper.readTree(blocked.getResponse().getContentAsByteArray());
        // "邮箱尚未认证" written as escapes so the assertion is independent of source encoding.
        assertTrue(blockedBody.get("error").asText().contains("\u90ae\u7bb1\u5c1a\u672a\u8ba4\u8bc1"));

        // Creating an extension API key is also gated.
        mockMvc.perform(post("/api/v1/auth/api-key")
                        .header("Authorization", "Bearer " + legacyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"plugin\"}"))
                .andExpect(status().isForbidden());

        // ── Send a code to the account address and verify it.
        mockMvc.perform(post("/api/v1/auth/email/send-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"flowlegacy@example.com\"}"))
                .andExpect(status().isOk());
        String code = capturedCode("flowlegacy@example.com");

        mockMvc.perform(post("/api/v1/auth/email/verify")
                        .header("Authorization", "Bearer " + legacyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"flowlegacy@example.com\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());

        // ── After verification the account reports verified and the gate is gone.
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + legacyToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailVerified").value(true));

        // The DeepSeek key is deliberately blank in tests, so a translation now
        // fails on model configuration (400) instead of the verification gate (403).
        MvcResult allowed = mockMvc.perform(post("/api/v1/translate")
                        .header("Authorization", "Bearer " + legacyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceText\":\"test\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        String allowedBody = new String(allowed.getResponse().getContentAsByteArray(),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(allowedBody.contains("\u90ae\u7bb1\u5c1a\u672a\u8ba4\u8bc1"));
    }

    @Test
    void adminAccountIsExemptFromVerificationGate() throws Exception {
        // flowuser is not in app.admin-usernames -> blocked. The exemption path is
        // exercised by the real configured admin list which contains 'shizuku'.
        jdbcTemplate.update("INSERT INTO users (username, email, password_hash, email_verified, created_at, updated_at) "
                + "VALUES ('shizuku', 'admin@example.com', ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                passwordEncoder.encode("pass1234"));

        String adminToken = login("shizuku");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isAdmin").value(true))
                .andExpect(jsonPath("$.emailVerified").value(true));

        mockMvc.perform(post("/api/v1/translate")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceText\":\"test\"}"))
                .andExpect(status().isBadRequest()); // model-config error, not the 403 gate
        verify(mailService, never()).sendVerificationCode(anyString(), anyString());
    }
}
