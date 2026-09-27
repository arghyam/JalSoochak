package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.FactSchemePerformance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface SchemePerformanceSchedulerRepository extends JpaRepository<FactSchemePerformance, Long> {

 /*the formula for the performance score is:
    performance_score = (total_water_supplied / (fhtc_count * house_hold_count * 5 * required_lpcd))
    if total_water_supplied is 0, then performance_score is 0
    if total_water_supplied is less than the required_lpcd, then performance_score is 0.5
    if total_water_supplied is greater than or equal to the required_lpcd, then performance_score is 1.0
    assumptions: 5 persons per household
    */
    @Modifying
    @Query(value = """
            INSERT INTO analytics_schema.fact_scheme_performance_table (
                scheme_id,
                tenant_id,
                performance_score,
                last_water_supply_date,
                created_at,
                updated_at
            )
            SELECT ds.scheme_id,
                   ds.tenant_id,
                   CASE
                       WHEN COALESCE(supply.total_water_supplied, 0) <= 0 THEN 0.0
                       WHEN COALESCE(supply.total_water_supplied, 0) <
                            (
   
                                COALESCE(ds.fhtc_count, 0) * COALESCE(ds.house_hold_count, 0) * 5
                                * COALESCE(dt.required_lpcd, 0)
                            ) THEN 0.5
                       ELSE 1.0
                   END AS performance_score,
                   :targetDate,
                   CURRENT_TIMESTAMP,
                   CURRENT_TIMESTAMP
            FROM analytics_schema.dim_scheme_table ds
            JOIN analytics_schema.dim_tenant_table dt
              ON dt.tenant_id = ds.tenant_id
            LEFT JOIN (
                -- De-duplicate to the latest fact_water_quantity row per (tenant, scheme, date) before
                -- summing, so a stray duplicate row can never double-count the day's volume. The table has
                -- no uniqueness on (tenant_id, scheme_id, date); ingestion keeps the current row via
                -- find-latest-and-update, ordered by updated_at DESC, id DESC (DISTINCT ON mirrors that).
                -- NOTE: this intentionally sums ALL submission statuses, unlike the dashboard's "supplied
                -- water" definition (SUBMITTED or NULL status, water_quantity > 0). Left as-is so daily
                -- performance scores do not silently shift; revisit with product if the two must agree.
                SELECT tenant_id,
                       scheme_id,
                       SUM(water_quantity) AS total_water_supplied
                FROM (
                    SELECT DISTINCT ON (fwq.tenant_id, fwq.scheme_id, fwq.date)
                           fwq.tenant_id, fwq.scheme_id, fwq.water_quantity
                    FROM analytics_schema.fact_water_quantity_table fwq
                    WHERE fwq.date = :targetDate
                    ORDER BY fwq.tenant_id, fwq.scheme_id, fwq.date, fwq.updated_at DESC, fwq.id DESC
                ) latest
                GROUP BY tenant_id, scheme_id
            ) supply
              ON supply.tenant_id = ds.tenant_id
             AND supply.scheme_id = ds.scheme_id
            -- Every scheme in the dimension is scored, deliberately. This once carried
            -- `WHERE ds.operating_status > 0`, a leftover of the retired active/inactive binary: it
            -- skipped Non-Operative schemes, which are precisely the ones that supply no water and
            -- score 0.0. With no row written they fell out of the AVG in
            -- SchemeRegularityRepository's performance-score queries entirely, inflating the average
            -- by hiding the failures. Scope is the read side's job -- those queries already apply the
            -- {{WS}} work-status policy -- so do not reintroduce a filter here.
            WHERE NOT EXISTS (
                  SELECT 1
                  FROM analytics_schema.fact_scheme_performance_table fp
                  WHERE fp.scheme_id = ds.scheme_id
                    AND fp.tenant_id = ds.tenant_id
                    AND fp.last_water_supply_date = :targetDate
              )
            """, nativeQuery = true)
    int insertDailySchemePerformanceScores(@Param("targetDate") LocalDate targetDate);
}
