package org.arghyam.jalsoochak.tenant.dto.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;

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

    /**
     * Range-checks the schedule via {@link ScheduleConfigDTO#validateRanges(String)}. Null-tolerant:
     * an absent {@code dailyReport}, {@code schedule} or field means "use the application default".
     *
     * @throws InvalidConfigValueException if an explicitly supplied field is out of range
     */
    public void validateSchedule() {
        ScheduleConfigDTO schedule = dailyReport == null ? null : dailyReport.getSchedule();
        if (schedule != null) {
            schedule.validateRanges("DAILY_SITUATION_REPORT_TIME");
        }
    }

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
