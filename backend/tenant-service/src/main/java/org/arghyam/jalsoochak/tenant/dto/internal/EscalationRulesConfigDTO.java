package org.arghyam.jalsoochak.tenant.dto.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;

/**
 * DTO for field staff escalation rules configuration.
 * Defines escalation schedule and multi-level escalation rules based on thresholds.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public final class EscalationRulesConfigDTO implements ConfigValueDTO {
    
    @NotNull(message = "Escalation configuration is required")
    @Valid
    private Escalation escalation;

    /**
     * Range-checks the schedule via {@link ScheduleConfigDTO#validateRanges(String)}. Null-tolerant:
     * an absent {@code escalation}, {@code schedule} or field means "use the application default".
     *
     * @throws InvalidConfigValueException if an explicitly supplied field is out of range
     */
    public void validateSchedule() {
        ScheduleConfigDTO schedule = escalation == null ? null : escalation.getSchedule();
        if (schedule != null) {
            schedule.validateRanges("FIELD_STAFF_ESCALATION_RULES");
        }
    }

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Escalation {
        @NotNull(message = "Schedule is required")
        @Valid
        private ScheduleConfigDTO schedule;
        
        @NotNull(message = "Level 1 escalation rule is required")
        @Valid
        private EscalationLevel level1;
        
        @NotNull(message = "Level 2 escalation rule is required")
        @Valid
        private EscalationLevel level2;
    }
    
    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class EscalationLevel {
        @NotNull(message = "Threshold is required for escalation level")
        @Valid
        private Threshold threshold;
        
        @NotNull(message = "Officer details are required for escalation level")
        @Valid
        private Officer officer;
        
        @Data
        @Builder
        @AllArgsConstructor
        @NoArgsConstructor
        public static class Threshold {
            @NotNull(message = "Days threshold is required")
            @Min(value = 1, message = "Days must be at least 1")
            private Integer days;
        }
        
        @Data
        @Builder
        @AllArgsConstructor
        @NoArgsConstructor
        public static class Officer {
            @NotBlank(message = "User type is required")
            private String userType;
        }
    }
}
