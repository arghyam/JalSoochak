package org.arghyam.jalsoochak.analytics.service;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.constant.EscalationType;
import org.arghyam.jalsoochak.analytics.entity.Anomaly;
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

    public List<Anomaly> getAnomaliesForUserSchemes(
            Integer mappedUserId,
            LocalDate startDate,
            LocalDate endDate,
            String anomalyType
    ) {
        LocalDate safeEndDate = (endDate != null) ? endDate : LocalDate.now();
        LocalDate safeStartDate = (startDate != null) ? startDate : safeEndDate.minusDays(30);

        OffsetDateTime from = safeStartDate.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to = safeEndDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC); // exclusive upper bound

        String normalizedType = normalizeTypeFilter(anomalyType);

        return anomalyRepository.findAnomaliesForMappedUserSchemesInRange(
                mappedUserId,
                from,
                to,
                normalizedType
        );
    }

    private static String normalizeTypeFilter(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        if (trimmed.matches("^\\d+$")) {
            String mapped = EscalationType.toDbType(Integer.parseInt(trimmed));
            return mapped != null ? mapped : trimmed;
        }
        return EscalationType.normalizeDbType(trimmed);
    }
}
