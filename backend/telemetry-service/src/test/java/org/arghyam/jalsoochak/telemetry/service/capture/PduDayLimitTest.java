package org.arghyam.jalsoochak.telemetry.service.capture;

import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PduDayLimitTest {

    private static final String SCHEMA = "tenant_test";
    private static final Long SCHEME_ID = 10L;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    @Mock
    private TelemetryTenantRepository repo;
    @Mock
    private PlatformTransactionManager transactionManager;
    @Mock
    private Supplier<Long> write;
    @Mock
    private Supplier<Long> replacedReadingId;

    private PduDayLimit limit;

    @BeforeEach
    void setUp() {
        limit = new PduDayLimit(repo, new TransactionTemplate(transactionManager));
    }

    @Test
    void aRunThatFillsTheDayExactlyIsWrittenInsideTheDaysLock() {
        when(replacedReadingId.get()).thenReturn(null);
        when(repo.sumPduMinutesForDay(SCHEMA, SCHEME_ID, DAY, null)).thenReturn(new BigDecimal("1000"));
        when(write.get()).thenReturn(7L);

        assertThat(limit.writeWithinLimit(SCHEMA, SCHEME_ID, DAY, new BigDecimal("440"), replacedReadingId, write))
                .contains(7L);

        // The replaced row is looked up and the day summed only once the lock is held, and the
        // transaction holding it commits after the write.
        InOrder order = inOrder(transactionManager, repo, replacedReadingId, write);
        order.verify(transactionManager).getTransaction(any());
        order.verify(repo).lockPduDay(SCHEMA, SCHEME_ID, DAY);
        order.verify(replacedReadingId).get();
        order.verify(repo).sumPduMinutesForDay(SCHEMA, SCHEME_ID, DAY, null);
        order.verify(write).get();
        order.verify(transactionManager).commit(any());
    }

    @Test
    void aRunThatTakesTheDayPastItsMinutesIsNotWritten() {
        when(replacedReadingId.get()).thenReturn(null);
        when(repo.sumPduMinutesForDay(SCHEMA, SCHEME_ID, DAY, null)).thenReturn(new BigDecimal("1000"));

        assertThat(limit.writeWithinLimit(SCHEMA, SCHEME_ID, DAY, new BigDecimal("440.5"), replacedReadingId, write))
                .isEmpty();

        verify(write, never()).get();
    }

    @Test
    void theReplacedRowIsLeftOutOfTheDay() {
        // The total the repository returns already leaves the replaced row out.
        when(replacedReadingId.get()).thenReturn(99L);
        when(repo.sumPduMinutesForDay(SCHEMA, SCHEME_ID, DAY, 99L)).thenReturn(new BigDecimal("400"));
        when(write.get()).thenReturn(99L);

        assertThat(limit.writeWithinLimit(SCHEMA, SCHEME_ID, DAY, new BigDecimal("1040"), replacedReadingId, write))
                .contains(99L);
    }

    @Test
    void theRefusalIsAnAbnormalReading() {
        assertThat(PduDayLimit.EXCEEDED.errorCode()).isEqualTo(TelemetryErrorCode.ABNORMAL_READING);
        assertThat(PduDayLimit.EXCEEDED.message()).isEqualTo(PduDayLimit.PDU_DAY_TOO_LONG_MESSAGE);
    }
}
