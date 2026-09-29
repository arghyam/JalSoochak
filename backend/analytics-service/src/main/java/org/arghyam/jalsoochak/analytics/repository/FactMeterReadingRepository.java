package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.FactMeterReading;
import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Meter-reading facts. The channel-filtered lookups count a legacy {@code NULL} channel as BFM
 * ({@link ReadingChannel#DEFAULT}, code 1), as {@link ReadingChannel#fromCode(Integer)} does.
 */
@Repository
public interface FactMeterReadingRepository extends JpaRepository<FactMeterReading, Long> {

    List<FactMeterReading> findByTenantIdAndSchemeIdAndReadingDateBetween(
            Integer tenantId,
            Integer schemeId,
            LocalDate startDate,
            LocalDate endDate
    );

    /**
     * The day's latest reading, on any channel: the last row recorded on {@code readingDate}. Its
     * channel is the day's channel, and it is who the day is credited to.
     *
     * <p>The {@code id} tiebreak matters. A correction from an older telemetry-service re-publishes
     * with the <em>original</em> {@code readingAt} as a new row, so a corrected day can hold two rows
     * with identical timestamps; ordering by {@code readingAt} alone would pick between them
     * arbitrarily. Highest id is the later write.
     */
    Optional<FactMeterReading> findTopByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDescIdDesc(
            Integer tenantId,
            Integer schemeId,
            LocalDate readingDate
    );

    /**
     * The latest reading on {@code channel} strictly <em>before</em> {@code readingDate} — the
     * starting point a meter-index channel's daily amount is measured from.
     *
     * <p>Same rule telemetry-service's own correction paths already apply, so both services derive
     * the same volume from the same readings. Three details carry weight:
     *
     * <ul>
     *   <li><strong>Strictly before, not "the previous calendar day".</strong> After a gap the
     *       starting point is the last actual reading, so the catch-up day carries the volume
     *       accumulated across the gap rather than being measured against a day that has no reading.</li>
     *   <li><strong>{@code confirmedReading > 0}.</strong> {@code resetLatestConfirmedReadingByPhone}
     *       in telemetry-service writes genuine {@code 0} readings; letting one become the starting
     *       point would make the next day's delta the entire cumulative meter index.</li>
     *   <li><strong>Same channel only.</strong> An electricity meter's kWh is never a flow meter's
     *       starting point, or the other way round.</li>
     * </ul>
     *
     * <p>Prefer the {@link #findLatestBefore(Integer, Integer, LocalDate, ReadingChannel)} overload —
     * this one exists only to carry the {@code LIMIT}.
     */
    @Query("""
            SELECT r FROM FactMeterReading r
            WHERE r.tenantId = :tenantId
              AND r.schemeId = :schemeId
              AND r.readingDate < :readingDate
              AND COALESCE(r.channel, 1) = :channel
              AND r.confirmedReading > 0
            ORDER BY r.readingDate DESC, r.readingAt DESC, r.id DESC
            """)
    List<FactMeterReading> findLatestBefore(
            @Param("tenantId") Integer tenantId,
            @Param("schemeId") Integer schemeId,
            @Param("readingDate") LocalDate readingDate,
            @Param("channel") int channel,
            Pageable pageable
    );

    /** @see #findLatestBefore(Integer, Integer, LocalDate, int, Pageable) */
    default Optional<FactMeterReading> findLatestBefore(
            Integer tenantId, Integer schemeId, LocalDate readingDate, ReadingChannel channel) {
        return findLatestBefore(tenantId, schemeId, readingDate, channel.getCode(), PageRequest.ofSize(1))
                .stream()
                .findFirst();
    }

    /**
     * The first date after {@code readingDate} with a reading on {@code channel}: the one day whose
     * meter-index amount starts from {@code readingDate}'s reading.
     */
    @Query("""
            SELECT MIN(r.readingDate) FROM FactMeterReading r
            WHERE r.tenantId = :tenantId
              AND r.schemeId = :schemeId
              AND r.readingDate > :readingDate
              AND COALESCE(r.channel, 1) = :channel
            """)
    Optional<LocalDate> findNextReadingDate(
            @Param("tenantId") Integer tenantId,
            @Param("schemeId") Integer schemeId,
            @Param("readingDate") LocalDate readingDate,
            @Param("channel") int channel
    );

    /** Every reading on {@code channel} dated {@code readingDate}, oldest first. */
    @Query("""
            SELECT r FROM FactMeterReading r
            WHERE r.tenantId = :tenantId
              AND r.schemeId = :schemeId
              AND r.readingDate = :readingDate
              AND COALESCE(r.channel, 1) = :channel
            ORDER BY r.readingAt, r.id
            """)
    List<FactMeterReading> findDayReadings(
            @Param("tenantId") Integer tenantId,
            @Param("schemeId") Integer schemeId,
            @Param("readingDate") LocalDate readingDate,
            @Param("channel") int channel
    );
}
