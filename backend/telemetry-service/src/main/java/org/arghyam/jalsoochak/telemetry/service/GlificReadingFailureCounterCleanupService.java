package org.arghyam.jalsoochak.telemetry.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GlificReadingFailureCounterCleanupService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${glific.reading-failure-counter.retention-days:30}")
    private int retentionDays;

    @Scheduled(cron = "${glific.reading-failure-counter.cleanup-cron:0 30 3 * * *}")
    public void deleteExpiredCounters() {
        int effectiveRetentionDays = Math.max(1, retentionDays);
        try {
            int deleted = jdbcTemplate.update("""
                    DELETE FROM common_schema.glific_reading_failure_counter
                    WHERE failure_date < CURRENT_DATE - ?
                    """, effectiveRetentionDays);
            log.info("Glific reading failure counter cleanup: deleted {} expired row(s)", deleted);
        } catch (Exception e) {
            log.error("Glific reading failure counter cleanup failed; will retry on next scheduled run", e);
        }
    }
}
