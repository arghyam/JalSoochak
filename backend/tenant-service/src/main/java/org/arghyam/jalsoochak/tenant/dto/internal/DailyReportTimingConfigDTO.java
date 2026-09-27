package org.arghyam.jalsoochak.tenant.dto.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for the Daily Water Service Situation Report schedule.
 *
 * <p>Shape: {@code {"dailyReport":{"schedule":{"hour":16,"minute":0}}}}. The report covers the same
 * day from 00:00 up to this time, so moving the schedule moves the window it reports on — the PDF's
 * "Reporting Period" line is rendered from this value rather than hard-coded.</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public final class DailyReportTimingConfigDTO implements ConfigValueDTO {

    @NotNull(message = "Daily report configuration is required")
    @Valid
    private DailyReport dailyReport;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class DailyReport {
        @NotNull(message = "Schedule is required")
        @Valid
        private ScheduleConfigDTO schedule;
    }
}
