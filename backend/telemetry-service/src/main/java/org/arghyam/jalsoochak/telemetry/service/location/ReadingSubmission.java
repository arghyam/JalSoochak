package org.arghyam.jalsoochak.telemetry.service.location;

import java.time.LocalDate;

/**
 * LOCATION-AFFINITY: the persisted reading a boundary check is being run against.
 *
 * <p>ANOMALY-SUBMISSION-LINK: both identifiers are carried because the tenant row and the analytics
 * row join on different things — {@code anomaly_table.flow_reading_id} is a schema-local surrogate
 * that means nothing in the warehouse, so {@code ANOMALY_RECORDED} carries the reading's
 * {@code correlation_id} instead. Passing both is what makes "the anomaly is captured against this
 * submission" true on both sides rather than only operationally.
 *
 * @param readingId       {@code flow_reading_table.id} of the row that was written
 * @param correlationId   that row's {@code correlation_id}, for the warehouse-side join; may be
 *                        {@code null} when the caller has no stable value
 * @param readingDate     the day the anomaly is keyed on, so repeated submissions collapse to one
 */
public record ReadingSubmission(Long readingId, String correlationId, LocalDate readingDate) {
}
