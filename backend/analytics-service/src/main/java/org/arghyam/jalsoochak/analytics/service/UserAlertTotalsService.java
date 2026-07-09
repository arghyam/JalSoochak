package org.arghyam.jalsoochak.analytics.service;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.dto.response.UserAlertTotalsResponse;
import org.arghyam.jalsoochak.analytics.repository.AnomalyRepository;
import org.arghyam.jalsoochak.analytics.repository.SchemeRegularityRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserAlertTotalsService {

    private final EscalationQueryService escalationQueryService;
    private final AnomalyRepository anomalyRepository;
    private final SchemeRegularityRepository schemeRegularityRepository;

    public UserAlertTotalsResponse getTotals(
            Integer tenantId,
            Integer userId,
            LocalDate startDate,
            LocalDate endDate
    ) {
        LocalDate safeEndDate = (endDate != null) ? endDate : LocalDate.now();
        LocalDate safeStartDate = (startDate != null) ? startDate : safeEndDate.minusDays(30);

        if (safeStartDate.isAfter(safeEndDate)) {
            throw new IllegalArgumentException("start_date must be on or before end_date");
        }

        long totalEscalationCount = escalationQueryService.countEscalations(tenantId, userId, safeStartDate, safeEndDate);

        // anomaly_table.created_at is plain TIMESTAMP holding UTC, so use UTC-naive LocalDateTime bounds.
        LocalDateTime from = safeStartDate.atStartOfDay();
        LocalDateTime to = safeEndDate.plusDays(1).atStartOfDay();
        long totalAnomalyCount = anomalyRepository.countAnomaliesForMappedUserSchemesInRange(tenantId, userId, from, to);

        int totalMappedSchemeCount = schemeRegularityRepository.getSchemeCountByUser(tenantId, userId);
        long totalWaterSupplied = schemeRegularityRepository.getTotalWaterSuppliedByUserSchemes(
                tenantId, userId, safeStartDate, safeEndDate
        );

        return UserAlertTotalsResponse.builder()
                .totalEscalationCount(totalEscalationCount)
                .totalAnomalyCount(totalAnomalyCount)
                .totalMappedSchemeCount(totalMappedSchemeCount)
                .totalWaterSupplied(totalWaterSupplied)
                .build();
    }
}

