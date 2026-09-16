package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.config.EscalationScheduleConfig;
import org.arghyam.jalsoochak.tenant.config.NudgeScheduleConfig;
import org.arghyam.jalsoochak.tenant.config.WeeklyReportScheduleConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TenantConfigService} JSON parsing and per-tenant isolation.
 */
@ExtendWith(MockitoExtension.class)
class TenantConfigServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private TenantConfigService service;

    private static final int TENANT_ID = 42;

    @BeforeEach
    void setUp() {
        service = new TenantConfigService(jdbcTemplate, new ObjectMapper());
        // Set default @Value fields
        ReflectionTestUtils.setField(service, "defaultNudgeHour", 8);
        ReflectionTestUtils.setField(service, "defaultNudgeMinute", 0);
        ReflectionTestUtils.setField(service, "defaultEscalationHour", 9);
        ReflectionTestUtils.setField(service, "defaultEscalationMinute", 0);
        ReflectionTestUtils.setField(service, "defaultLevel1Days", 3);
        ReflectionTestUtils.setField(service, "defaultLevel2Days", 7);
        ReflectionTestUtils.setField(service, "defaultLevel1OfficerType", "SECTION_OFFICER");
        ReflectionTestUtils.setField(service, "defaultLevel2OfficerType", "DISTRICT_OFFICER");
        ReflectionTestUtils.setField(service, "defaultWeeklyReportDayOfWeek", 1);
        ReflectionTestUtils.setField(service, "defaultWeeklyReportHour", 9);
        ReflectionTestUtils.setField(service, "defaultWeeklyReportMinute", 0);
    }

    // ── getNudgeConfig ──────────────────────────────────────────────────────────

    @Test
    void getNudgeConfig_parsesHourAndMinute_fromValidJson() {
        stubNudgeJson(TENANT_ID, "{\"nudge\":{\"schedule\":{\"hour\":10,\"minute\":30}}}");

        NudgeScheduleConfig cfg = service.getNudgeConfig(TENANT_ID);

        assertThat(cfg.getHour()).isEqualTo(10);
        assertThat(cfg.getMinute()).isEqualTo(30);
    }

    @Test
    void getNudgeConfig_returnsDefaults_whenRowAbsent() {
        stubNudgeMissing(TENANT_ID);

        NudgeScheduleConfig cfg = service.getNudgeConfig(TENANT_ID);

        assertThat(cfg.getHour()).isEqualTo(8);
        assertThat(cfg.getMinute()).isEqualTo(0);
    }

    @Test
    void getNudgeConfig_returnsDefaults_whenJsonMalformed() {
        stubNudgeJson(TENANT_ID, "not-valid-json{{");

        NudgeScheduleConfig cfg = service.getNudgeConfig(TENANT_ID);

        assertThat(cfg.getHour()).isEqualTo(8);
        assertThat(cfg.getMinute()).isEqualTo(0);
    }

    @Test
    void getNudgeConfig_usesDefaultForMissingField_whenJsonPartial() {
        stubNudgeJson(TENANT_ID, "{\"nudge\":{\"schedule\":{\"hour\":7}}}");

        NudgeScheduleConfig cfg = service.getNudgeConfig(TENANT_ID);

        assertThat(cfg.getHour()).isEqualTo(7);
        assertThat(cfg.getMinute()).isEqualTo(0); // fallback
    }

    // ── getEscalationConfig ─────────────────────────────────────────────────────

    @Test
    void getEscalationConfig_parsesAllSixFields_fromValidJson() {
        stubEscalationJson(TENANT_ID,
                "{\"escalation\":{\"schedule\":{\"hour\":11,\"minute\":15}," +
                "\"level1\":{\"threshold\":{\"days\":4},\"officer\":{\"userType\":\"JE\"}}," +
                "\"level2\":{\"threshold\":{\"days\":10},\"officer\":{\"userType\":\"EE\"}}}}");

        EscalationScheduleConfig cfg = service.getEscalationConfig(TENANT_ID);

        assertThat(cfg.getHour()).isEqualTo(11);
        assertThat(cfg.getMinute()).isEqualTo(15);
        assertThat(cfg.getLevel1Days()).isEqualTo(4);
        assertThat(cfg.getLevel1OfficerType()).isEqualTo("JE");
        assertThat(cfg.getLevel2Days()).isEqualTo(10);
        assertThat(cfg.getLevel2OfficerType()).isEqualTo("EE");
    }

    @Test
    void getEscalationConfig_returnsDefaults_whenRowAbsent() {
        stubEscalationMissing(TENANT_ID);

        EscalationScheduleConfig cfg = service.getEscalationConfig(TENANT_ID);

        assertThat(cfg.getHour()).isEqualTo(9);
        assertThat(cfg.getMinute()).isEqualTo(0);
        assertThat(cfg.getLevel1Days()).isEqualTo(3);
        assertThat(cfg.getLevel1OfficerType()).isEqualTo("SECTION_OFFICER");
        assertThat(cfg.getLevel2Days()).isEqualTo(7);
        assertThat(cfg.getLevel2OfficerType()).isEqualTo("DISTRICT_OFFICER");
    }

    @Test
    void getEscalationConfig_returnsDefaults_whenJsonMalformed() {
        stubEscalationJson(TENANT_ID, "{broken");

        EscalationScheduleConfig cfg = service.getEscalationConfig(TENANT_ID);

        assertThat(cfg.getLevel1Days()).isEqualTo(3);
        assertThat(cfg.getLevel2Days()).isEqualTo(7);
    }

    // ── per-tenant isolation ────────────────────────────────────────────────────

    @Test
    void getNudgeConfig_queriesWithBothTenantIdAndConfigKey() {
        stubNudgeMissing(TENANT_ID);

        service.getNudgeConfig(TENANT_ID);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(
                contains("tenant_id = ?"),
                eq(String.class),
                argsCaptor.capture());

        Object[] args = argsCaptor.getValue();
        assertThat(args).containsExactly(TENANT_ID, "PUMP_OPERATOR_REMINDER_NUDGE_TIME");
    }

    @Test
    void getEscalationConfig_queriesWithBothTenantIdAndConfigKey() {
        stubEscalationMissing(TENANT_ID);

        service.getEscalationConfig(TENANT_ID);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(
                contains("tenant_id = ?"),
                eq(String.class),
                argsCaptor.capture());

        Object[] args = argsCaptor.getValue();
        assertThat(args).containsExactly(TENANT_ID, "FIELD_STAFF_ESCALATION_RULES");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    // ── getWeeklyReportConfig ───────────────────────────────────────────────────

    @Test
    void getWeeklyReportConfig_parsesDayHourAndMinute_fromValidJson() {
        stubWeeklyReportJson(TENANT_ID,
                "{\"weeklyReport\":{\"schedule\":{\"dayOfWeek\":3,\"hour\":7,\"minute\":45}}}");

        WeeklyReportScheduleConfig cfg = service.getWeeklyReportConfig(TENANT_ID);

        assertThat(cfg.getDayOfWeek()).isEqualTo(3);
        assertThat(cfg.getHour()).isEqualTo(7);
        assertThat(cfg.getMinute()).isEqualTo(45);
    }

    @Test
    void getWeeklyReportConfig_returnsDefaults_whenRowAbsent() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(TENANT_ID),
                eq("WEEKLY_SITUATION_REPORT_TIME")))
                .thenThrow(new EmptyResultDataAccessException(1));

        WeeklyReportScheduleConfig cfg = service.getWeeklyReportConfig(TENANT_ID);

        assertThat(cfg.getDayOfWeek()).isEqualTo(1);
        assertThat(cfg.getHour()).isEqualTo(9);
        assertThat(cfg.getMinute()).isEqualTo(0);
    }

    @Test
    void getWeeklyReportConfig_fallsBackPerField_whenJsonIsPartial() {
        // A half-written config must not lose the fields it does carry, nor throw: the tenant keeps
        // its configured hour and picks up defaults for the rest.
        stubWeeklyReportJson(TENANT_ID, "{\"weeklyReport\":{\"schedule\":{\"hour\":11}}}");

        WeeklyReportScheduleConfig cfg = service.getWeeklyReportConfig(TENANT_ID);

        assertThat(cfg.getHour()).isEqualTo(11);
        assertThat(cfg.getDayOfWeek()).isEqualTo(1);
        assertThat(cfg.getMinute()).isEqualTo(0);
    }

    @Test
    void getWeeklyReportConfig_returnsDefaults_whenJsonIsMalformed() {
        // Degrading to defaults beats throwing: a tenant with bad config still gets its report.
        stubWeeklyReportJson(TENANT_ID, "{not json");

        WeeklyReportScheduleConfig cfg = service.getWeeklyReportConfig(TENANT_ID);

        assertThat(cfg.getDayOfWeek()).isEqualTo(1);
        assertThat(cfg.getHour()).isEqualTo(9);
    }

    private void stubWeeklyReportJson(int tenantId, String json) {
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(tenantId),
                eq("WEEKLY_SITUATION_REPORT_TIME")))
                .thenReturn(json);
    }

    private void stubNudgeJson(int tenantId, String json) {
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(tenantId), eq("PUMP_OPERATOR_REMINDER_NUDGE_TIME")))
                .thenReturn(json);
    }

    private void stubNudgeMissing(int tenantId) {
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(tenantId), eq("PUMP_OPERATOR_REMINDER_NUDGE_TIME")))
                .thenThrow(new EmptyResultDataAccessException(1));
    }

    private void stubEscalationJson(int tenantId, String json) {
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(tenantId), eq("FIELD_STAFF_ESCALATION_RULES")))
                .thenReturn(json);
    }

    private void stubEscalationMissing(int tenantId) {
        when(jdbcTemplate.queryForObject(any(String.class), eq(String.class), eq(tenantId), eq("FIELD_STAFF_ESCALATION_RULES")))
                .thenThrow(new EmptyResultDataAccessException(1));
    }
}
