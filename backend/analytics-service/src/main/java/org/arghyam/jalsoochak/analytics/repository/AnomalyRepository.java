package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.dto.response.AnomalyListItemDto;
import org.arghyam.jalsoochak.analytics.entity.Anomaly;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;

@Repository
public interface AnomalyRepository extends JpaRepository<Anomaly, Long> {

    boolean existsByCorrelationIdAndTypeAndSchemeIdAndTenantId(
            String correlationId, String type, Integer schemeId, Integer tenantId);

    @Query(
            value = """
                    SELECT new org.arghyam.jalsoochak.analytics.dto.response.AnomalyListItemDto(
                        a.id, a.uuid, a.type, a.userId, a.schemeId, a.tenantId, a.aiReading, a.aiConfidencePercentage,
                        a.overriddenReading, a.retries, a.previousReading, a.previousReadingDate, a.consecutiveDaysMissed,
                        a.reason, a.status, a.remarks, a.correlationId, a.resolvedBy, a.resolvedAt, a.deletedAt, a.deletedBy,
                        a.createdAt, a.updatedAt, s.schemeName)
                    FROM Anomaly a, DimUserSchemeMapping m, DimScheme s
                    WHERE a.tenantId = :tenantId
                      AND m.schemeId = a.schemeId AND m.userId = :mappedUserId
                      AND s.schemeId = a.schemeId
                      AND a.deletedAt IS NULL
                      AND a.createdAt >= :fromInclusive
                      AND a.createdAt < :toExclusive
                      AND (:anomalyType = '' OR a.type = :anomalyType)
                      AND (:schemeName = '' OR lower(s.schemeName) like lower(concat('%', :schemeName, '%')))
                      AND (:status = -1 OR a.status = :status)
                    """,
            countQuery = """
                    SELECT count(distinct a.id)
                    FROM Anomaly a, DimUserSchemeMapping m, DimScheme s
                    WHERE a.tenantId = :tenantId
                      AND m.schemeId = a.schemeId AND m.userId = :mappedUserId
                      AND s.schemeId = a.schemeId
                      AND a.deletedAt IS NULL
                      AND a.createdAt >= :fromInclusive
                      AND a.createdAt < :toExclusive
                      AND (:anomalyType = '' OR a.type = :anomalyType)
                      AND (:schemeName = '' OR lower(s.schemeName) like lower(concat('%', :schemeName, '%')))
                      AND (:status = -1 OR a.status = :status)
                    """)
    Page<AnomalyListItemDto> findAnomaliesForMappedUserSchemesInRange(
            @Param("tenantId") Integer tenantId,
            @Param("mappedUserId") Integer mappedUserId,
            @Param("fromInclusive") OffsetDateTime fromInclusive,
            @Param("toExclusive") OffsetDateTime toExclusive,
            @Param("anomalyType") String anomalyType,
            @Param("schemeName") String schemeName,
            @Param("status") int status,
            Pageable pageable
    );

    @Query("""
            SELECT count(distinct a.id)
            FROM Anomaly a, DimUserSchemeMapping m
            WHERE a.tenantId = :tenantId
              AND m.schemeId = a.schemeId AND m.userId = :mappedUserId
              AND a.deletedAt IS NULL
              AND a.createdAt >= :fromInclusive
              AND a.createdAt < :toExclusive
            """)
    long countAnomaliesForMappedUserSchemesInRange(
            @Param("tenantId") Integer tenantId,
            @Param("mappedUserId") Integer mappedUserId,
            @Param("fromInclusive") OffsetDateTime fromInclusive,
            @Param("toExclusive") OffsetDateTime toExclusive
    );
}
