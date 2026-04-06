package org.arghyam.jalsoochak.analytics.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnomalyListItemDto {

    private Long id;
    private String uuid;
    private String type;
    private Integer userId;
    private Integer schemeId;
    private Integer tenantId;
    private BigDecimal aiReading;
    private BigDecimal aiConfidencePercentage;
    private BigDecimal overriddenReading;
    private Integer retries;
    private BigDecimal previousReading;
    private LocalDate previousReadingDate;
    private Integer consecutiveDaysMissed;
    private String reason;
    @JsonIgnore
    private Integer statusCode;
    private String remarks;
    private String correlationId;
    private Integer resolvedBy;
    private OffsetDateTime resolvedAt;
    private OffsetDateTime deletedAt;
    private Integer deletedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @JsonProperty("scheme_name")
    private String schemeName;

    @JsonProperty("status")
    public String getStatus() {
        AnomalyStatusDto status = AnomalyStatusDto.fromCode(statusCode);
        return status != null ? status.getLabel() : null;
    }
}
