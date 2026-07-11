package org.arghyam.jalsoochak.telemetry.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class ReadingTimeTest {

    @Test
    void fromClient_convertsUtcInstantToIstWallClock() {
        // 18:45Z on Jul 9 == 00:15 IST on Jul 10: the exact boundary the UTC->IST migration fixes,
        // where the UTC calendar date (Jul 9) and the IST calendar date (Jul 10) disagree.
        OffsetDateTime utc = OffsetDateTime.of(2026, 7, 9, 18, 45, 0, 0, ZoneOffset.UTC);

        LocalDateTime ist = ReadingTime.fromClient(utc);

        assertThat(ist).isEqualTo(LocalDateTime.of(2026, 7, 10, 0, 15, 0));
        assertThat(LocalDate.from(ist)).isEqualTo(LocalDate.of(2026, 7, 10));
    }

    @Test
    void fromClient_isInstantPreservingRegardlessOfClientOffset() {
        // The same instant expressed with a +05:30 offset must yield the same IST wall clock.
        OffsetDateTime istOffset =
                OffsetDateTime.of(2026, 7, 10, 0, 15, 0, 0, ZoneOffset.ofHoursMinutes(5, 30));

        assertThat(ReadingTime.fromClient(istOffset))
                .isEqualTo(LocalDateTime.of(2026, 7, 10, 0, 15, 0));
    }

    @Test
    void fromClient_null_returnsNull() {
        assertThat(ReadingTime.fromClient(null)).isNull();
    }

    @Test
    void now_isEvaluatedInIst() {
        LocalDateTime expected = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));

        assertThat(ReadingTime.now()).isBetween(expected.minusMinutes(1), expected.plusMinutes(1));
    }
}
