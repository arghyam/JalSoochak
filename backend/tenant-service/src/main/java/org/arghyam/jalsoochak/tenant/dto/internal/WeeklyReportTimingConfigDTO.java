package org.arghyam.jalsoochak.tenant.dto.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for the Weekly Water Service Situation Report schedule, shared by the Section Officer and
 * Sub-Divisional Officer variants.
 *
 * <p>Shape: {@code {"weeklyReport":{"schedule":{"dayOfWeek":1,"hour":9,"minute":0}}}}, reusing
 * {@link ScheduleConfigDTO}'s optional {@code dayOfWeek} (0–7, where both 0 and 7 are Sunday).</p>
 *
 * <p>Unlike the daily report, moving this schedule does <em>not</em> move the reported window: the
 * report always covers the last complete Monday–Sunday week before it runs. Scheduling it off a
 * Monday only delays delivery.</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public final class WeeklyReportTimingConfigDTO implements ConfigValueDTO {

    @NotNull(message = "Weekly report configuration is required")
    @Valid
    private WeeklyReport weeklyReport;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class WeeklyReport {
        @NotNull(message = "Schedule is required")
        @Valid
        private ScheduleConfigDTO schedule;
    }
}
