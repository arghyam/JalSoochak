package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EscalationEvent {

    private String eventType;
    private Integer tenantId;
    private Integer schemeId;
    private Integer escalationType;
    private String message;
    private String correlationId;
    private Integer userId;
    private Integer resolutionStatus;
    private String remark;
}
