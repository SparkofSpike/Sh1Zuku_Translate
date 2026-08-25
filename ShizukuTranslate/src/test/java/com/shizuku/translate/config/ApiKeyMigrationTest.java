package com.shizuku.translate.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiKeyMigrationTest {

    @Test
    void addsColumnsBackfillsLegacyRowsAndClearsPlaintext() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenReturn(1)
                .thenReturn(0)
                .thenReturn(0)
                .thenReturn(0)
                .thenReturn(0);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any()))
                .thenReturn(0)
                .thenReturn(0)
                .thenReturn(0);
        ApiKeyMigration migration = new ApiKeyMigration(jdbc, "update");

        migration.run(new DefaultApplicationArguments());

        verify(jdbc).execute("ALTER TABLE API_KEYS ADD KEY_PREFIX VARCHAR(32)");
        verify(jdbc).execute("ALTER TABLE API_KEYS ADD KEY_HASH VARCHAR(64)");
        verify(jdbc).execute("ALTER TABLE API_KEYS ADD KEY_VALUE VARCHAR(64)");
    }

    @Test
    void doesNothingWhenSchemaManagementIsDisabled() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ApiKeyMigration migration = new ApiKeyMigration(jdbc, "validate");

        migration.run(new DefaultApplicationArguments());

        verifyNoInteractions(jdbc);
    }
}
