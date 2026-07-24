package org.arghyam.jalsoochak.analytics.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Hourly reading-submission activity for a tenant (optionally scoped to one LGD or
 * department region) over a date range. Each bucket is one hour.
 *
 * <p>{@code submissionCount} is additive; {@code distinctSchemeCount} is a per-hour
 * figure and must NOT be summed across hours in the UI (a scheme can submit in several
 * hours). For a range-wide distinct total, use the daily-grain KPIs instead.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HourlySubmissionActivityResponse {

    private Integer tenantId;
    private Integer lgdId;
    private Integer departmentId;
    private LocalDate startDate;
    private LocalDate endDate;
    private List<HourlyBucket> hourlyActivity;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HourlyBucket {
        /** Start of the hour (IST, matching how readings are stored), e.g. 2026-01-01T09:00:00. */
        @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
        private LocalDateTime hourStart;
        private long submissionCount;
        private int distinctSchemeCount;
    }
}
