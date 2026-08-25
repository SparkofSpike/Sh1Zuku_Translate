package com.shizuku.translate.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class ApiKeyMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final String ddlAuto;

    public ApiKeyMigration(JdbcTemplate jdbcTemplate,
                           @Value("${spring.jpa.hibernate.ddl-auto:update}") String ddlAuto) {
        this.jdbcTemplate = jdbcTemplate;
        this.ddlAuto = ddlAuto;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!"update".equalsIgnoreCase(ddlAuto) || !tableExists()) {
            return;
        }
        ensureColumn("KEY_PREFIX", "VARCHAR(32)");
        ensureColumn("KEY_HASH", "VARCHAR(64)");
        ensureColumn("KEY_VALUE", "VARCHAR(64)");
        ensureLegacyKeyColumnNullable();
        migrateLegacyRows();
    }

    private boolean tableExists() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES " +
                        "WHERE TABLE_SCHEMA = SCHEMA() AND TABLE_NAME = 'API_KEYS'",
                Integer.class);
        return count != null && count > 0;
    }

    private void ensureColumn(String column, String definition) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = SCHEMA() AND TABLE_NAME = 'API_KEYS' AND COLUMN_NAME = ?",
                Integer.class, column);
        if (count != null && count == 0) {
            jdbcTemplate.execute("ALTER TABLE API_KEYS ADD " + column + " " + definition);
        }
    }

    private void ensureLegacyKeyColumnNullable() {
        String nullable = jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = SCHEMA() AND TABLE_NAME = 'API_KEYS' AND COLUMN_NAME = 'KEY_VALUE'",
                String.class);
        if ("NO".equalsIgnoreCase(nullable)) {
            jdbcTemplate.execute("ALTER TABLE API_KEYS ALTER COLUMN KEY_VALUE VARCHAR(64) NULL");
        }
    }

    private void migrateLegacyRows() {
        List<LegacyKeyRow> rows = jdbcTemplate.query(
                "SELECT ID, KEY_VALUE, KEY_PREFIX, KEY_HASH FROM API_KEYS " +
                        "WHERE KEY_VALUE IS NOT NULL",
                (rs, rowNum) -> new LegacyKeyRow(
                        rs.getLong("ID"), rs.getString("KEY_VALUE"),
                        rs.getString("KEY_PREFIX"), rs.getString("KEY_HASH")));
        for (LegacyKeyRow row : rows) {
            String raw = row.keyValue();
            if (raw == null || raw.isBlank()) {
                throw new IllegalStateException("API key row " + row.id() + " contains blank plaintext");
            }
            String expectedHash = ApiKeyHash.hash(raw);
            String prefix = row.keyPrefix() == null || row.keyPrefix().isBlank()
                    ? maskPrefix(raw) : row.keyPrefix();
            if (row.keyHash() != null && !row.keyHash().isBlank() && !expectedHash.equals(row.keyHash())) {
                throw new IllegalStateException("API key row " + row.id() + " has an invalid hash");
            }
            jdbcTemplate.update("UPDATE API_KEYS SET KEY_PREFIX = ?, KEY_HASH = ?, KEY_VALUE = NULL WHERE ID = ?",
                    prefix, expectedHash, row.id());
        }
    }

    private static String maskPrefix(String raw) {
        int length = Math.min(12, raw.length());
        return raw.substring(0, length) + "...";
    }

    private record LegacyKeyRow(Long id, String keyValue, String keyPrefix, String keyHash) {}

    static final class ApiKeyHash {
        private ApiKeyHash() {}

        static String hash(String raw) {
            try {
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                return java.util.HexFormat.of().formatHex(
                        digest.digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
