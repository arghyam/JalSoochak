package org.arghyam.jalsoochak.tenant.dto.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;

/**
 * DTO for the Weekly Water Service Situation Report schedule, shared by the Section Officer and
 * Sub-Divisional Officer variants.
 *
 * <p>Shape:
 * {@code {"weeklyReport":{"schedule":{"dayOfWeek":1,"hour":9,"minute":0},"weekStartDay":1}}}, reusing
 * {@link ScheduleConfigDTO}'s optional {@code dayOfWeek} (0–7, where both 0 and 7 are Sunday).</p>
 *
 * <p>{@code schedule} says when the job fires; {@code weekStartDay} says which seven days it reports
 * on. It sits beside {@code schedule} rather than inside it because {@link ScheduleConfigDTO} is shared
 * with the nudge, escalation and daily-report keys, and this is a reporting-window setting, not a cron
 * field. Omitting it reports a Monday–Sunday week.</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public final class WeeklyReportTimingConfigDTO implements ConfigValueDTO {

    @NotNull(message = "Weekly report configuration is required")
    @Valid
    private WeeklyReport weeklyReport;

    /**
     * Range-checks {@code weekStartDay} explicitly.
     *
     * <p>The bean-validation annotations below do not run on this key's write path, which binds with
     * {@code ObjectMapper.treeToValue}. Without this the value would commit, and the reschedule that
     * follows the commit would then fail — persisting config that unschedules the tenant on next
     * startup. Mirrors {@code RegularityThresholdConfigDTO.validatedThresholdPercent()}.</p>
     *
     * @return the configured value, or null to mean "use the application default"
     * @throws InvalidConfigValueException if the value is outside 0–7
     */
    public Integer validatedWeekStartDay() {
        Integer value = weeklyReport == null ? null : weeklyReport.getWeekStartDay();
        if (value != null && (value < 0 || value > 7)) {
            throw new InvalidConfigValueException(
                    "Invalid weekStartDay '" + value + "' in WEEKLY_SITUATION_REPORT_TIME"
                            + " (must be between 0 and 7, where both 0 and 7 mean Sunday)");
        }
        return value;
    }

    /**
     * Range-checks the three cron fields the weekly schedule actually uses, for the same reason as
     * {@link #validatedWeekStartDay()}.
     *
     * <p>{@code TenantSchedulerManager.validateScheduleConfig} rejects an out-of-range hour, minute or
     * dayOfWeek exactly as it rejects an out-of-range weekStartDay — so a value that commits here
     * survives the write, then throws on every later {@code scheduleForTenant}, leaving the tenant with
     * no nudge, escalation, daily-report or weekly-report job from the next startup onwards.</p>
     *
     * <p>Null-tolerant throughout: an absent {@code weeklyReport}, {@code schedule} or individual field
     * means "use the application default" and is left for {@code TenantConfigService} to fill in.</p>
     *
     * @throws InvalidConfigValueException if an explicitly supplied field is out of range
     */
    public void validateSchedule() {
        ScheduleConfigDTO schedule = weeklyReport == null ? null : weeklyReport.getSchedule();
        if (schedule == null) {
            return;
        }
        checkRange(schedule.getDayOfWeek(), 0, 7,
                "dayOfWeek", "(must be between 0 and 7, where both 0 and 7 mean Sunday)");
        checkRange(schedule.getHour(), 0, 23, "hour", "(must be between 0 and 23)");
        checkRange(schedule.getMinute(), 0, 59, "minute", "(must be between 0 and 59)");
    }

    private static void checkRange(Integer value, int min, int max, String field, String bounds) {
        if (value != null && (value < min || value > max)) {
            throw new InvalidConfigValueException(
                    "Invalid " + field + " '" + value + "' in WEEKLY_SITUATION_REPORT_TIME " + bounds);
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class WeeklyReport {
        @NotNull(message = "Schedule is required")
        @Valid
        private ScheduleConfigDTO schedule;

        /**
         * Day the reported week begins on: 0–7, where both 0 and 7 are Sunday. Null means the
         * application default (Monday), not "every day".
         */
        @Min(value = 0, message = "Week start day must be between 0 and 7")
        @Max(value = 7, message = "Week start day must be between 0 and 7")
        private Integer weekStartDay;
    }
}
