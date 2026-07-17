package org.arghyam.jalsoochak.tenant.dto.internal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;

/**
 * DTO for the {@code REGULARITY_THRESHOLD_PERCENT} config key: the share of days in a window on which a
 * scheme must supply water to be classified <em>regular</em> in analytics dashboards. Default 90.
 *
 * <p>Set per-tenant ({@code TenantConfigKeyEnum}) to move a tenant's own bar, or at system level
 * ({@code SystemConfigKeyEnum}) to define the national default (analytics tenant-0). Both paths publish a
 * {@code REGULARITY_THRESHOLD_UPDATED} Kafka event consumed by analytics-service.</p>
 *
 * <p>The bean-validation annotations document intent, but the enforced path is
 * {@link #validatedThresholdPercent()} — generic/system config values arrive as {@code JsonNode} and are
 * bound with {@code ObjectMapper}, which does not trigger bean validation.</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public final class RegularityThresholdConfigDTO implements ConfigValueDTO {

    /**
     * Percentage of days in the window on which a scheme must supply water to count as regular.
     * E.g. 90.0 means a scheme must supply on at least 90% of the window's days (rounded half-up,
     * minimum 1 day). Must be greater than 0 and at most 100.
     */
    @NotNull(message = "Regularity threshold percent cannot be null")
    @DecimalMin(value = "0.0", inclusive = false, message = "Regularity threshold percent must be > 0")
    @DecimalMax(value = "100.0", message = "Regularity threshold percent must be <= 100")
    private Double thresholdPercent;

    /**
     * Validates the configured threshold in-service (the enforced path for JsonNode-bound configs).
     *
     * @throws InvalidConfigValueException if the value is null, not finite, {@code <= 0}, or {@code > 100}
     */
    public Double validatedThresholdPercent() {
        if (thresholdPercent == null || !Double.isFinite(thresholdPercent)) {
            throw new InvalidConfigValueException(
                    "REGULARITY_THRESHOLD_PERCENT must be a number greater than 0 and at most 100");
        }
        if (thresholdPercent <= 0.0 || thresholdPercent > 100.0) {
            throw new InvalidConfigValueException(
                    "Invalid regularity threshold percent '" + thresholdPercent
                            + "' in REGULARITY_THRESHOLD_PERCENT (must be greater than 0 and at most 100)");
        }
        return thresholdPercent;
    }
}
