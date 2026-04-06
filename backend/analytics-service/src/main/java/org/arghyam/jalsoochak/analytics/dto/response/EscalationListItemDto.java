package org.arghyam.jalsoochak.analytics.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EscalationListItemDto {

    private Long id;
    private Integer tenantId;
    private Integer schemeId;
    private String escalationType;
    private String message;
    private String correlationId;
    private Integer userId;
    private Integer resolutionStatus;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @JsonProperty("scheme_name")
    private String schemeName;
}
