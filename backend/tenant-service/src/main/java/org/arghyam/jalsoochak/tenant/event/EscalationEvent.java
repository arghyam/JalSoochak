package org.arghyam.jalsoochak.tenant.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EscalationEvent {
    private String eventType;
    private int escalationLevel;
    private String anomalyType;
    private String officerPhone;
    private String officerName;
    private List<OperatorEscalationDetail> operators;
    private Integer tenantId;
    private Integer officerLanguageId;
    private Long officerId;
    private Long officerWhatsappConnectionId;
    private String tenantSchema;
    private String correlationId;
    private String officerUserType;
}
