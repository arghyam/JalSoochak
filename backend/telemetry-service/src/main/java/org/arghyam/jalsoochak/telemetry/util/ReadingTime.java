package org.arghyam.jalsoochak.telemetry.util;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * Reading timestamps ({@code reading_at} / {@code reading_date}) are stored in IST across the app,
 * while audit columns ({@code created_at}/{@code updated_at}/{@code attempted_at}) stay UTC. This is
 * the single choke point that converts an incoming reading instant to the IST wall-clock timestamp
 * that gets persisted and emitted to analytics; {@code reading_date} is then {@code
 * LocalDate.from(readingAt)}, so both columns land on the IST calendar day.
 *
 * <p>Downstream, the analytics REPORTED-METRIC queries shift UTC reject timestamps
 * ({@code anomaly.created_at} / {@code submission_attempt.attempted_at}) by {@code + 5:30} to line up
 * with this IST {@code reading_date} boundary — keep those UTC and this IST.
 */
public final class ReadingTime {

    /** India Standard Time. The platform serves Indian water utilities and reports on IST days. */
    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private ReadingTime() {
    }

    /**
     * Convert a client-supplied, offset-aware reading timestamp to the IST wall-clock instant to
     * persist. Instant-preserving: the same moment sent with any offset yields the same IST time.
     */
    public static LocalDateTime fromClient(OffsetDateTime timestamp) {
        return timestamp == null ? null : timestamp.atZoneSameInstant(ZONE).toLocalDateTime();
    }

    /** Current reading instant as an IST wall-clock timestamp (fallback when the client sends none). */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
