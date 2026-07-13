package org.arghyam.jalsoochak.tenant.dto.internal;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;

import java.util.List;
import java.util.Set;

/**
 * DTO for the {@code INCLUDED_WORK_STATUSES} config key: the set of scheme {@code work_status} codes
 * whose schemes are counted in analytics dashboard aggregates. {@code 4} = handed-over.
 *
 * <p>Set per-tenant ({@code TenantConfigKeyEnum}) to scope a tenant's own dashboards, or at system
 * level ({@code SystemConfigKeyEnum}) to define the national default (analytics tenant-0). Both paths
 * publish an {@code INCLUDED_WORK_STATUSES_UPDATED} Kafka event consumed by analytics-service.</p>
 *
 * <p>The bean-validation annotations document intent, but the enforced path is
 * {@link #validatedWorkStatuses()} — generic/system config values arrive as {@code JsonNode} and are
 * bound with {@code ObjectMapper}, which does not trigger bean validation.</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public final class IncludedWorkStatusesConfigDTO implements ConfigValueDTO {

    /** Valid scheme work statuses: 1=Ongoing, 2=Completed, 3=Not Started, 4=Handed Over. */
    public static final Set<Integer> VALID_WORK_STATUSES = Set.of(1, 2, 3, 4);

    @NotEmpty(message = "At least one work status must be provided")
    private List<@NotNull Integer> workStatuses;

    /**
     * Validates the configured work statuses in-service (the enforced path for JsonNode-bound configs)
     * and returns them de-duplicated and sorted.
     *
     * @throws InvalidConfigValueException if the list is null/empty or contains a value outside
     *                                     {@link #VALID_WORK_STATUSES}
     */
    public List<Integer> validatedWorkStatuses() {
        if (workStatuses == null || workStatuses.isEmpty()) {
            throw new InvalidConfigValueException(
                    "INCLUDED_WORK_STATUSES must contain at least one work status (allowed: " + VALID_WORK_STATUSES + ")");
        }
        for (Integer ws : workStatuses) {
            if (ws == null || !VALID_WORK_STATUSES.contains(ws)) {
                throw new InvalidConfigValueException(
                        "Invalid work status '" + ws + "' in INCLUDED_WORK_STATUSES (allowed: " + VALID_WORK_STATUSES + ")");
            }
        }
        return workStatuses.stream().distinct().sorted().toList();
    }
}
