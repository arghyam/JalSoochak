package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserEvent {

    private String eventType;
    private Integer userId;
    private Integer tenantId;
    private String email;
    private Integer userType;
    private UUID uuid;
    private String title;
    private Integer status;
}
