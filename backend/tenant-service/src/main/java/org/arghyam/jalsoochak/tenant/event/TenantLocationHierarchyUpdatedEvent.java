package org.arghyam.jalsoochak.tenant.event;

import lombok.Value;
import org.arghyam.jalsoochak.tenant.dto.internal.LocationLevelConfigDTO;

import java.util.List;

@Value
public class TenantLocationHierarchyUpdatedEvent {
    Integer tenantId;
    String stateCode;
    String hierarchyType;
    List<LocationLevelConfigDTO> levels;
}
