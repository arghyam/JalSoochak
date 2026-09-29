package org.arghyam.jalsoochak.telemetry.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SelectionResponse {
    private boolean success;
    private String selected;
    private String message;

    /**
     * Set only when {@link #selected} starts a reading submission: the scheme list the flow would
     * otherwise fetch with a second webhook call. Omitted from the JSON when unset, so the response
     * keeps its old shape for every other choice.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String schemesMessage;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonProperty("isSchemeGreaterThanOne")
    private Boolean isSchemeGreaterThanOne;
}
