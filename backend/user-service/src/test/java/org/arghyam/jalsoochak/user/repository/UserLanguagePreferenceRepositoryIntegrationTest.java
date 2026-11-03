package org.arghyam.jalsoochak.user.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("UserLanguagePreferenceRepository Integration Tests")
class UserLanguagePreferenceRepositoryIntegrationTest extends AbstractPostgresIT {

    private static final String SCHEMA = "tenant_mp";

    @Autowired UserLanguagePreferenceRepository repo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearPreferences() {
        jdbc.execute("TRUNCATE " + SCHEMA + ".user_language_preference RESTART IDENTITY");
    }

    private Map<String, String> languageByContact() {
        Map<String, String> languages = new HashMap<>();
        jdbc.queryForList("SELECT contact_id, language_value FROM " + SCHEMA + ".user_language_preference")
                .forEach(row -> languages.put((String) row.get("contact_id"), (String) row.get("language_value")));
        return languages;
    }

    @Test
    @DisplayName("adds a row for each contact that has none")
    void insertsMissingContacts() {
        repo.upsertAll(SCHEMA, List.of("919876543210", "919876543211"), "Punjabi");

        assertThat(languageByContact()).containsOnly(
                Map.entry("919876543210", "Punjabi"), Map.entry("919876543211", "Punjabi"));
    }

    @Test
    @DisplayName("overwrites a contact's chosen language and bumps updated_at")
    void overwritesExistingContact() {
        jdbc.update("INSERT INTO " + SCHEMA + ".user_language_preference"
                + " (contact_id, language_value, created_at, updated_at)"
                + " VALUES ('919876543210', 'Hindi', NOW() - INTERVAL '1 day', NOW() - INTERVAL '1 day')");

        repo.upsertAll(SCHEMA, List.of("919876543210"), "Punjabi");

        assertThat(languageByContact()).containsOnly(Map.entry("919876543210", "Punjabi"));
        assertThat(jdbc.queryForObject("SELECT updated_at > NOW() - INTERVAL '1 hour' FROM " + SCHEMA
                + ".user_language_preference WHERE contact_id = '919876543210'", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("stores the contact id digits-only, once, as the chatbot looks it up")
    void storesDigitsOnlyOnce() {
        repo.upsertAll(SCHEMA, List.of("+91 98765-43210", "919876543210"), "Punjabi");

        assertThat(languageByContact()).containsOnly(Map.entry("919876543210", "Punjabi"));
    }

    @Test
    @DisplayName("does nothing for no contacts")
    void noContacts() {
        repo.upsertAll(SCHEMA, List.of(), "Punjabi");

        assertThat(languageByContact()).isEmpty();
    }

    @Test
    @DisplayName("rejects a schema name that is not a tenant schema (never reaches SQL)")
    void rejectsInvalidSchema() {
        assertThatThrownBy(() -> repo.upsertAll("tenant_mp; DROP TABLE x", List.of("919876543210"), "Punjabi"))
                .hasMessageContaining("Invalid schema name");
    }
}
