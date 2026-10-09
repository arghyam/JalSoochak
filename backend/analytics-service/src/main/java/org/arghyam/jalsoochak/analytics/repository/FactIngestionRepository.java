package org.arghyam.jalsoochak.analytics.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.dto.event.CalculationParameters;
import org.arghyam.jalsoochak.analytics.entity.FactMeterReading;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Types;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQL that meter-reading and water-quantity ingestion needs and Spring Data cannot express: the
 * per-scheme advisory lock, the one-row-per-submission upsert, and where a submission is stored
 * before it is overwritten. It joins the caller's transaction, so its writes and the JPA ones commit
 * or roll back together.
 */
@Repository
@RequiredArgsConstructor
public class FactIngestionRepository {

    /**
     * Advisory-lock namespace for scheme water-quantity writes. The number itself is arbitrary; it
     * only has to keep this lock's keys apart from the other two-int advisory locks taken in the
     * shared database (tenant-service's, keyed the same way on their own namespaces).
     */
    static final int SCHEME_LOCK_NAMESPACE = "analytics_schema.fact_water_quantity_table".hashCode();

    /**
     * Only serialises {@link CalculationParameters}, a plain record. Default settings on purpose:
     * Hibernate reads the column back through {@code @JdbcTypeCode(SqlTypes.JSON)} with its own
     * default mapper, so any {@code spring.jackson.*} customisation of the application's mapper would
     * make the two disagree on property names.
     */
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * {@code ON CONFLICT} names the V56 partial unique index by its columns and predicate. A NULL
     * {@code source_reading_id} never conflicts, so legacy events always insert.
     *
     * <p>The version guard lets a stored row with no version be overwritten, and never lets an event
     * with no version overwrite a versioned row ({@code x <= NULL} is not true). {@code RETURNING}
     * yields no row when the guard refuses, which is how a stale event is recognised.
     */
    private static final String UPSERT_METER_READING_SQL = """
            INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence,
                 image_url, reading_at, channel, reading_date, submission_status, reading_type,
                 correlation_id, created_at, source_reading_id, source_updated_at, calculation_parameters,
                 submitted_unit)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
            ON CONFLICT (tenant_id, source_reading_id) WHERE source_reading_id IS NOT NULL
            DO UPDATE SET
                scheme_id = EXCLUDED.scheme_id,
                user_id = EXCLUDED.user_id,
                extracted_reading = EXCLUDED.extracted_reading,
                confirmed_reading = EXCLUDED.confirmed_reading,
                confidence = EXCLUDED.confidence,
                image_url = EXCLUDED.image_url,
                reading_at = EXCLUDED.reading_at,
                channel = EXCLUDED.channel,
                reading_date = EXCLUDED.reading_date,
                submission_status = EXCLUDED.submission_status,
                reading_type = EXCLUDED.reading_type,
                correlation_id = EXCLUDED.correlation_id,
                source_updated_at = EXCLUDED.source_updated_at,
                calculation_parameters = EXCLUDED.calculation_parameters,
                submitted_unit = EXCLUDED.submitted_unit
            WHERE fact_meter_reading_table.source_updated_at IS NULL
               OR fact_meter_reading_table.source_updated_at <= EXCLUDED.source_updated_at
            RETURNING id
            """;

    private static final int[] UPSERT_METER_READING_TYPES = {
            Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.NUMERIC, Types.NUMERIC, Types.INTEGER,
            Types.VARCHAR, Types.TIMESTAMP, Types.INTEGER, Types.DATE, Types.INTEGER, Types.INTEGER,
            Types.VARCHAR, Types.TIMESTAMP, Types.BIGINT, Types.TIMESTAMP, Types.VARCHAR, Types.VARCHAR
    };

    private final JdbcTemplate jdbcTemplate;

    /** Where a submission's reading is stored: a correction can move it to another scheme or day. */
    public record SchemeDay(int schemeId, LocalDate readingDate) {
    }

    /**
     * Serialises every write to one scheme's readings and day totals for the rest of the surrounding
     * transaction. Take it before the transaction's first write.
     *
     * <p>Both writers of a day's total — a reading's recalculation and a reason event — find the
     * day's row and then update or insert it, and there is no unique constraint behind that. A
     * recalculation also reads the scheme's other readings, which a concurrent transaction may not
     * have committed yet. Telemetry publishes without a key, so once analytics runs more than one
     * consumer, a scheme's events can be processed at the same time; unguarded, two of them could
     * both insert a day, or leave it with a total worked out from readings they could not see.
     *
     * <p>Transaction-scoped, so it is released on commit or rollback with no unlock call. Outside a
     * transaction it would be released immediately and guard nothing, so calling it without one
     * fails instead.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockScheme(Integer tenantId, Integer schemeId) {
        acquireSchemeLock(schemeLockKey(tenantId, schemeId));
    }

    /**
     * {@link #lockScheme} for each of the tenant's {@code schemeIds}, taken in ascending key order: two
     * transactions locking the same schemes then queue on the first one instead of deadlocking.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockSchemes(Integer tenantId, Collection<Integer> schemeIds) {
        schemeIds.stream()
                .map(schemeId -> schemeLockKey(tenantId, schemeId))
                .distinct()
                .sorted()
                .forEach(this::acquireSchemeLock);
    }

    /** Two schemes whose keys collide only make one of them wait. */
    private static int schemeLockKey(Integer tenantId, Integer schemeId) {
        return Objects.hash(tenantId, schemeId);
    }

    private void acquireSchemeLock(int key) {
        // Two int keys rather than one bigint, so the namespace keeps these apart from other locks.
        jdbcTemplate.query("SELECT pg_advisory_xact_lock(?, ?)",
                ps -> {
                    ps.setInt(1, SCHEME_LOCK_NAMESPACE);
                    ps.setInt(2, key);
                },
                rs -> null);
    }

    /**
     * The scheme and day the submission is stored under. Read with SQL rather than through the
     * entity, so a call made after {@link #lockSchemes} sees what other transactions committed before
     * the lock was granted, not a copy already cached in this transaction.
     *
     * @return empty when the submission has no row yet
     */
    public Optional<SchemeDay> findSchemeDay(Integer tenantId, Long sourceReadingId) {
        return jdbcTemplate.query("""
                        SELECT scheme_id, reading_date FROM analytics_schema.fact_meter_reading_table
                        WHERE tenant_id = ? AND source_reading_id = ?
                        """,
                (rs, rowNum) -> new SchemeDay(rs.getInt("scheme_id"), rs.getObject("reading_date", LocalDate.class)),
                tenantId, sourceReadingId)
                .stream()
                .findFirst();
    }

    /**
     * Stores one submission's reading, keeping one row per submission.
     *
     * <p>A reading with a {@code sourceReadingId} is inserted, or overwrites the row already held for
     * that {@code (tenantId, sourceReadingId)} — but only when its {@code sourceUpdatedAt} is not older
     * than the stored one, so a replay of the same version is harmless and an out-of-order older
     * version is dropped. A reading without one (from an older telemetry-service) is always inserted.
     *
     * @return the stored row's id, or empty when the reading is older than the stored version
     */
    public Optional<Long> upsertMeterReading(FactMeterReading reading) {
        Object[] args = {
                reading.getTenantId(),
                reading.getSchemeId(),
                reading.getUserId(),
                reading.getExtractedReading(),
                reading.getConfirmedReading(),
                reading.getConfidence(),
                reading.getImageUrl(),
                reading.getReadingAt(),
                reading.getChannel(),
                reading.getReadingDate(),
                reading.getSubmissionStatus(),
                reading.getReadingType(),
                reading.getCorrelationId(),
                reading.getCreatedAt(),
                reading.getSourceReadingId(),
                reading.getSourceUpdatedAt(),
                toJson(reading.getCalculationParameters()),
                reading.getSubmittedUnit()
        };
        List<Long> ids = jdbcTemplate.query(UPSERT_METER_READING_SQL, args, UPSERT_METER_READING_TYPES,
                (rs, rowNum) -> rs.getLong(1));
        return ids.stream().findFirst();
    }

    private static String toJson(CalculationParameters parameters) {
        if (parameters == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(parameters);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise calculation parameters", e);
        }
    }
}
