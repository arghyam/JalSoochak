package org.arghyam.jalsoochak.telemetry.dto.requests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class MeterImageWebhookRequest {

    private String contactId;

    private String messageType;

    private String mediaId;

    private String mediaUrl;

    private String confirmedReading;

    private String correlationId;

    /**
     * When true, indicates the meter was replaced and the submitted reading should be treated as the new baseline.
     * Nullable for backward compatibility with older webhook payloads.
     */
    @JsonProperty("isMeterReplaced")
    private Boolean isMeterReplaced;
}
