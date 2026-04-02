package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.entity.Anomaly;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface AnomalyRepository extends JpaRepository<Anomaly, Long> {

    boolean existsByCorrelationIdAndTypeAndSchemeIdAndTenantId(
            String correlationId, Integer type, Integer schemeId, Integer tenantId);

    @Query(value = """
            SELECT a.*
            FROM analytics_schema.anomaly_table a
            INNER JOIN analytics_schema.dim_user_scheme_mapping_table m
                ON m.scheme_id = a.scheme_id
            WHERE m.user_id = :mappedUserId
              AND a.deleted_at IS NULL
              AND a.created_at >= :fromInclusive
              AND a.created_at < :toExclusive
              AND (:anomalyType IS NULL OR a.type = :anomalyType)
            ORDER BY a.created_at DESC
            """, nativeQuery = true)
    List<Anomaly> findAnomaliesForMappedUserSchemesInRange(
            @Param("mappedUserId") Integer mappedUserId,
            @Param("fromInclusive") OffsetDateTime fromInclusive,
            @Param("toExclusive") OffsetDateTime toExclusive,
            @Param("anomalyType") Integer anomalyType
    );
}
