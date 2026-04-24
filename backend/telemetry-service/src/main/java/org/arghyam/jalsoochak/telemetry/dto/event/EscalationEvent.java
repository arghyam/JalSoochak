package org.arghyam.jalsoochak.telemetry.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
