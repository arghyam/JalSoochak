package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.FactMeterReading;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface FactMeterReadingRepository extends JpaRepository<FactMeterReading, Long> {

    List<FactMeterReading> findByTenantIdAndSchemeIdAndReadingDateBetween(
            Integer tenantId,
            Integer schemeId,
            LocalDate startDate,
            LocalDate endDate
    );

    /**
     * The day's reading: the last row recorded on {@code readingDate}.
     *
     * <p>The {@code id} tiebreak matters. A correction re-publishes with the <em>original</em>
     * {@code readingAt}, so a corrected day holds two rows with identical timestamps; ordering by
     * {@code readingAt} alone would pick between them arbitrarily. Highest id is the later write.
     */
    Optional<FactMeterReading> findTopByTenantIdAndSchemeIdAndReadingDateOrderByReadingAtDescIdDesc(
            Integer tenantId,
            Integer schemeId,
            LocalDate readingDate
    );

    /**
     * The latest reading strictly <em>before</em> {@code readingDate} — the baseline a cumulative
     * meter's daily delta is measured against.
     *
     * <p>Same rule telemetry-service's own correction paths already apply, so both services derive
     * the same volume from the same readings. Two details carry weight:
     *
     * <ul>
     *   <li><strong>Strictly before, not "the previous calendar day".</strong> After a gap the
     *       baseline is the last actual reading, so the catch-up day carries the volume accumulated
     *       across the gap rather than being measured against a day that has no reading.</li>
     *   <li><strong>{@code confirmedReading > 0}.</strong> {@code resetLatestConfirmedReadingByPhone}
     *       in telemetry-service writes genuine {@code 0} readings; letting one become the baseline
     *       would make the next day's delta the entire cumulative meter index.</li>
     * </ul>
     *
     * <p>Prefer the {@link #findLatestBefore(Integer, Integer, LocalDate)} overload — this one exists
     * only to carry the {@code LIMIT}.
     */
    @Query("""
            SELECT r FROM FactMeterReading r
            WHERE r.tenantId = :tenantId
              AND r.schemeId = :schemeId
              AND r.readingDate < :readingDate
              AND r.confirmedReading > 0
            ORDER BY r.readingDate DESC, r.readingAt DESC, r.id DESC
            """)
    List<FactMeterReading> findLatestBefore(
            @Param("tenantId") Integer tenantId,
            @Param("schemeId") Integer schemeId,
            @Param("readingDate") LocalDate readingDate,
            Pageable pageable
    );

    /** @see #findLatestBefore(Integer, Integer, LocalDate, Pageable) */
    default Optional<FactMeterReading> findLatestBefore(
            Integer tenantId, Integer schemeId, LocalDate readingDate) {
        return findLatestBefore(tenantId, schemeId, readingDate, PageRequest.ofSize(1))
                .stream()
                .findFirst();
    }
}
