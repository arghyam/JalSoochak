package org.arghyam.jalsoochak.telemetry.service.capture;

import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PduDayLimitTest {

    private static final String SCHEMA = "tenant_test";
    private static final Long SCHEME_ID = 10L;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    @Mock
    private TelemetryTenantRepository repo;

    private PduDayLimit limit;

    @BeforeEach
    void setUp() {
        limit = new PduDayLimit(repo);
    }

    @Test
    void aRunThatFillsTheDayExactlyIsAllowed() {
        when(repo.sumPduMinutesForDay(SCHEMA, SCHEME_ID, DAY, null)).thenReturn(new BigDecimal("1000"));

        assertThat(limit.wouldExceed(SCHEMA, SCHEME_ID, DAY, new BigDecimal("440"), null)).isFalse();
    }

    @Test
    void aRunThatTakesTheDayPastItsMinutesIsNot() {
        when(repo.sumPduMinutesForDay(SCHEMA, SCHEME_ID, DAY, null)).thenReturn(new BigDecimal("1000"));

        assertThat(limit.wouldExceed(SCHEMA, SCHEME_ID, DAY, new BigDecimal("440.5"), null)).isTrue();
    }

    @Test
    void theReplacedRowIsLeftOutOfTheDay() {
        // The total the repository returns already leaves the replaced row out.
        when(repo.sumPduMinutesForDay(SCHEMA, SCHEME_ID, DAY, 99L)).thenReturn(new BigDecimal("400"));

        assertThat(limit.wouldExceed(SCHEMA, SCHEME_ID, DAY, new BigDecimal("1040"), 99L)).isFalse();
    }

    @Test
    void theRefusalIsAnAbnormalReading() {
        assertThat(PduDayLimit.EXCEEDED.errorCode()).isEqualTo(TelemetryErrorCode.ABNORMAL_READING);
        assertThat(PduDayLimit.EXCEEDED.message()).isEqualTo(PduDayLimit.PDU_DAY_TOO_LONG_MESSAGE);
    }
}
