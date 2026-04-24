package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserSchemeMappingsReplacedEvent {

    private String eventType;
    private Integer userId;
    private Integer tenantId;
    private UUID userUuid;
    private List<Integer> schemeIds;
    private Integer status;
}
