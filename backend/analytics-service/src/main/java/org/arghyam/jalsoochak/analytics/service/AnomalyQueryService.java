package org.arghyam.jalsoochak.analytics.service;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.dto.response.AnomalyListItemDto;
import org.arghyam.jalsoochak.analytics.repository.AnomalyRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AnomalyQueryService {

    private final AnomalyRepository anomalyRepository;

    public List<AnomalyListItemDto> getAnomaliesForUserSchemes(
            Integer mappedUserId,
            LocalDate startDate,
            LocalDate endDate,
            String anomalyType
    ) {
        LocalDate safeEndDate = (endDate != null) ? endDate : LocalDate.now();
        LocalDate safeStartDate = (startDate != null) ? startDate : safeEndDate.minusDays(30);

        OffsetDateTime from = safeStartDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = safeEndDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC); // exclusive upper bound

        String typeFilter = (anomalyType == null || anomalyType.isBlank()) ? null : anomalyType.trim();

        return anomalyRepository.findAnomaliesForMappedUserSchemesInRange(
                mappedUserId,
                from,
                to,
                typeFilter
        );
    }
}

