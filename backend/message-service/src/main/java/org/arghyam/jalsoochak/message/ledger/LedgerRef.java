package org.arghyam.jalsoochak.message.ledger;

/**
 * A handle on an opened ledger row, passed back to {@link NotificationLedger#close}.
 *
 * <p>A ref is returned even when the row could not be written — the ledger is off, or the insert
 * failed — so callers never branch on the ledger: closing an unrecorded ref does nothing.</p>
 *
 * @param schema       the schema the row lives in
 * @param uuid         the row's uuid, generated before the insert so a tracking reference exists even
 *                     if the insert then fails
 * @param tenantId     the tenant the row belongs to, or {@code null} for {@code common_schema}
 * @param recorded     whether the row was actually written
 * @param startedNanos when the row was opened, for the send's latency
 */
public record LedgerRef(String schema, String uuid, Integer tenantId, boolean recorded, long startedNanos) {

    private static final String SEPARATOR = ":";

    /**
     * The opaque reference a provider is asked to echo back with its delivery reports, so a report
     * leads straight to its schema and row. Holds no personal data. {@code null} when nothing was
     * recorded, so a provider is never handed a reference to a row that does not exist.
     */
    public String trackingRef() {
        return recorded ? schema + SEPARATOR + uuid : null;
    }

    /** The schema and uuid inside a tracking reference, or {@code null} for one this service did not issue. */
    public static String[] parseTrackingRef(String trackingRef) {
        if (trackingRef == null) {
            return null;
        }
        String[] parts = trackingRef.trim().split(SEPARATOR, 2);
        if (parts.length != 2 || !parts[0].matches("^[a-z0-9_]+$")
                || !parts[1].matches("^[0-9a-fA-F-]{36}$")) {
            return null;
        }
        return parts;
    }
}
