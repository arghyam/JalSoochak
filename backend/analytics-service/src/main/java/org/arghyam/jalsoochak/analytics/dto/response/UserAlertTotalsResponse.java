package org.arghyam.jalsoochak.analytics.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserAlertTotalsResponse {
    private long totalEscalationCount;
    private long totalAnomalyCount;
    private int totalMappedSchemeCount;
    private long totalWaterSupplied;
}

