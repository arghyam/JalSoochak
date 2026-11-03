package org.arghyam.jalsoochak.telemetry.repository;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link SupportedLanguageRepository} against the tenant {@code language_master_table}, on real PostgreSQL. */
@Testcontainers
class SupportedLanguageRepositoryIntegrationTest {

    private static final String SCHEMA = "tenant_as";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbcTemplate;
    private static SupportedLanguageRepository repository;

    @BeforeAll
    static void createSchema() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);

        // The table as create_tenant_schema() creates it.
        jdbcTemplate.execute("CREATE SCHEMA " + SCHEMA);
        jdbcTemplate.execute("""
                CREATE TABLE %s.language_master_table (
                    id              SERIAL          PRIMARY KEY,
                    language_name   VARCHAR(255)    NOT NULL,
                    preference      INTEGER,
                    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
                    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
                    status          INTEGER         NOT NULL,
                    created_by      INTEGER,
                    updated_by      INTEGER,
                    deleted_at      TIMESTAMP,
                    deleted_by      INTEGER,
                    CONSTRAINT uq_language_name UNIQUE (language_name)
                )
                """.formatted(SCHEMA));
        repository = new SupportedLanguageRepository(jdbcTemplate);
    }

    @BeforeEach
    void clearLanguages() {
        jdbcTemplate.execute("TRUNCATE " + SCHEMA + ".language_master_table RESTART IDENTITY");
    }

    private void insert(String language, Integer preference, int status) {
        jdbcTemplate.update("INSERT INTO " + SCHEMA + ".language_master_table (language_name, preference, status)"
                + " VALUES (?, ?, ?)", language, preference, status);
    }

    @Test
    void listsTheActiveLanguagesMostPreferredFirst() {
        insert("Hindi", 2, 1);
        insert("Tamil", 1, 0);
        insert("English", 1, 1);

        assertThat(repository.findLanguageNames(SCHEMA)).containsExactly("English", "Hindi");
    }

    @Test
    void listsALanguageWithoutAPreferenceLast() {
        insert("Assamese", null, 1);
        insert("English", 1, 1);

        assertThat(repository.findLanguageNames(SCHEMA)).containsExactly("English", "Assamese");
    }

    @Test
    void isEmptyWhenNoLanguageIsActive() {
        insert("English", 1, 0);

        assertThat(repository.findLanguageNames(SCHEMA)).isEmpty();
    }

    @Test
    void rejectsASchemaNameThatIsNotAPlainIdentifier() {
        assertThatThrownBy(() -> repository.findLanguageNames("tenant_as; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
