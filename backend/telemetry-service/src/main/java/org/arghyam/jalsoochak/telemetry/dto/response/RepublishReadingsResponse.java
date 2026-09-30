package org.arghyam.jalsoochak.telemetry.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Response of {@code POST /api/v1/telemetry/readings/republish}, in the {@code success} + {@code data}
 * envelope the other {@code /readings} routes answer in.
 */
public record RepublishReadingsResponse(boolean success, Data data) {

    /**
     * @param republishedCount readings handed to the event publisher, which sends them to Kafka
     *                         asynchronously
     * @param withheldCount    readings not sent because they are still quarantined
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Data(Integer republishedCount,
                       Integer withheldCount,
                       TelemetryErrorCode errorCode,
                       String message) {
    }

    public static RepublishReadingsResponse republished(int republishedCount, int withheldCount) {
        return new RepublishReadingsResponse(true, new Data(republishedCount, withheldCount, null, null));
    }

    public static RepublishReadingsResponse rejected(TelemetryErrorCode errorCode, String message) {
        return new RepublishReadingsResponse(false, new Data(null, null, errorCode, message));
    }
}
