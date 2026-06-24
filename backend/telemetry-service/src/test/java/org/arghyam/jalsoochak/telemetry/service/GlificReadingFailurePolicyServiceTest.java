package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlificReadingFailurePolicyServiceTest {

    @Test
    void thirdConsecutiveFailureUsesManualUploadMessage() {
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        GlificReadingFailurePolicyService service = new GlificReadingFailurePolicyService(
                jdbcTemplate,
                fixedClock("2026-06-24T10:00:00Z")
        );

        assertEquals("try again", applyFailure(service, "919999999999").getMessage());
        assertEquals("try again", applyFailure(service, "919999999999").getMessage());
        assertEquals(
                GlificReadingFailurePolicyService.MANUAL_UPLOAD_MESSAGE,
                applyFailure(service, "919999999999").getMessage()
        );
    }

    @Test
    void successResetsConsecutiveFailureCountForSameDay() {
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        GlificReadingFailurePolicyService service = new GlificReadingFailurePolicyService(
                jdbcTemplate,
                fixedClock("2026-06-24T10:00:00Z")
        );

        applyFailure(service, "919999999999");
        applyFailure(service, "919999999999");
        service.applyToGlificReadingResult("919999999999", CreateReadingResponse.builder()
                .success(true)
                .message("ok")
                .build());

        assertEquals("try again", applyFailure(service, "919999999999").getMessage());
    }

    @Test
    void newDayStartsAtFirstFailureForSameUser() {
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();

        GlificReadingFailurePolicyService dayOne = new GlificReadingFailurePolicyService(
                jdbcTemplate,
                fixedClock("2026-06-24T10:00:00Z")
        );
        applyFailure(dayOne, "919999999999");
        applyFailure(dayOne, "919999999999");

        GlificReadingFailurePolicyService dayTwo = new GlificReadingFailurePolicyService(
                jdbcTemplate,
                fixedClock("2026-06-25T10:00:00Z")
        );

        assertEquals("try again", applyFailure(dayTwo, "919999999999").getMessage());
    }

    private static CreateReadingResponse applyFailure(GlificReadingFailurePolicyService service, String contactId) {
        return service.applyToGlificReadingResult(contactId, CreateReadingResponse.builder()
                .success(false)
                .message("try again")
                .build());
    }

    private static Clock fixedClock(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static final class FakeJdbcTemplate extends JdbcTemplate {
        private final Map<String, Integer> counts = new HashMap<>();

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            String contactId = (String) args[0];
            LocalDate date = (LocalDate) args[1];
            String key = contactId + "|" + date;
            Integer count = counts.merge(key, 1, Integer::sum);
            return requiredType.cast(count);
        }

        @Override
        public int update(String sql, Object... args) {
            String contactId = (String) args[0];
            LocalDate date = (LocalDate) args[1];
            counts.put(contactId + "|" + date, 0);
            return 1;
        }
    }
}
