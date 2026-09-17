package com.shizuku.translate.integration;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end tests for database-backed presets: the public name list
 * (GET /api/v1/presets) and the admin CRUD endpoints under /api/v1/admin/presets.
 */
@SpringBootTest(properties = {
        "app.jwt.secret=integration-test-secret-0123456789abcdefghijklmnopqrstuv",
        "app.jwt.issuer=shizuku-translate-test",
        "spring.datasource.url=jdbc:h2:mem:presetcrud;DB_CLOSE_DELAY=-1",
        "deepseek.api.key=",
        "app.mail.host=",
        "app.presets[0].name=seed-preset",
        "app.presets[0].prompt=seed prompt text"
})
@AutoConfigureMockMvc
class PresetCrudTest {

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
        // The config seeder runs once at startup, so 'seed-preset' must survive the cleanup;
        // only rows created by the tests themselves are deleted.
        jdbcTemplate.update("DELETE FROM presets WHERE name IN ('renamed-preset', 'crud-preset', 'nope')");
        jdbcTemplate.update("UPDATE presets SET prompt = 'seed prompt text' WHERE name = 'seed-preset'");
        jdbcTemplate.update("DELETE FROM users WHERE email IN ('preset-admin@example.com', 'preset-user@example.com')");

        String hash = passwordEncoder.encode("pass1234");
        // 'shizuku' is listed in app.admin-usernames -> exercises the admin path.
        jdbcTemplate.update("INSERT INTO users (username, email, password_hash, email_verified, created_at, updated_at) "
                + "VALUES ('shizuku', 'preset-admin@example.com', ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", hash);
        jdbcTemplate.update("INSERT INTO users (username, email, password_hash, email_verified, created_at, updated_at) "
                + "VALUES ('presetuser', 'preset-user@example.com', ?, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", hash);
    }

    private String login(String username) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"pass1234\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("token").asText();
    }

    @Test
    void publicPresetsListReflectsAdminCrud() throws Exception {
        String adminToken = login("shizuku");
        String userToken = login("presetuser");

        // The config seeder made the configured name available to the public endpoint.
        mockMvc.perform(get("/api/v1/presets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@ == 'seed-preset')]").exists());

        // Create.
        MvcResult res = mockMvc.perform(post("/api/v1/admin/presets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"crud-preset\",\"prompt\":\"do X\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("crud-preset"))
                .andReturn();
        long id = objectMapper.readTree(res.getResponse().getContentAsByteArray()).get("id").asLong();

        // Public list now contains the new preset; regular users see names but not prompts.
        mockMvc.perform(get("/api/v1/presets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@ == 'crud-preset')]").exists());
        mockMvc.perform(get("/api/v1/presets").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].prompt").doesNotExist());

        // Update.
        mockMvc.perform(put("/api/v1/admin/presets/" + id)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"renamed-preset\",\"prompt\":\"do Y\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("renamed-preset"))
                .andExpect(jsonPath("$.prompt").value("do Y"));

        // Delete.
        mockMvc.perform(delete("/api/v1/admin/presets/" + id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/presets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@ == 'renamed-preset')]").doesNotExist());

        // Non-admins cannot manage presets.
        mockMvc.perform(post("/api/v1/admin/presets")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"nope\",\"prompt\":\"x\"}"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void duplicateNameBlankFieldsAndUnknownIdsAreRejected() throws Exception {
        String adminToken = login("shizuku");

        // Blank fields -> validation error.
        mockMvc.perform(post("/api/v1/admin/presets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"prompt\":\" \"}"))
                .andExpect(status().isBadRequest());

        // Duplicate name -> business error, not a raw 500 from the unique constraint.
        mockMvc.perform(post("/api/v1/admin/presets")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"seed-preset\",\"prompt\":\"another\"}"))
                .andExpect(status().isBadRequest());

        // Unknown ids -> 404.
        mockMvc.perform(put("/api/v1/admin/presets/999999")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\",\"prompt\":\"y\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/v1/admin/presets/999999")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());

        // The seeder never duplicates rows on a fresh context.
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM presets WHERE name = 'seed-preset'", Integer.class);
        assertEquals(1, count);
        String prompt = jdbcTemplate.queryForObject(
                "SELECT prompt FROM presets WHERE name = 'seed-preset'", String.class);
        assertTrue(prompt.contains("seed prompt"));
    }
}
