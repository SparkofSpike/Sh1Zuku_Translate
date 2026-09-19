package com.shizuku.translate.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shizuku.translate.entity.ApiKey;
import com.shizuku.translate.service.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.util.Optional;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for the plugin device-code flow: the anonymous plugin asks for a code,
 * the logged-in user approves it, and the plugin polls the key out of it exactly once.
 */
@SpringBootTest(properties = {
        "app.jwt.secret=integration-test-secret-0123456789abcdefghijklmnopqrstuv",
        "app.jwt.issuer=shizuku-translate-test",
        "spring.datasource.url=jdbc:h2:mem:plugindevicecode;DB_CLOSE_DELAY=-1",
        "deepseek.api.key=",
        "app.mail.host="
})
@AutoConfigureMockMvc
class PluginDeviceCodeFlowTest {

    private static final String VERIFIED_USER = "plugintester";
    private static final String UNVERIFIED_USER = "pluginunverified";
    private static final String PLUGIN_KEY_NAME = "pixiv-plugin";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ApiKeyService apiKeyService;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM plugin_device_codes");
        // Keys created by this flow reference the seeded users, so they go first.
        jdbcTemplate.update("DELETE FROM api_keys WHERE name = ?", PLUGIN_KEY_NAME);
        jdbcTemplate.update("DELETE FROM users WHERE username IN (?, ?)", VERIFIED_USER, UNVERIFIED_USER);

        String hash = passwordEncoder.encode("pass1234");
        insertUser(VERIFIED_USER, "plugin-verified@example.com", hash, true);
        insertUser(UNVERIFIED_USER, "plugin-unverified@example.com", hash, false);
    }

    private void insertUser(String username, String email, String hash, boolean emailVerified) {
        jdbcTemplate.update("INSERT INTO users (username, email, password_hash, email_verified, created_at, updated_at) "
                + "VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", username, email, hash, emailVerified);
    }

    private String login(String username) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pass1234\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    /** Anonymous step 1, also asserting the frozen request/response shape. */
    private String requestDeviceCode() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/plugin/device-code"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(matchesPattern("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}")))
                .andExpect(jsonPath("$.expiresIn").value(600))
                .andExpect(jsonPath("$.interval").value(2))
                .andExpect(jsonPath("$.verificationPath").value("/plugin-link"))
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("code").asText();
    }

    private void approve(String code, String token, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/plugin/device-code/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void freshCodePollsAsPendingWithoutLeakingAKey() throws Exception {
        String code = requestDeviceCode();

        // No approval has happened, so there is nothing to hand over yet.
        mockMvc.perform(get("/api/v1/plugin/device-code/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.keyValue").doesNotExist())
                .andExpect(jsonPath("$.keyName").doesNotExist());

        assertEquals(0, countKeys());
    }

    @Test
    void unknownCodeIsReportedAsExpiredNotAs404() throws Exception {
        // The SPA fallback answers unmatched paths with index.html + 200, so a 404 would be
        // ambiguous; the contract requires an explicit EXPIRED status instead.
        mockMvc.perform(get("/api/v1/plugin/device-code/ZZZZZZZZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    @Test
    void expiredCodeIsRejectedOnPollAndOnApprove() throws Exception {
        String code = requestDeviceCode();
        expire(code);
        String token = login(VERIFIED_USER);

        mockMvc.perform(get("/api/v1/plugin/device-code/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.keyValue").doesNotExist());

        mockMvc.perform(post("/api/v1/plugin/device-code/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_or_expired_code"));

        assertEquals(0, countKeys());
    }

    @Test
    void approveRejectsUnverifiedEmail() throws Exception {
        String code = requestDeviceCode();
        String token = login(UNVERIFIED_USER);

        mockMvc.perform(post("/api/v1/plugin/device-code/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("email_not_verified"));

        // A rejected approval must leave the code pending and mint no key.
        mockMvc.perform(get("/api/v1/plugin/device-code/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertEquals(0, countKeys());
    }

    @Test
    void approvingTwiceIsRejectedAndMintsOnlyOneKey() throws Exception {
        String code = requestDeviceCode();
        String token = login(VERIFIED_USER);

        approve(code, token, 200);

        mockMvc.perform(post("/api/v1/plugin/device-code/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("code_already_approved"));

        assertEquals(1, countKeys());
    }

    @Test
    void approvedCodeDeliversAWorkingKeyExactlyOnce() throws Exception {
        String code = requestDeviceCode();
        String token = login(VERIFIED_USER);

        mockMvc.perform(post("/api/v1/plugin/device-code/approve")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.keyName").value(PLUGIN_KEY_NAME));

        MvcResult poll = mockMvc.perform(get("/api/v1/plugin/device-code/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.keyName").value(PLUGIN_KEY_NAME))
                .andExpect(jsonPath("$.keyValue").value(startsWith("sk-st-")))
                .andReturn();
        String keyValue = objectMapper.readTree(poll.getResponse().getContentAsString())
                .get("keyValue").asText();

        assertTrue(keyValue.matches("sk-st-[0-9a-f]{64}"), "unexpected key shape: " + keyValue);
        // The whole point of the flow: the key handed to the plugin really authenticates.
        Optional<ApiKey> authenticated = apiKeyService.authenticate(keyValue);
        assertTrue(authenticated.isPresent(), "delivered key does not authenticate");
        assertEquals(PLUGIN_KEY_NAME, authenticated.get().getName());
        assertEquals(VERIFIED_USER, authenticated.get().getUser().getUsername());

        // Plaintext is dropped the moment it has been handed over.
        assertNull(jdbcTemplate.queryForObject(
                "SELECT pending_key FROM plugin_device_codes WHERE code = ?", String.class, code));

        // Second poll: consumed, and the key is never repeated.
        mockMvc.perform(get("/api/v1/plugin/device-code/" + code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONSUMED"))
                .andExpect(jsonPath("$.keyValue").doesNotExist())
                .andExpect(jsonPath("$.keyName").doesNotExist());
    }

    private void expire(String code) {
        jdbcTemplate.update("UPDATE plugin_device_codes SET expires_at = ? WHERE code = ?",
                new Timestamp(System.currentTimeMillis() - 60_000L), code);
    }

    private int countKeys() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM api_keys WHERE name = ?", Integer.class, PLUGIN_KEY_NAME);
        return count == null ? 0 : count;
    }
}
