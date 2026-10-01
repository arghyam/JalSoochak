package org.arghyam.jalsoochak.telemetry.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response of {@code POST /api/v1/telemetry/readings/republish}, in the {@code success} + {@code data}
 * envelope the other {@code /readings} routes answer in.
 */
public record RepublishReadingsResponse(boolean success, Data data) {

    /**
     * @param republishedCount readings Kafka acknowledged
     * @param withheldCount    readings not sent because they are still quarantined
     * @param notSentCount     readings not sent because Kafka stopped acknowledging them part-way
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Data(Integer republishedCount,
                       Integer withheldCount,
                       Integer notSentCount,
                       TelemetryErrorCode errorCode,
                       String message) {
    }

    public static RepublishReadingsResponse republished(int republishedCount, int withheldCount) {
        return new RepublishReadingsResponse(true, new Data(republishedCount, withheldCount, null, null, null));
    }

    /** A run that stopped part-way, with what was and was not sent before it stopped. */
    public static RepublishReadingsResponse stopped(int republishedCount,
                                                    int withheldCount,
                                                    int notSentCount,
                                                    TelemetryErrorCode errorCode,
                                                    String message) {
        return new RepublishReadingsResponse(false,
                new Data(republishedCount, withheldCount, notSentCount, errorCode, message));
    }

    public static RepublishReadingsResponse rejected(TelemetryErrorCode errorCode, String message) {
        return new RepublishReadingsResponse(false, new Data(null, null, null, errorCode, message));
    }
}
