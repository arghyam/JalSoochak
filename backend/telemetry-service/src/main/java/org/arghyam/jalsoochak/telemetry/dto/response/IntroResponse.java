package org.arghyam.jalsoochak.telemetry.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IntroResponse {
    private boolean success;
    private String message;
    private String correlationId;
    private String selected;
    @JsonProperty("isSchemeGreaterThanOne")
    private Boolean isSchemeGreaterThanOne;
    private Boolean hasBfm;
    private Boolean hasElectric;
    private Boolean isBfmorIsElectrical;
    private Boolean isBfm;
    private Boolean isElectrical;
    private String isBfmOrIsElectric;
    private Boolean notOthers;
}
