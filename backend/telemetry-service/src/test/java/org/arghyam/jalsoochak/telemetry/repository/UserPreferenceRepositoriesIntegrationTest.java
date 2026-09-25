package org.arghyam.jalsoochak.telemetry.repository;

import org.arghyam.jalsoochak.telemetry.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * USER-PREFERENCE-TENANT-SCHEMA: {@link UserChannelPreferenceRepository} and
 * {@link UserLanguagePreferenceRepository} against the per-tenant tables V47 creates, on real
 * PostgreSQL.
 *
 * <p>The cross-tenant lookup is one {@code UNION ALL} over every tenant schema, so it is checked here
 * with a schema that predates V47 (which would fail the whole query if it were included) and a
 * soft-deleted tenant whose schema still holds the newest row.
 */
@Testcontainers
class UserPreferenceRepositoriesIntegrationTest {

    private static final int TENANT_AS = 1;
    private static final int TENANT_MP = 2;
    private static final int DELETED_TENANT = 3;
    private static final int PRE_V47_TENANT = 4;
    private static final List<String> SCHEMAS_WITH_TABLES = List.of("tenant_as", "tenant_mp", "tenant_xx");

    private static final String CONTACT = "919999900001";
    private static final String RAW_CONTACT = "+91 99999-00001";
    private static final LocalDateTime OLDER = LocalDateTime.of(2026, 1, 1, 9, 0);
    private static final LocalDateTime NEWER = LocalDateTime.of(2026, 2, 1, 9, 0);
    private static final LocalDateTime NEWEST = LocalDateTime.of(2026, 3, 1, 9, 0);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbcTemplate;
    private static UserChannelPreferenceRepository channelRepository;
    private static UserLanguagePreferenceRepository languageRepository;

    @BeforeAll
    static void createSchemas() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.execute("CREATE SCHEMA common_schema");
        jdbcTemplate.execute("""
                CREATE TABLE common_schema.tenant_master_table (
                    id         INTEGER PRIMARY KEY,
                    state_code VARCHAR(10) NOT NULL,
                    deleted_at TIMESTAMP
                )
                """);
        // The state code is stored untrimmed and mixed-case for MP, as the schema is derived from it.
        jdbcTemplate.update("""
                INSERT INTO common_schema.tenant_master_table (id, state_code, deleted_at)
                VALUES (?, 'AS', NULL), (?, ' Mp ', NULL), (?, 'XX', NOW()), (?, 'ZZ', NULL)
                """, TENANT_AS, TENANT_MP, DELETED_TENANT, PRE_V47_TENANT);

        for (String schemaName : SCHEMAS_WITH_TABLES) {
            createPreferenceTables(schemaName);
        }
        jdbcTemplate.execute("CREATE SCHEMA tenant_zz");

        String key = Base64.getEncoder().encodeToString(new byte[32]);
        TelemetryTenantRepository tenantRepository =
                new TelemetryTenantRepository(jdbcTemplate, new PiiEncryptionService(key, key));
        channelRepository = new UserChannelPreferenceRepository(jdbcTemplate);
        languageRepository = new UserLanguagePreferenceRepository(jdbcTemplate, tenantRepository);
    }

    /** The tables as V47's {@code common_schema.create_user_preference_tables} creates them. */
    private static void createPreferenceTables(String schemaName) {
        jdbcTemplate.execute("CREATE SCHEMA " + schemaName);
        jdbcTemplate.execute("""
                CREATE TABLE %s.user_channel_preference (
                    id            BIGSERIAL     PRIMARY KEY,
                    contact_id    VARCHAR(50)   NOT NULL,
                    channel_value VARCHAR(100)  NOT NULL,
                    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
                    updated_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
                    CONSTRAINT uq_user_channel_pref_contact UNIQUE (contact_id)
                )
                """.formatted(schemaName));
        jdbcTemplate.execute("""
                CREATE TABLE %s.user_language_preference (
                    id             BIGSERIAL    PRIMARY KEY,
                    contact_id     TEXT         NOT NULL,
                    language_value TEXT,
                    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
                    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
                    CONSTRAINT uq_user_language_pref_contact UNIQUE (contact_id)
                )
                """.formatted(schemaName));
    }

    @BeforeEach
    void clearPreferences() {
        for (String schemaName : SCHEMAS_WITH_TABLES) {
            jdbcTemplate.execute("TRUNCATE %1$s.user_channel_preference, %1$s.user_language_preference"
                    .formatted(schemaName));
        }
    }

    private static void insertLanguage(String schemaName, String language, LocalDateTime updatedAt) {
        jdbcTemplate.update("""
                INSERT INTO %s.user_language_preference (contact_id, language_value, created_at, updated_at)
                VALUES (?, ?, ?, ?)
                """.formatted(schemaName), CONTACT, language, OLDER, updatedAt);
    }

    private static int rowCount(String schemaName, String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM %s.%s".formatted(schemaName, tableName), Integer.class);
        return count == null ? 0 : count;
    }

    @Nested
    class ChannelPreference {

        @Test
        void upsertStoresTheDigitsOnlyContactAndUpdatesItInPlace() {
            channelRepository.upsert("tenant_as", RAW_CONTACT, "BFM");
            channelRepository.upsert("tenant_as", CONTACT, "ELM");

            assertThat(rowCount("tenant_as", "user_channel_preference")).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT contact_id FROM tenant_as.user_channel_preference", String.class))
                    .isEqualTo(CONTACT);
            assertThat(channelRepository.findChannelValue("tenant_as", RAW_CONTACT)).contains("ELM");
        }

        @Test
        void findChannelValueReadsOnlyTheGivenTenantSchema() {
            channelRepository.upsert("tenant_as", CONTACT, "ELM");

            assertThat(channelRepository.findChannelValue("tenant_mp", CONTACT)).isEmpty();
        }
    }

    @Nested
    class LanguagePreference {

        @Test
        void upsertStoresTheDigitsOnlyContactAndUpdatesItInPlace() {
            languageRepository.upsert("tenant_as", RAW_CONTACT, "Assamese");
            languageRepository.upsert("tenant_as", CONTACT, "Hindi");

            assertThat(rowCount("tenant_as", "user_language_preference")).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT contact_id FROM tenant_as.user_language_preference", String.class))
                    .isEqualTo(CONTACT);
            assertThat(languageRepository.findLanguage("tenant_as", RAW_CONTACT)).contains("Hindi");
        }

        @Test
        void findLanguageReadsOnlyTheGivenTenantSchema() {
            languageRepository.upsert("tenant_as", CONTACT, "Hindi");

            assertThat(languageRepository.findLanguage("tenant_mp", CONTACT)).isEmpty();
        }
    }

    @Nested
    class PreferredTenant {

        @Test
        void isTheTenantWhosePreferenceWasUpdatedMostRecently() {
            // tenant_as is the first UNION branch, so this fails if the order is not applied.
            insertLanguage("tenant_as", "Assamese", OLDER);
            insertLanguage("tenant_mp", "Hindi", NEWER);

            assertThat(languageRepository.findPreferredTenantIdByContactId(CONTACT)).contains(TENANT_MP);
        }

        @Test
        void matchesTheContactInAnyShape() {
            insertLanguage("tenant_as", "Assamese", OLDER);

            assertThat(languageRepository.findPreferredTenantIdByContactId(RAW_CONTACT)).contains(TENANT_AS);
        }

        @Test
        void skipsATenantSchemaThatPredatesTheTable() {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_namespace WHERE nspname = 'tenant_zz'", Integer.class))
                    .isEqualTo(1);
            insertLanguage("tenant_as", "Assamese", OLDER);

            assertThat(languageRepository.findPreferredTenantIdByContactId(CONTACT)).contains(TENANT_AS);
        }

        @Test
        void ignoresASoftDeletedTenantEvenWhenItsRowIsNewest() {
            insertLanguage("tenant_as", "Assamese", OLDER);
            insertLanguage("tenant_xx", "Hindi", NEWEST);

            assertThat(languageRepository.findPreferredTenantIdByContactId(CONTACT)).contains(TENANT_AS);
        }

        @Test
        void isEmptyForAnUnknownContact() {
            insertLanguage("tenant_as", "Assamese", OLDER);

            assertThat(languageRepository.findPreferredTenantIdByContactId("919999900002")).isEmpty();
        }

        @Test
        void isEmptyForAMissingOrDigitlessContact() {
            assertThat(languageRepository.findPreferredTenantIdByContactId(null)).isEmpty();
            assertThat(languageRepository.findPreferredTenantIdByContactId("+-")).isEmpty();
        }
    }
}
