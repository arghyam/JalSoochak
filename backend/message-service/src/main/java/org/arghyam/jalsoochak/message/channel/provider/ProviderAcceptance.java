package org.arghyam.jalsoochak.message.channel.provider;

import java.math.BigDecimal;

/**
 * What a provider handed back when it accepted a send: the handle its later delivery reports are
 * keyed on, and its own word for the acceptance.
 *
 * <p>Acceptance is not delivery. A populated {@code providerMessageId} means the provider took
 * responsibility for the message and can be asked about it later, nothing more; one without an id
 * can never be followed up, which the ledger records as {@link DeliveryState#NOT_TRACKED}.</p>
 *
 * @param providerMessageId the provider's id for the message, or {@code null} when it gives none
 * @param providerStatus    the provider's own word for the acceptance, verbatim, or {@code null}
 * @param cost              what the provider charged, when it says at send time; usually {@code null}
 * @param costCurrency      the currency of {@code cost}
 */
public record ProviderAcceptance(String providerMessageId, String providerStatus,
                                 BigDecimal cost, String costCurrency) {

    /** An acceptance that came with an id the provider can be asked about later. */
    public static ProviderAcceptance of(String providerMessageId, String providerStatus) {
        return new ProviderAcceptance(blankToNull(providerMessageId), providerStatus, null, null);
    }

    /** An acceptance with no id — the provider took it, and that is all anyone will ever know. */
    public static ProviderAcceptance untracked(String providerStatus) {
        return new ProviderAcceptance(null, providerStatus, null, null);
    }

    /** True when the provider gave an id its delivery reports can be matched on. */
    public boolean isTrackable() {
        return providerMessageId != null && !providerMessageId.isBlank();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
