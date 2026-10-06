package org.arghyam.jalsoochak.tenant.dto.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;

/**
 * DTO for pump operator reminder nudge configuration.
 * Defines the schedule time at which reminder notifications are sent
 * if meter reading has not been submitted.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public final class NudgeTimingConfigDTO implements ConfigValueDTO {
    
    @NotNull(message = "Nudge configuration is required")
    @Valid
    private Nudge nudge;

    /**
     * Range-checks the schedule via {@link ScheduleConfigDTO#validateRanges(String)}. Null-tolerant:
     * an absent {@code nudge}, {@code schedule} or field means "use the application default".
     *
     * @throws InvalidConfigValueException if an explicitly supplied field is out of range
     */
    public void validateSchedule() {
        ScheduleConfigDTO schedule = nudge == null ? null : nudge.getSchedule();
        if (schedule != null) {
            schedule.validateRanges("PUMP_OPERATOR_REMINDER_NUDGE_TIME");
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Nudge {
        @NotNull(message = "Schedule is required")
        @Valid
        private ScheduleConfigDTO schedule;
    }
}
