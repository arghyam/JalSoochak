package org.arghyam.jalsoochak.telemetry.repository;

import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One row for a tenant schema's {@code anomaly_table}.
 *
 * <p>Carries the same structured detail that {@code TelemetryEventPublisher.publishAnomalyRecorded}
 * puts on the {@code ANOMALY_RECORDED} event, because the two rows describe the same anomaly and are
 * read side by side: the tenant row by operational queries, the {@code analytics_schema} row by
 * reporting. They drifted once — the tenant insert wrote only the identifying columns and left
 * {@code previous_reading} and {@code overridden_reading} NULL while analytics held both — so the
 * fields are grouped here rather than passed positionally, and every call site fills the same object.
 *
 * <p>{@code correlationId} is deliberately absent: the tenant table has no column for it. Its
 * {@code uuid} is database-generated and unrelated to the event uuid analytics derives, so the two
 * rows are matched on {@code (user_id, scheme_id, type, created_at)} rather than by key.
 *
 * <p>ANOMALY-SUBMISSION-LINK: {@code flowReadingId} is the one real key on this record — the
 * {@code flow_reading_table} row the anomaly was raised over. It is the submission's surrogate id
 * and not its {@code correlation_id} because the latter is mutable (an issue report overwrites it
 * on a reused same-day row), carries no unique constraint, is deliberately shared across rows by the
 * Glific flows, and is overloaded with {@code scheme-selection-}/{@code issue-report-} prefixes that
 * are matched with LIKE. {@code createFlowReading} already returns the id at every call site.
 *
 * @param userId                    operator the anomaly is filed against
 * @param schemeId                  scheme the anomaly is filed against
 * @param type                      an {@code AnomalyConstants.TYPE_*} code
 * @param reason                    human-readable text; prefer a fixed constant so the column stays
 *                                  groupable
 * @param status                    an {@code AnomalyConstants.STATUS_*} code
 * @param aiReading                 value FlowVision extracted, or {@code null} when no image was read
 * @param aiConfidencePercentage    FlowVision's confidence, or {@code null}
 * @param overriddenReading         the value that was submitted or attempted
 * @param retries                   attempts already made today, where the flow counts them
 * @param previousReading           the baseline the reading was judged against, not the standing
 *                                  stored value — see {@code AnomalyConstants}
 * @param previousReadingDate       when that baseline was recorded
 * @param consecutiveDaysOverridden run length behind a consecutive-override anomaly
 * @param flowReadingId             the {@code flow_reading_table} row this anomaly was raised over,
 *                                  or {@code null} for the types that have no submission behind them
 *                                  — 1 UNREADABLE_IMAGE, 4 DUPLICATE_IMAGE_SUBMISSION and 5
 *                                  READING_LESS_THAN_PREVIOUS are rejected before any row is
 *                                  inserted, 6 NO_WATER_SUPPLY and 9 NO_SUBMISSION come from the
 *                                  issue-report menu, and 3 CONSECUTIVE_OVERRIDE_5_DAYS is an
 *                                  aggregate over days with no single row to point at
 */
@Builder
public record TenantAnomalyRecord(
        Long userId,
        Long schemeId,
        Integer type,
        String reason,
        Integer status,
        BigDecimal aiReading,
        BigDecimal aiConfidencePercentage,
        BigDecimal overriddenReading,
        Integer retries,
        BigDecimal previousReading,
        LocalDateTime previousReadingDate,
        Integer consecutiveDaysOverridden,
        Long flowReadingId
) {

    /**
     * The four columns the table declares {@code NOT NULL}. Rejected here rather than at the INSERT
     * so a caller that drops one fails with the field's name instead of a constraint violation.
     */
    public TenantAnomalyRecord {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(schemeId, "schemeId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
    }
}
