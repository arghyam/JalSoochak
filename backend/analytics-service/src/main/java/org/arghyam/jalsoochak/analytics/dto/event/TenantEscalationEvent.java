package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TenantEscalationEvent {

    private String eventType;
    private Integer escalationLevel;
    private String anomalyType;
    private String officerPhone;
    private String officerName;
    private List<TenantOperatorEscalationDetail> operators;
    private Integer tenantId;
    private Integer officerLanguageId;
    private Long officerId;
    private Long officerWhatsappConnectionId;
    private String tenantSchema;
    private String correlationId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TenantOperatorEscalationDetail {
        private String name;
        private String phoneNumber;
        private String schemeName;
        private String schemeId;
        private String soName;
        private Integer consecutiveDaysMissed;
        private String lastRecordedBfmDate;
        private Integer userId;
        private Double lastConfirmedReading;
        private String correlationId;
    }
}
