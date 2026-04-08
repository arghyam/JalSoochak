package org.arghyam.jalsoochak.tenant.config.properties;

import java.util.List;

import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO;
import org.arghyam.jalsoochak.tenant.dto.internal.ReasonItemDTO;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@ConfigurationProperties(prefix = "tenant.defaults")
@Validated
@Data
public class TenantDefaultsProperties {

    @NotEmpty
    private List<@Valid LocationLevelConfigDTO> lgdLocationHierarchy;

    @NotEmpty
    private List<@Valid LocationLevelConfigDTO> deptLocationHierarchy;

    @NotEmpty
    private List<@Valid ReasonItemDTO> meterChangeReasons;

    @NotEmpty
    private List<@Valid ReasonItemDTO> supplyOutageReasons;
}
