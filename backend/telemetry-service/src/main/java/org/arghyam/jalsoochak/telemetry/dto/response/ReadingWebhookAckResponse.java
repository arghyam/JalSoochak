package org.arghyam.jalsoochak.telemetry.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReadingWebhookAckResponse {
    private boolean success;
    private String status;
    @JsonProperty("job_id")
    private String jobId;
    private String message;
}
