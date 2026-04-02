package org.arghyam.jalsoochak.tenant.dto.response;

import java.util.Map;

import org.arghyam.jalsoochak.tenant.enums.ConfigStatusEnum;
import org.arghyam.jalsoochak.tenant.enums.TenantConfigKeyEnum;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Configuration completeness status for a tenant")
public class TenantConfigStatusResponseDTO {

    @Schema(description = "ID of the tenant", example = "1")
    private Integer tenantId;

    @Schema(description = "Aggregate counts across all configuration keys")
    private Summary summary;

    @Schema(description = "Per-key configuration status")
    private Map<TenantConfigKeyEnum, ConfigEntry> configs;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Aggregate summary of configuration completeness")
    public static class Summary {

        @Schema(description = "Total number of known configuration keys", example = "19")
        private int total;

        @Schema(description = "Number of keys that have been configured", example = "5")
        private int configured;

        @Schema(description = "Number of keys that are still pending configuration", example = "14")
        private int pending;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Status of a single configuration key")
    public static class ConfigEntry {

        @Schema(description = "CONFIGURED if a value has been set, PENDING otherwise",
                example = "CONFIGURED", allowableValues = {"CONFIGURED", "PENDING"})
        private ConfigStatusEnum status;

        @Schema(description = "Whether this key must be configured before the tenant can auto-transition to CONFIGURED status",
                example = "true")
        private boolean mandatory;
    }
}
