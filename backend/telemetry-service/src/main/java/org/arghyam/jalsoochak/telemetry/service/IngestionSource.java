package org.arghyam.jalsoochak.telemetry.service;

/**
 * LENIENT-INGEST: bitmask describing why a meter-reading submission had to be recorded
 * through the lenient path (scheme id or operator phone missing from tenant master data).
 * Persisted on {@code flow_reading_table.ingestion_source}. A value of {@link #NORMAL} means
 * the submission resolved normally. Remove this class (and its callers) to revert the feature.
 */
public final class IngestionSource {

    private IngestionSource() {
    }

    /** Submission resolved normally against existing master data. */
    public static final int NORMAL = 0;

    /** bit 0 — submitted scheme id not found; recorded against an auto-provisioned placeholder scheme. */
    public static final int UNKNOWN_SCHEME = 1;

    /** bit 1 — submitted phone not found in user_table; recorded against the sentinel "Unknown operator". */
    public static final int UNKNOWN_OPERATOR = 2;

    /** bit 2 — scheme and operator both exist but the operator is not mapped to the scheme; recorded anyway. */
    public static final int OPERATOR_NOT_MAPPED = 4;

    /**
     * bit 3 — the submission carried no phone number at all, so the operator was *inferred* from the
     * scheme (first mapped pump operator, else the sentinel) rather than identified by the submitter.
     * Always set together with the outcome bit: {@link #UNKNOWN_OPERATOR} when the scheme had no mapped
     * pump operator, otherwise on its own. Without this bit a phone-less submission credited to a real,
     * scheme-mapped operator would be indistinguishable from a genuine one, since it trips none of the
     * other lenient conditions.
     */
    public static final int PHONE_ABSENT = 8;

    public static boolean has(int source, int bit) {
        return (source & bit) != 0;
    }
}
