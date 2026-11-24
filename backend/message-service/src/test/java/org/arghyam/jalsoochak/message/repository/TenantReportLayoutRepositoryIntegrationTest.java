package org.arghyam.jalsoochak.message.repository;

import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSender;
import org.arghyam.jalsoochak.message.channel.provider.glific.GlificAuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TenantReportLayoutRepository} against a real PostgreSQL: the SQL is what is under test
 * (the {@code deleted_at} filter, the newest-row ordering, the tenant and key scoping), and none of
 * it would fail against a mocked {@code JdbcTemplate}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("TenantReportLayoutRepository Integration Tests")
class TenantReportLayoutRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Suppress the @PostConstruct network calls these beans make at startup.
    @MockBean
    private GlificAuthService glificAuthService;

    @MockBean
    private WhatsAppSender whatsAppSender;

    @Autowired
    private TenantReportLayoutRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int TENANT_ID = 1; // seeded in test-schema.sql
    private static final String KEY = "SITUATION_REPORT_LAYOUT";
    private static final String CONFIG = "{\"version\":1,\"daily\":{\"roles\":{}}}";

    @AfterEach
    void cleanConfig() {
        jdbcTemplate.execute("DELETE FROM common_schema.tenant_config_master_table");
    }

    private void writeConfig(int tenantId, String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO common_schema.tenant_config_master_table (tenant_id, config_key, config_value)"
                        + " VALUES (?, ?, ?)", tenantId, key, value);
    }

    @Test
    @DisplayName("the stored config is returned as written")
    void storedConfigIsReturned() {
        writeConfig(TENANT_ID, KEY, CONFIG);

        assertThat(repository.findConfigValue(TENANT_ID)).contains(CONFIG);
    }

    @Test
    @DisplayName("a tenant with no config reads as empty")
    void noConfigReadsAsEmpty() {
        assertThat(repository.findConfigValue(TENANT_ID)).isEmpty();
    }

    @Test
    @DisplayName("another config key of the same tenant is not read")
    void otherKeysAreIgnored() {
        writeConfig(TENANT_ID, "EMAIL_PROVIDER_SETTINGS", CONFIG);

        assertThat(repository.findConfigValue(TENANT_ID)).isEmpty();
    }

    @Test
    @DisplayName("one tenant's config is never served to another")
    void configIsScopedToItsTenant() {
        writeConfig(TENANT_ID, KEY, CONFIG);

        assertThat(repository.findConfigValue(TENANT_ID + 99)).isEmpty();
    }

    @Test
    @DisplayName("a soft-deleted config is invisible")
    void softDeletedConfigIsInvisible() {
        writeConfig(TENANT_ID, KEY, CONFIG);
        jdbcTemplate.update("UPDATE common_schema.tenant_config_master_table SET deleted_at = NOW()"
                + " WHERE tenant_id = ? AND config_key = ?", TENANT_ID, KEY);

        assertThat(repository.findConfigValue(TENANT_ID)).isEmpty();
    }

    @Test
    @DisplayName("the most recently updated row wins")
    void newestRowWins() {
        writeConfig(TENANT_ID, KEY, "{\"version\":1,\"weekly\":{\"roles\":{}}}");
        jdbcTemplate.update("UPDATE common_schema.tenant_config_master_table"
                + " SET updated_at = NOW() - INTERVAL '1 day' WHERE tenant_id = ?", TENANT_ID);
        writeConfig(TENANT_ID, KEY, CONFIG);

        assertThat(repository.findConfigValue(TENANT_ID)).contains(CONFIG);
    }

    @Test
    @DisplayName("on equal timestamps the higher id wins")
    void higherIdBreaksTimestampTies() {
        writeConfig(TENANT_ID, KEY, "{\"version\":1,\"weekly\":{\"roles\":{}}}");
        writeConfig(TENANT_ID, KEY, CONFIG);
        jdbcTemplate.update("UPDATE common_schema.tenant_config_master_table"
                + " SET updated_at = TIMESTAMP '2026-11-01 10:00:00' WHERE tenant_id = ?", TENANT_ID);

        assertThat(repository.findConfigValue(TENANT_ID)).contains(CONFIG);
    }

    @Test
    @DisplayName("a blank value reads as empty")
    void blankValueReadsAsEmpty() {
        writeConfig(TENANT_ID, KEY, "   ");

        assertThat(repository.findConfigValue(TENANT_ID)).isEmpty();
    }

    @Test
    @DisplayName("a null value reads as empty")
    void nullValueReadsAsEmpty() {
        writeConfig(TENANT_ID, KEY, null);

        assertThat(repository.findConfigValue(TENANT_ID)).isEmpty();
    }
}
