package org.arghyam.jalsoochak.analytics.service;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.dto.response.AnomalyListItemDto;
import org.arghyam.jalsoochak.analytics.repository.AnomalyRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AnomalyQueryService {

    private final AnomalyRepository anomalyRepository;

    public Page<AnomalyListItemDto> getAnomaliesForUserSchemes(
            Integer tenantId,
            Integer mappedUserId,
            LocalDate startDate,
            LocalDate endDate,
            String anomalyType,
            String schemeName,
            Integer status,
            Pageable pageable
    ) {
        LocalDate safeEndDate = (endDate != null) ? endDate : LocalDate.now();
        LocalDate safeStartDate = (startDate != null) ? startDate : safeEndDate.minusDays(30);

        // anomaly_table.created_at is plain TIMESTAMP holding UTC, so use UTC-naive LocalDateTime bounds.
        LocalDateTime from = safeStartDate.atStartOfDay();
        LocalDateTime to = safeEndDate.plusDays(1).atStartOfDay(); // exclusive upper bound

        // Empty string (never null) avoids PostgreSQL 42P18 on untyped NULL JDBC parameters for :anomalyType.
        String typeFilter = (anomalyType == null || anomalyType.isBlank()) ? "" : anomalyType.trim();
        String schemeNameFilter = (schemeName == null || schemeName.isBlank()) ? "" : schemeName.trim();
        int statusFilter = (status == null) ? -1 : status;

        return anomalyRepository.findAnomaliesForMappedUserSchemesInRange(
                tenantId,
                mappedUserId,
                from,
                to,
                typeFilter,
                schemeNameFilter,
                statusFilter,
                pageable
        );
    }
}

