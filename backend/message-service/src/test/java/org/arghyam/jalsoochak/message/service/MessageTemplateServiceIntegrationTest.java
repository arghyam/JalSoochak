package org.arghyam.jalsoochak.message.service;

import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSender;
import org.arghyam.jalsoochak.message.channel.provider.glific.GlificAuthService;
import org.junit.jupiter.api.AfterEach;
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
 * Integration tests for {@link MessageTemplateService} against a real PostgreSQL instance.
 *
 * <p>Verifies:
 * <ul>
 *   <li>State name: {@code state_name}, falling back to the tenant's title when absent or blank</li>
 *   <li>Welcome flow id: {@code welcome_flow_id}, trimmed, empty when absent or blank</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class MessageTemplateServiceIntegrationTest {

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

    // Suppress GlificAuthService @PostConstruct login
    @MockBean
    private GlificAuthService glificAuthService;

    // Suppress GlificWhatsAppSender @PostConstruct validateTemplates
    @MockBean
    private WhatsAppSender whatsAppSender;

    @Autowired
    private MessageTemplateService messageTemplateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int TENANT_ID = 1; // seeded in test-schema.sql

    @AfterEach
    void cleanConfig() {
        jdbcTemplate.execute("DELETE FROM common_schema.tenant_config_master_table");
    }

    // ──────────────────────────── state name ───────────────────────────────────

    @Test
    void findStateName_returnsConfiguredValue_whenStateNameKeyExists() {
        insertConfig("state_name", "Madhya Pradesh");

        String name = messageTemplateService.findStateName(TENANT_ID);

        assertThat(name).isEqualTo("Madhya Pradesh");
    }

    @Test
    void findStateName_fallsBackToTenantTitle_whenKeyAbsent() {
        String name = messageTemplateService.findStateName(TENANT_ID);

        assertThat(name).isEqualTo("Test State");
    }

    @Test
    void findStateName_fallsBackToTenantTitle_whenValueIsBlank() {
        insertConfig("state_name", "   ");

        String name = messageTemplateService.findStateName(TENANT_ID);

        assertThat(name).isEqualTo("Test State");
    }

    // ─────────────────────────── welcome flow id ───────────────────────────────

    @Test
    void findWelcomeFlowId_returnsConfiguredValue() {
        insertConfig("welcome_flow_id", "tenant-flow");

        assertThat(messageTemplateService.findWelcomeFlowId(TENANT_ID)).contains("tenant-flow");
    }

    @Test
    void findWelcomeFlowId_returnsEmpty_whenKeyAbsent() {
        assertThat(messageTemplateService.findWelcomeFlowId(TENANT_ID)).isEmpty();
    }

    @Test
    void findWelcomeFlowId_trimsValue() {
        insertConfig("welcome_flow_id", "  tenant-flow  ");

        assertThat(messageTemplateService.findWelcomeFlowId(TENANT_ID)).contains("tenant-flow");
    }

    @Test
    void findWelcomeFlowId_returnsEmpty_whenValueIsBlank() {
        insertConfig("welcome_flow_id", "   ");

        assertThat(messageTemplateService.findWelcomeFlowId(TENANT_ID)).isEmpty();
    }

    // ────────────────────────────── helpers ────────────────────────────────────

    private void insertConfig(String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO common_schema.tenant_config_master_table (tenant_id, config_key, config_value) VALUES (?, ?, ?)",
                TENANT_ID, key, value);
    }
}
