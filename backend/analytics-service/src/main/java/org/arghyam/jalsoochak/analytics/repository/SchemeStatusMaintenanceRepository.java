package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.DimScheme;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface SchemeStatusMaintenanceRepository extends JpaRepository<DimScheme, Integer> {

    @Query(value = """
            SELECT ds.scheme_id AS schemeId,
                   MAX(fwq.date) AS lastSubmittedDate
            FROM analytics_schema.dim_scheme_table ds
            LEFT JOIN analytics_schema.fact_water_quantity_table fwq
                   ON fwq.scheme_id = ds.scheme_id
                  AND fwq.submission_status = :submittedStatus
            GROUP BY ds.scheme_id
            """, nativeQuery = true)
    List<SchemeLastSubmissionProjection> findLastSubmittedDateByScheme(
            @Param("submittedStatus") Integer submittedStatus);

    @Modifying
    @Query(value = """
            UPDATE analytics_schema.dim_scheme_table
            SET operating_status = :statusCode,
                updated_at = CURRENT_TIMESTAMP
            WHERE scheme_id IN (:schemeIds)
            """, nativeQuery = true)
    int updateSchemeStatusBySchemeIds(
            @Param("statusCode") Integer statusCode,
            @Param("schemeIds") Collection<Integer> schemeIds);

    interface SchemeLastSubmissionProjection {
        Integer getSchemeId();

        LocalDate getLastSubmittedDate();
    }
}
