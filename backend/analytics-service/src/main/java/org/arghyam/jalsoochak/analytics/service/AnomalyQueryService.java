package org.arghyam.jalsoochak.analytics.service;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.dto.response.AnomalyListItemDto;
import org.arghyam.jalsoochak.analytics.repository.AnomalyRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

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
            Pageable pageable
    ) {
        LocalDate safeEndDate = (endDate != null) ? endDate : LocalDate.now();
        LocalDate safeStartDate = (startDate != null) ? startDate : safeEndDate.minusDays(30);

        OffsetDateTime from = safeStartDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = safeEndDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC); // exclusive upper bound

        // Empty string (never null) avoids PostgreSQL 42P18 on untyped NULL JDBC parameters for :anomalyType.
        String typeFilter = (anomalyType == null || anomalyType.isBlank()) ? "" : anomalyType.trim();
        String schemeNameFilter = (schemeName == null || schemeName.isBlank()) ? "" : schemeName.trim();

        return anomalyRepository.findAnomaliesForMappedUserSchemesInRange(
                tenantId,
                mappedUserId,
                from,
                to,
                typeFilter,
                schemeNameFilter,
                pageable
        );
    }
}

