package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.dto.response.AnomalyListItemDto;
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
            String correlationId, String type, Integer schemeId, Integer tenantId);

    @Query("""
            SELECT new org.arghyam.jalsoochak.analytics.dto.response.AnomalyListItemDto(
                a.id, a.uuid, a.type, a.userId, a.schemeId, a.tenantId, a.aiReading, a.aiConfidencePercentage,
                a.overriddenReading, a.retries, a.previousReading, a.previousReadingDate, a.consecutiveDaysMissed,
                a.reason, a.status, a.remarks, a.correlationId, a.resolvedBy, a.resolvedAt, a.deletedAt, a.deletedBy,
                a.createdAt, a.updatedAt, s.schemeName)
            FROM Anomaly a
            INNER JOIN DimUserSchemeMapping m ON m.schemeId = a.schemeId AND m.userId = :mappedUserId
            INNER JOIN DimScheme s ON s.schemeId = a.schemeId
            WHERE a.deletedAt IS NULL
              AND a.createdAt >= :fromInclusive
              AND a.createdAt < :toExclusive
              AND (:anomalyType IS NULL OR a.type = :anomalyType)
            ORDER BY a.createdAt DESC
            """)
    List<AnomalyListItemDto> findAnomaliesForMappedUserSchemesInRange(
            @Param("mappedUserId") Integer mappedUserId,
            @Param("fromInclusive") OffsetDateTime fromInclusive,
            @Param("toExclusive") OffsetDateTime toExclusive,
            @Param("anomalyType") String anomalyType
    );
}
