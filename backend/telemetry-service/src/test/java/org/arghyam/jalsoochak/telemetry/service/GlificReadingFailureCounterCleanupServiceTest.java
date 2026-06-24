package org.arghyam.jalsoochak.telemetry.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlificReadingFailureCounterCleanupServiceTest {

    @Test
    void deleteExpiredCountersUsesConfiguredRetentionDays() {
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        GlificReadingFailureCounterCleanupService service = new GlificReadingFailureCounterCleanupService(jdbcTemplate);
        ReflectionTestUtils.setField(service, "retentionDays", 45);

        service.deleteExpiredCounters();

        assertEquals(45, jdbcTemplate.retentionDays);
    }

    @Test
    void deleteExpiredCountersKeepsAtLeastOneDayOfHistory() {
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        GlificReadingFailureCounterCleanupService service = new GlificReadingFailureCounterCleanupService(jdbcTemplate);
        ReflectionTestUtils.setField(service, "retentionDays", 0);

        service.deleteExpiredCounters();

        assertEquals(1, jdbcTemplate.retentionDays);
    }

    private static final class FakeJdbcTemplate extends JdbcTemplate {
        private int retentionDays;

        @Override
        public int update(String sql, Object... args) {
            retentionDays = (int) args[0];
            return 7;
        }
    }
}
