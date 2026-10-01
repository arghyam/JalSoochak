package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.FactWaterQuantity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface FactWaterQuantityRepository extends JpaRepository<FactWaterQuantity, Long> {

    List<FactWaterQuantity> findByTenantId(Integer tenantId);

    List<FactWaterQuantity> findBySchemeId(Integer schemeId);

    List<FactWaterQuantity> findByTenantIdAndDateBetween(Integer tenantId, LocalDate startDate, LocalDate endDate);

    List<FactWaterQuantity> findBySchemeIdAndDateBetween(Integer schemeId, LocalDate startDate, LocalDate endDate);

    Optional<FactWaterQuantity> findTopByTenantIdAndSchemeIdAndDateOrderByUpdatedAtDescIdDesc(
            Integer tenantId,
            Integer schemeId,
            LocalDate date
    );

    /**
     * Removes the day's total that was worked out from readings, for a day that can no longer be
     * calculated.
     *
     * <p>A row holding an outage, non-submission or meter-change reason is kept: the reason events
     * always set {@code outage_reason} or {@code non_submission_reason}, so a row with both NULL can
     * only have come from the reading path. Every such row of the day goes, duplicates included.
     *
     * @return the number of rows removed
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            DELETE FROM FactWaterQuantity w
            WHERE w.tenantId = :tenantId
              AND w.schemeId = :schemeId
              AND w.date = :date
              AND w.outageReason IS NULL
              AND w.nonSubmissionReason IS NULL
            """)
    int deleteReadingDerivedDay(
            @Param("tenantId") Integer tenantId,
            @Param("schemeId") Integer schemeId,
            @Param("date") LocalDate date
    );
}
