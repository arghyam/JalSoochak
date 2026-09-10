package org.arghyam.jalsoochak.telemetry.service;

public final class AnomalyConstants {

    private AnomalyConstants() {
    }

    public static final int TYPE_UNREADABLE_IMAGE = 1;
    public static final int TYPE_MANUAL_OVERRIDE = 2;
    public static final int TYPE_CONSECUTIVE_OVERRIDE_5_DAYS = 3;
    public static final int TYPE_DUPLICATE_IMAGE_SUBMISSION = 4;
    public static final int TYPE_READING_LESS_THAN_PREVIOUS = 5;
    // Operator-reported "No Water Supply" (e.g. from issue report menu).
    public static final int TYPE_NO_WATER_SUPPLY = 6;
    // Daily supply anomalies derived from water quantity vs water norm thresholds.
    public static final int TYPE_LOW_WATER_SUPPLY = 7;
    public static final int TYPE_OVER_WATER_SUPPLY = 8;
    // No meter reading submission due to operational issues (e.g. meter not working/damaged/others).
    public static final int TYPE_NO_SUBMISSION = 9;
    /**
     * SUPPLY-PLAUSIBILITY: the implied daily volume exceeds what the scheme's connected population
     * could physically consume. Distinct from {@link #TYPE_OVER_WATER_SUPPLY} (8), which means "above
     * the tenant's configured tolerance over the norm"; this one means "impossible for this
     * population". Named for the condition because staff read it on dashboards and the daily PDF —
     * the wire-facing name for the same rejection is deliberately the vaguer
     * {@code TelemetryErrorCode.ABNORMAL_READING}, which discloses no threshold.
     */
    public static final int TYPE_IMPLAUSIBLE_WATER_SUPPLY = 10;

    public static final int STATUS_OPEN = 1;

    /*
     * SUPPLY-PLAUSIBILITY reason texts. One fixed constant per case, deliberately with no
     * interpolated values: anomaly_table.reason then stays groupable, so "12 corrections rejected"
     * is a GROUP BY rather than a text parse. The per-row numbers live in the structured columns —
     * overridden_reading is the value that failed and previous_reading the baseline it was measured
     * against, so (overridden_reading - previous_reading) * 1000 reproduces the litres from the row
     * alone. The ceiling and population appear only in the server log.
     *
     * Write these constants, never a literal: the tests assert equals() against them, which is what
     * stops an appended number quietly making the column ungroupable again.
     */

    /** Case A — a POST submission was stored, quarantined and withheld from the warehouse. */
    public static final String REASON_IMPLAUSIBLE_SUPPLY_SUBMITTED =
            "Submitted reading implies an implausible daily water supply for this scheme.";

    /**
     * Case B — a PUT correction was refused over a reading that is already published. The published
     * value stands and the day is still counted; only the proposed replacement was rejected.
     */
    public static final String REASON_IMPLAUSIBLE_SUPPLY_CORRECTION_REJECTED_PUBLISHED =
            "Correction rejected: implies an implausible daily water supply. "
                    + "The published reading is unchanged.";

    /**
     * Case C — a PUT correction was refused over a reading that is itself quarantined. Told apart
     * from case B because it means something different to whoever picks the anomaly up: the day is
     * still missing from analytics and needs a plausible value before it will ever appear.
     */
    public static final String REASON_IMPLAUSIBLE_SUPPLY_CORRECTION_REJECTED_QUARANTINED =
            "Correction rejected: implies an implausible daily water supply. "
                    + "The reading remains quarantined.";
}
