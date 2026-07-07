package org.arghyam.jalsoochak.analytics.dto.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * REPORTED-METRIC: a submission rejected in telemetry before any reading/anomaly was written.
 * Consumed from telemetry-service-topic and stored in submission_attempt_table so "reported" scheme
 * counts can include it. Remove this (+ consumer case + table) to revert.
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
