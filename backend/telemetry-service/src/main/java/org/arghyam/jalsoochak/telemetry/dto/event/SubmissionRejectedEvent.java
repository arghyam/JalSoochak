package org.arghyam.jalsoochak.telemetry.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * REPORTED-METRIC: emitted when a meter-reading submission is rejected BEFORE any reading or anomaly
 * row is written (bean-validation, invalid API key, and other pre-processing rejects). Lets analytics
 * record that a scheme attempted a submission so the "reported" scheme counts can include it.
 * Remove this event (and its publish sites + the analytics consumer) to revert.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SubmissionRejectedEvent {

    private String eventType;
    private Integer tenantId;
    private String submittedStateSchemeId;
    private String submittedCentreSchemeId;
    private String submittedPhoneHash;
    private String reason;
    private String attemptedAt;
}
