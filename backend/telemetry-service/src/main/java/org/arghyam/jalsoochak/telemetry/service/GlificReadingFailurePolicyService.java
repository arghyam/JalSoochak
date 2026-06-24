package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;

@Service
@Slf4j
public class GlificReadingFailurePolicyService {

    static final int MANUAL_UPLOAD_THRESHOLD = 3;
    static final String MANUAL_UPLOAD_MESSAGE =
            "You have tried up to 3 consecutive time today you will be routed to manual submission.";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public GlificReadingFailurePolicyService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemDefaultZone());
    }

    GlificReadingFailurePolicyService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public CreateReadingResponse applyToGlificReadingResult(String contactId, CreateReadingResponse response) {
        if (response == null) {
            return null;
        }

        String counterKey = normalizeContactId(contactId);
        if (counterKey == null) {
            return response;
        }

        try {
            if (response.isSuccess()) {
                resetToday(counterKey);
                return response;
            }

            int consecutiveFailures = incrementFailure(counterKey);
            if (consecutiveFailures >= MANUAL_UPLOAD_THRESHOLD) {
                response.setMessage(MANUAL_UPLOAD_MESSAGE);
            }
        } catch (Exception e) {
            log.warn("Unable to update Glific reading failure counter for contactId {}: {}",
                    contactId, e.getMessage());
        }

        return response;
    }

    private int incrementFailure(String counterKey) {
        Integer count = jdbcTemplate.queryForObject("""
                        INSERT INTO common_schema.glific_reading_failure_counter (
                            contact_id,
                            failure_date,
                            consecutive_failure_count
                        )
                        VALUES (?, ?, 1)
                        ON CONFLICT (contact_id, failure_date)
                        DO UPDATE SET
                            consecutive_failure_count =
                                common_schema.glific_reading_failure_counter.consecutive_failure_count + 1,
                            updated_at = NOW()
                        RETURNING consecutive_failure_count
                        """,
                Integer.class,
                counterKey,
                LocalDate.now(clock));
        return count != null ? count : 0;
    }

    private void resetToday(String counterKey) {
        jdbcTemplate.update("""
                        UPDATE common_schema.glific_reading_failure_counter
                        SET consecutive_failure_count = 0,
                            updated_at = NOW()
                        WHERE contact_id = ?
                          AND failure_date = ?
                        """,
                counterKey,
                LocalDate.now(clock));
    }

    private static String normalizeContactId(String contactId) {
        if (contactId == null || contactId.isBlank()) {
            return null;
        }
        String digits = contactId.replaceAll("\\D", "");
        return digits.isBlank() ? contactId.trim() : digits;
    }
}
