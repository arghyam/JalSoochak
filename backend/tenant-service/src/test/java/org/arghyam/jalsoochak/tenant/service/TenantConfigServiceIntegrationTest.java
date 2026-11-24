package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.config.EscalationScheduleConfig;
import org.arghyam.jalsoochak.tenant.config.NudgeScheduleConfig;
import org.arghyam.jalsoochak.tenant.enums.reportlayout.ReportKind;
import org.arghyam.jalsoochak.tenant.service.PiiEncryptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link TenantConfigService} verifying that JSON config
 * is correctly read from the database per-tenant, with application-level
 * defaults as fallback.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
class TenantConfigServiceIntegrationTest {

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

    @MockBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    /** Suppress PII encryption startup — not under test here. */
    @MockBean
    private PiiEncryptionService piiEncryptionService;

    @Autowired
    private TenantConfigService tenantConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanConfig() {
        jdbcTemplate.execute("DELETE FROM common_schema.tenant_config_master_table");
    }

    // ── getNudgeConfig ──────────────────────────────────────────────────────────

    @Test
    void getNudgeConfig_returnsDefaultHourAndMinute_whenKeyAbsent() {
        NudgeScheduleConfig cfg = tenantConfigService.getNudgeConfig(1);

        assertThat(cfg.getHour()).isEqualTo(8);
        assertThat(cfg.getMinute()).isEqualTo(0);
    }

    @Test
    void getNudgeConfig_returnsConfiguredHourAndMinute_whenKeyPresent() {
        insertConfig(1, "PUMP_OPERATOR_REMINDER_NUDGE_TIME",
                "{\"nudge\":{\"schedule\":{\"hour\":10,\"minute\":30}}}");

        NudgeScheduleConfig cfg = tenantConfigService.getNudgeConfig(1);

        assertThat(cfg.getHour()).isEqualTo(10);
        assertThat(cfg.getMinute()).isEqualTo(30);
    }

    // ── getEscalationConfig ─────────────────────────────────────────────────────

    @Test
    void getEscalationConfig_returnsDefaults_whenKeyAbsent() {
        EscalationScheduleConfig cfg = tenantConfigService.getEscalationConfig(1);

        assertThat(cfg.getHour()).isEqualTo(9);
        assertThat(cfg.getMinute()).isEqualTo(0);
        assertThat(cfg.getLevel1Days()).isEqualTo(3);
        assertThat(cfg.getLevel1OfficerType()).isEqualTo("SECTION_OFFICER");
        assertThat(cfg.getLevel2Days()).isEqualTo(7);
        assertThat(cfg.getLevel2OfficerType()).isEqualTo("DISTRICT_OFFICER");
    }

    @Test
    void getEscalationConfig_returnsAllSixParsedFields_whenKeyPresent() {
        insertConfig(1, "FIELD_STAFF_ESCALATION_RULES",
                "{\"escalation\":{\"schedule\":{\"hour\":11,\"minute\":15}," +
                "\"level1\":{\"threshold\":{\"days\":4},\"officer\":{\"userType\":\"JE\"}}," +
                "\"level2\":{\"threshold\":{\"days\":10},\"officer\":{\"userType\":\"EE\"}}}}");

        EscalationScheduleConfig cfg = tenantConfigService.getEscalationConfig(1);

        assertThat(cfg.getHour()).isEqualTo(11);
        assertThat(cfg.getMinute()).isEqualTo(15);
        assertThat(cfg.getLevel1Days()).isEqualTo(4);
        assertThat(cfg.getLevel1OfficerType()).isEqualTo("JE");
        assertThat(cfg.getLevel2Days()).isEqualTo(10);
        assertThat(cfg.getLevel2OfficerType()).isEqualTo("EE");
    }

    // ── per-tenant isolation ────────────────────────────────────────────────────

    @Test
    void getEscalationConfig_returnsDifferentValues_forDifferentTenants() {
        insertConfig(1, "FIELD_STAFF_ESCALATION_RULES",
                "{\"escalation\":{\"schedule\":{\"hour\":9,\"minute\":0}," +
                "\"level1\":{\"threshold\":{\"days\":3},\"officer\":{\"userType\":\"SECTION_OFFICER\"}}," +
                "\"level2\":{\"threshold\":{\"days\":7},\"officer\":{\"userType\":\"DISTRICT_OFFICER\"}}}}");
        insertConfig(2, "FIELD_STAFF_ESCALATION_RULES",
                "{\"escalation\":{\"schedule\":{\"hour\":10,\"minute\":30}," +
                "\"level1\":{\"threshold\":{\"days\":5},\"officer\":{\"userType\":\"JE\"}}," +
                "\"level2\":{\"threshold\":{\"days\":14},\"officer\":{\"userType\":\"EE\"}}}}");

        EscalationScheduleConfig cfg1 = tenantConfigService.getEscalationConfig(1);
        EscalationScheduleConfig cfg2 = tenantConfigService.getEscalationConfig(2);

        assertThat(cfg1.getLevel1Days()).isEqualTo(3);
        assertThat(cfg1.getLevel1OfficerType()).isEqualTo("SECTION_OFFICER");
        assertThat(cfg1.getLevel2Days()).isEqualTo(7);

        assertThat(cfg2.getLevel1Days()).isEqualTo(5);
        assertThat(cfg2.getLevel1OfficerType()).isEqualTo("JE");
        assertThat(cfg2.getLevel2Days()).isEqualTo(14);
        assertThat(cfg2.getHour()).isEqualTo(10);
        assertThat(cfg2.getMinute()).isEqualTo(30);
    }

    @Test
    void getNudgeConfig_returnsDifferentValues_forDifferentTenants() {
        insertConfig(1, "PUMP_OPERATOR_REMINDER_NUDGE_TIME",
                "{\"nudge\":{\"schedule\":{\"hour\":8,\"minute\":0}}}");
        // Tenant 2 has no row → defaults

        NudgeScheduleConfig cfg1 = tenantConfigService.getNudgeConfig(1);
        NudgeScheduleConfig cfg2 = tenantConfigService.getNudgeConfig(2);

        assertThat(cfg1.getHour()).isEqualTo(8);
        assertThat(cfg1.getMinute()).isEqualTo(0);

        // Tenant 2 falls back to defaults
        assertThat(cfg2.getHour()).isEqualTo(8);
        assertThat(cfg2.getMinute()).isEqualTo(0);
    }

    @Test
    void getEscalationConfig_returnsDefaults_whenKeyPresentForDifferentTenantOnly() {
        // Only tenant 2 has a row; tenant 1 should get defaults
        insertConfig(2, "FIELD_STAFF_ESCALATION_RULES",
                "{\"escalation\":{\"schedule\":{\"hour\":10,\"minute\":0}," +
                "\"level1\":{\"threshold\":{\"days\":9},\"officer\":{\"userType\":\"JE\"}}," +
                "\"level2\":{\"threshold\":{\"days\":20},\"officer\":{\"userType\":\"EE\"}}}}");

        EscalationScheduleConfig cfg1 = tenantConfigService.getEscalationConfig(1);

        assertThat(cfg1.getLevel1Days()).isEqualTo(3); // default
        assertThat(cfg1.getLevel2Days()).isEqualTo(7); // default
    }

    // ── findReportRecipientRoles ────────────────────────────────────────────────

    private static final String REPORT_LAYOUT_KEY = "SITUATION_REPORT_LAYOUT";

    @Test
    void findReportRecipientRoles_readsTheStoredRolesInDocumentOrder() {
        insertConfig(1, REPORT_LAYOUT_KEY, "{\"version\":1,\"weekly\":{\"roles\":{"
                + "\"SUB_DIVISIONAL_OFFICER\":{\"useDefault\":true},\"SECTION_OFFICER\":{\"useDefault\":true}}}}");

        assertThat(tenantConfigService.findReportRecipientRoles(1, ReportKind.WEEKLY))
                .contains(List.of("SUB_DIVISIONAL_OFFICER", "SECTION_OFFICER"));
        assertThat(tenantConfigService.findReportRecipientRoles(2, ReportKind.WEEKLY)).isEmpty();
    }

    @Test
    void findReportRecipientRoles_ignoresASoftDeletedRow_evenWhenItIsNewer() {
        // The same row message-service draws the layout from, so recipients and layouts can't disagree.
        insertConfig(1, REPORT_LAYOUT_KEY,
                "{\"version\":1,\"daily\":{\"roles\":{\"SECTION_OFFICER\":{\"useDefault\":true}}}}");
        insertSoftDeletedConfig(1, REPORT_LAYOUT_KEY,
                "{\"version\":1,\"daily\":{\"roles\":{\"SUB_DIVISIONAL_OFFICER\":{\"layout\":{}}}}}");

        assertThat(tenantConfigService.findReportRecipientRoles(1, ReportKind.DAILY))
                .contains(List.of("SECTION_OFFICER"));
    }

    @Test
    void findReportRecipientRoles_isEmpty_whenTheOnlyRowIsSoftDeleted() {
        insertSoftDeletedConfig(1, REPORT_LAYOUT_KEY,
                "{\"version\":1,\"daily\":{\"roles\":{\"SECTION_OFFICER\":{\"useDefault\":true}}}}");

        assertThat(tenantConfigService.findReportRecipientRoles(1, ReportKind.DAILY)).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private void insertConfig(int tenantId, String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO common_schema.tenant_config_master_table (tenant_id, config_key, config_value) VALUES (?, ?, ?)",
                tenantId, key, value);
    }

    private void insertSoftDeletedConfig(int tenantId, String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO common_schema.tenant_config_master_table (tenant_id, config_key, config_value,"
                        + " updated_at, deleted_at) VALUES (?, ?, ?, NOW() + INTERVAL '1 hour', NOW())",
                tenantId, key, value);
    }
}
