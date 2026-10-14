package org.arghyam.jalsoochak.message.channel.provider;

import java.util.List;

/**
 * What an authenticated delivery-report request yielded: its reports, and the schemas the credential
 * that authenticated it may change.
 *
 * @param receipts the reports, possibly none
 * @param scope    where they may be applied; {@link ReceiptScope#ANY} for a platform credential
 */
public record VerifiedReceipts(List<DeliveryReceipt> receipts, ReceiptScope scope) {

    public VerifiedReceipts {
        receipts = receipts == null ? List.of() : List.copyOf(receipts);
        scope = scope == null ? ReceiptScope.ANY : scope;
    }

    /** Reports authenticated by a platform credential, which may reach any schema. */
    public static VerifiedReceipts unscoped(List<DeliveryReceipt> receipts) {
        return new VerifiedReceipts(receipts, ReceiptScope.ANY);
    }
}
