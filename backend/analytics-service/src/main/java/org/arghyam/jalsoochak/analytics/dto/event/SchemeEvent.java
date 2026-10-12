package org.arghyam.jalsoochak.analytics.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A scheme's details: everything about it except which villages and sub-divisions it serves. They
 * apply to every row the scheme has in {@code dim_scheme_table}. Older messages also carry one village
 * and sub-division; those fields are ignored.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SchemeEvent {

    private String eventType;
    private Integer schemeId;
    private Integer tenantId;
    private String schemeName;
    private Integer stateSchemeId;
    private Integer centreSchemeId;
    private Double longitude;
    private Double latitude;

    /** Operating status. */
    private Integer status;

    @JsonProperty("work_status")
    private Integer workStatus;

    /** FHTC counts. Null when the message does not carry them, which keeps the stored values. */
    private Integer fhtcCount;
    private Integer plannedFhtc;
    private Integer houseHoldCount;
}
