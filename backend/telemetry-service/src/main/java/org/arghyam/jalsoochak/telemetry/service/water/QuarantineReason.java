package org.arghyam.jalsoochak.telemetry.service.water;

/**
 * SUPPLY-PLAUSIBILITY: the Java mirror of {@code flow_reading_table.quarantine_reason} (V40).
 *
 * <p>A quarantined row is stored but is not a reading: it is excluded from telemetry's own baseline
 * queries and never published to the warehouse. The column is a reason code rather than a boolean so
 * a future quarantine cause reuses it; the codes below are a contract with the database and with
 * anything reading the column directly, so append rather than repurpose.
 */
public final class QuarantineReason {

    /** The column default — an ordinary, accepted reading. */
    public static final int NONE = 0;

    /** The implied daily supply exceeds what the scheme's connected population could consume. */
    public static final int IMPLAUSIBLE_WATER_SUPPLY = 1;

    private QuarantineReason() {
    }
}
