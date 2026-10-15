package org.arghyam.jalsoochak.telemetry.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response of {@code POST /api/v1/telemetry/internal/readings/quarantine/release}, in the
 * {@code success} + {@code data} envelope the {@code /readings} routes answer in.
 */
public record ReleaseQuarantinedReadingsResponse(boolean success, Data data) {

    /**
     * @param dryRun                whether the counts are what a release would do, with nothing kept
     * @param releasedCount         readings released, or that a release would release
     * @param stillQuarantinedCount readings the check still finds implausible
     * @param notSentCount          readings left quarantined because Kafka stopped acknowledging part-way
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Data(Boolean dryRun,
                       Integer releasedCount,
                       Integer stillQuarantinedCount,
                       Integer notSentCount,
                       TelemetryErrorCode errorCode,
                       String message) {
    }

    public static ReleaseQuarantinedReadingsResponse released(boolean dryRun,
                                                              int releasedCount,
                                                              int stillQuarantinedCount) {
        return new ReleaseQuarantinedReadingsResponse(true,
                new Data(dryRun, releasedCount, stillQuarantinedCount, null, null, null));
    }

    /** A run that stopped part-way, with what was and was not released before it stopped. */
    public static ReleaseQuarantinedReadingsResponse stopped(int releasedCount,
                                                             int stillQuarantinedCount,
                                                             int notSentCount,
                                                             TelemetryErrorCode errorCode,
                                                             String message) {
        return new ReleaseQuarantinedReadingsResponse(false,
                new Data(false, releasedCount, stillQuarantinedCount, notSentCount, errorCode, message));
    }

    public static ReleaseQuarantinedReadingsResponse rejected(TelemetryErrorCode errorCode, String message) {
        return new ReleaseQuarantinedReadingsResponse(false, new Data(null, null, null, null, errorCode, message));
    }
}
