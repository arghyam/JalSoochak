package org.arghyam.jalsoochak.user.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("LanguageCatalogRepository Integration Tests")
class LanguageCatalogRepositoryIntegrationTest extends AbstractPostgresIT {

    @Autowired LanguageCatalogRepository repo;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void seedCatalog() {
        jdbc.execute("DELETE FROM common_schema.language_alias");
        jdbc.execute("DELETE FROM common_schema.language_master");
        // Stored as V38 seeds them: normalised aliases, ids that are not a tenant's list positions.
        jdbc.execute("""
                INSERT INTO common_schema.language_master (language_id, canonical_name, locale_code) VALUES
                    (1, 'English', 'en'), (11, 'Punjabi', 'pa'), (15, 'Sign Language', 'isl')
                """);
        jdbc.execute("""
                INSERT INTO common_schema.language_alias (alias, language_id) VALUES
                    ('english', 1), ('punjabi', 11), ('pa', 11), ('sign language', 15)
                """);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Punjabi", "PUNJABI", "  punjabi ", "pa"})
    @DisplayName("resolves a name or alias in any casing to its catalog id")
    void resolvesNameOrAlias(String alias) {
        assertThat(repo.findLanguageIdByAlias(alias)).hasValue(11);
    }

    @Test
    @DisplayName("matches separators the way V38 stores aliases")
    void normalisesSeparators() {
        assertThat(repo.findLanguageIdByAlias("Sign_Language")).hasValue(15);
    }

    @Test
    @DisplayName("returns empty for a language not in the catalog")
    void unknownLanguage() {
        assertThat(repo.findLanguageIdByAlias("Klingon")).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "--"})
    @DisplayName("returns empty for a blank alias")
    void blankAlias(String alias) {
        assertThat(repo.findLanguageIdByAlias(alias)).isEmpty();
    }
}
