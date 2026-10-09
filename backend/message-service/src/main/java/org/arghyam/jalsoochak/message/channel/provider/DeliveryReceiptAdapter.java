package org.arghyam.jalsoochak.message.channel.provider;

/**
 * Port for delivery reports a provider pushes to us — the push half of delivery status, beside the
 * pull half each sender's {@code lookupStatuses} provides.
 *
 * <p>One adapter per provider, registered as a Spring bean and found by {@link #providerId()}, which
 * is also the last path segment of {@code POST /api/v1/message/delivery-receipts/{providerId}}. Adding a
 * provider's reports is adding an adapter; the endpoint and the ledger do not change. It is also the
 * whole of delivery status for a provider that only pushes — one with no status API to ask.</p>
 *
 * <p>The endpoint is open at the gateway, so an adapter must <strong>authenticate the request before
 * reading anything from it</strong> — a signature, or a shared secret — and refuse with
 * {@link ReceiptRejectedException} otherwise. It returns reports carrying identifiers and statuses only:
 * an address or message text in the provider's payload is left behind.</p>
 *
 * <p>Every report must carry this adapter's own {@link #providerId()}: the endpoint drops any other, and
 * the ledger only ever matches a report to a row that went out through the provider it names. When the
 * credential that authenticated the request belongs to one tenant's own account rather than the
 * platform's, the adapter narrows the {@link ReceiptScope} to that tenant's schema.</p>
 */
public interface DeliveryReceiptAdapter {

    /** The provider these reports come from; the same id its sender records in the ledger. */
    String providerId();

    /**
     * Authenticates and parses one pushed request.
     *
     * @return the reports in it, possibly none — a payload of events that carry no delivery status — and
     *         the schemas they may change
     * @throws ReceiptRejectedException if the request cannot be shown to come from the provider
     * @throws IllegalArgumentException if it is authentic but cannot be parsed
     */
    VerifiedReceipts parseAndVerify(DeliveryReceiptRequest request);
}
