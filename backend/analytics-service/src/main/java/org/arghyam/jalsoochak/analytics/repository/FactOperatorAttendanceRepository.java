package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.dto.response.OperatorAttendanceDayItemDto;
import org.arghyam.jalsoochak.analytics.entity.FactOperatorAttendance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface FactOperatorAttendanceRepository extends JpaRepository<FactOperatorAttendance, Long> {

    @Query("""
            SELECT new org.arghyam.jalsoochak.analytics.dto.response.OperatorAttendanceDayItemDto(
                d.fullDate, oa.attendance)
            FROM FactOperatorAttendance oa, DimDate d, DimUser u
            WHERE oa.dateKey = d.dateKey
              AND oa.userId = u.userId
              AND u.uuid = :userUuid
              AND d.fullDate >= :startDate
              AND d.fullDate <= :endDate
            ORDER BY d.fullDate ASC, oa.schemeId ASC
            """)
    List<OperatorAttendanceDayItemDto> findDayWiseByUserUuidAndDateRange(
            @Param("userUuid") UUID userUuid,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate
    );

    boolean existsByTenantIdAndSchemeIdAndUserIdAndDateKey(
            Integer tenantId,
            Integer schemeId,
            Integer userId,
            Integer dateKey
    );
}
