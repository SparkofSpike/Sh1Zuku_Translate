package com.shizuku.translate.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for the admin endpoint that toggles whether an announcement
 * still requires user confirmation (PATCH /api/v1/admin/announcements/{id}/confirmation-required).
 *
 * <p>Disabling must stop the pop-up for users who have not confirmed yet;
 * re-enabling brings it back while users who already confirmed are not asked
 * again (their acknowledgement rows are kept).</p>
 */
@SpringBootTest(properties = {
        "app.jwt.secret=integration-test-secret-0123456789abcdefghijklmnopqrstuv",
        "app.jwt.issuer=shizuku-translate-test",
        "spring.datasource.url=jdbc:h2:mem:acktoggle;DB_CLOSE_DELAY=-1",
        "deepseek.api.key=",
        "app.mail.host="
})
@AutoConfigureMockMvc
class AnnouncementConfirmationToggleTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void seed() {
        jdbcTemplate.update("DELETE FROM announcement_acknowledgements");
        jdbcTemplate.update("DELETE FROM announcements");
        // Delete by the test-only emails: the admin row keeps the reserved
        // username 'shizuku' (required by app.admin-usernames), so username
        // based cleanup would leak it into the next test's seed.
        jdbcTemplate.update("DELETE FROM users WHERE email IN "
                + "('toggle-admin@example.com', 'toggle-user@example.com')");

        String hash = passwordEncoder.encode("pass1234");
        // 'shizuku' is listed in app.admin-usernames -> exercises the admin path.
        jdbcTemplate.update("INSERT INTO users (username, email, password_hash, email_verified, created_at, updated_at) "
                + "VALUES ('shizuku', 'toggle-admin@example.com', ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", hash);
        jdbcTemplate.update("INSERT INTO users (username, email, password_hash, email_verified, created_at, updated_at) "
                + "VALUES ('toggleuser', 'toggle-user@example.com', ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", hash);
    }

    private String login(String username) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pass1234\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    private long createAnnouncement(String token) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/admin/announcements")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"toggle-test\",\"content\":\"hello\",\"requireConfirmation\":true}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asLong();
    }

    private void setConfirmationRequired(String token, long id, boolean required) throws Exception {
        mockMvc.perform(patch("/api/v1/admin/announcements/" + id + "/confirmation-required")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requireConfirmation\":" + required + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requireConfirmation").value(required));
    }

    @Test
    void disablingStopsPopUpAndReenablingBringsItBack() throws Exception {
        String adminToken = login("shizuku");
        long id = createAnnouncement(adminToken);

        // Pending before the toggle.
        mockMvc.perform(get("/api/v1/announcements/pending").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").exists());

        // Disable: no longer pending for anyone.
        setConfirmationRequired(adminToken, id, false);
        mockMvc.perform(get("/api/v1/announcements/pending").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").doesNotExist());

        // Re-enable: pending again.
        setConfirmationRequired(adminToken, id, true);
        mockMvc.perform(get("/api/v1/announcements/pending").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").exists());
    }

    @Test
    void usersWhoAlreadyConfirmedAreNotAskedAgainAfterReenabling() throws Exception {
        String adminToken = login("shizuku");
        String userToken = login("toggleuser");
        long id = createAnnouncement(adminToken);

        // The user confirms while confirmation is required.
        mockMvc.perform(post("/api/v1/announcements/" + id + "/acknowledge")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        // Disable -> already-confirmed users stay confirmed.
        setConfirmationRequired(adminToken, id, false);
        mockMvc.perform(get("/api/v1/announcements/pending").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").doesNotExist());

        // Re-enable -> still not asked again because the acknowledgement row was kept.
        setConfirmationRequired(adminToken, id, true);
        mockMvc.perform(get("/api/v1/announcements/pending").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").doesNotExist());

        // And the admin acknowledgement list still shows the original confirmation.
        mockMvc.perform(get("/api/v1/admin/announcements/" + id + "/acknowledgements")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void nonAdminCannotToggleAndUnknownAnnouncementIs404() throws Exception {
        String adminToken = login("shizuku");
        String userToken = login("toggleuser");
        long id = createAnnouncement(adminToken);

        // A normal authenticated user is rejected.
        mockMvc.perform(patch("/api/v1/admin/announcements/" + id + "/confirmation-required")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requireConfirmation\":false}"))
                .andExpect(status().is5xxServerError());

        // Missing body field is a clean 400, not a 500.
        mockMvc.perform(patch("/api/v1/admin/announcements/" + id + "/confirmation-required")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        // Unknown announcement id maps to 404.
        mockMvc.perform(patch("/api/v1/admin/announcements/999999/confirmation-required")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requireConfirmation\":false}"))
                .andExpect(status().isNotFound());

        JsonNode body = objectMapper.readTree(mockMvc.perform(
                        patch("/api/v1/admin/announcements/" + id + "/confirmation-required")
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"requireConfirmation\":false}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
        assertEquals(id, body.get("id").asLong());
        assertFalse(body.get("requireConfirmation").asBoolean());
    }
}
