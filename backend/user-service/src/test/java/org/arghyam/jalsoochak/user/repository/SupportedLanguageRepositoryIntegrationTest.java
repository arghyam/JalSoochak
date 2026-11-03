package org.arghyam.jalsoochak.user.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("SupportedLanguageRepository Integration Tests")
class SupportedLanguageRepositoryIntegrationTest extends AbstractPostgresIT {

    private static final String SCHEMA = "tenant_mp";

    @Autowired SupportedLanguageRepository repo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearLanguages() {
        jdbc.execute("TRUNCATE " + SCHEMA + ".language_master_table RESTART IDENTITY");
    }

    private void insert(String language, Integer preference, int status) {
        jdbc.update("INSERT INTO " + SCHEMA + ".language_master_table (language_name, preference, status)"
                + " VALUES (?, ?, ?)", language, preference, status);
    }

    @Test
    @DisplayName("lists the active languages, most preferred first")
    void listsActiveLanguagesByPreference() {
        insert("Hindi", 2, 1);
        insert("Tamil", 1, 0);
        insert("English", 1, 1);

        assertThat(repo.findLanguageNames(SCHEMA)).containsExactly("English", "Hindi");
    }

    @Test
    @DisplayName("lists a language without a preference last")
    void listsLanguageWithoutPreferenceLast() {
        insert("Assamese", null, 1);
        insert("English", 1, 1);

        assertThat(repo.findLanguageNames(SCHEMA)).containsExactly("English", "Assamese");
    }

    @Test
    @DisplayName("is empty when no language is active")
    void emptyWhenNoneActive() {
        insert("English", 1, 0);

        assertThat(repo.findLanguageNames(SCHEMA)).isEmpty();
    }

    @Test
    @DisplayName("rejects a schema name that is not a tenant schema (never reaches SQL)")
    void rejectsInvalidSchema() {
        assertThatThrownBy(() -> repo.findLanguageNames("tenant_mp; DROP TABLE x"))
                .hasMessageContaining("Invalid schema name");
    }
}
