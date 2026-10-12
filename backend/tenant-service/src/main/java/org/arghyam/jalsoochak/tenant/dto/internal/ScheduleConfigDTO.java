package org.arghyam.jalsoochak.tenant.dto.internal;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;

/**
 * Reusable DTO for cron schedule configuration.
 * hour and minute are required; dayOfMonth, month, and dayOfWeek are optional
 * and only needed for schedules more specific than a daily time (e.g. monthly jobs).
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ScheduleConfigDTO {
    @NotNull(message = "Hour is required")
    @Min(value = 0, message = "Hour must be between 0 and 23")
    @Max(value = 23, message = "Hour must be between 0 and 23")
    private Integer hour;

    @NotNull(message = "Minute is required")
    @Min(value = 0, message = "Minute must be between 0 and 59")
    @Max(value = 59, message = "Minute must be between 0 and 59")
    private Integer minute;

    /** 1–31. Null means "every day" (cron wildcard). */
    @Min(value = 1, message = "Day of month must be between 1 and 31")
    @Max(value = 31, message = "Day of month must be between 1 and 31")
    private Integer dayOfMonth;

    /** 1–12. Null means "every month" (cron wildcard). */
    @Min(value = 1, message = "Month must be between 1 and 12")
    @Max(value = 12, message = "Month must be between 1 and 12")
    private Integer month;

    /** 0–7 (0 and 7 both represent Sunday). Null means "every day of week" (cron wildcard). */
    @Min(value = 0, message = "Day of week must be between 0 and 7")
    @Max(value = 7, message = "Day of week must be between 0 and 7")
    private Integer dayOfWeek;

    /**
     * Range-checks the fields the notification scheduler reads: dayOfWeek, hour and minute.
     *
     * <p>The bean-validation annotations above do not run on the generic config write path, which
     * binds with {@code ObjectMapper.treeToValue}. Without this an out-of-range value would be stored,
     * and {@code TenantConfigService} would then silently run the job on the default schedule.</p>
     *
     * <p>Null-tolerant: an absent field means "use the application default".</p>
     *
     * @param configKey the config key named in the error message
     * @throws InvalidConfigValueException if an explicitly supplied field is out of range
     */
    public void validateRanges(String configKey) {
        checkRange(dayOfWeek, 0, 7, "dayOfWeek", configKey,
                "(must be between 0 and 7, where both 0 and 7 mean Sunday)");
        checkRange(hour, 0, 23, "hour", configKey, "(must be between 0 and 23)");
        checkRange(minute, 0, 59, "minute", configKey, "(must be between 0 and 59)");
    }

    private static void checkRange(Integer value, int min, int max, String field, String configKey,
                                   String bounds) {
        if (value != null && (value < min || value > max)) {
            throw new InvalidConfigValueException(
                    "Invalid " + field + " '" + value + "' in " + configKey + " " + bounds);
        }
    }
}
