package org.arghyam.jalsoochak.message.channel.provider;

import org.arghyam.jalsoochak.message.ledger.DispatchStatus;
import org.arghyam.jalsoochak.message.ledger.LedgerRef;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The delivery-state rules the ledger's SQL mirrors, and the tracking reference's format. */
class DeliveryStateTest {

    @ParameterizedTest(name = "{0} -> {1}: {2}")
    @CsvSource({
            "PENDING, DELIVERED, true", "PENDING, READ, true", "PENDING, FAILED, true",
            "UNRESOLVED, DELIVERED, true", "NOT_TRACKED, READ, true",
            "DELIVERED, READ, true", "DELIVERED, FAILED, false", "DELIVERED, PENDING, false",
            "READ, DELIVERED, false", "FAILED, DELIVERED, false", "NOT_SENT, DELIVERED, false",
            "PENDING, PENDING, false"})
    void movesForwardOnly(DeliveryState from, DeliveryState to, boolean allowed) {
        assertThat(from.canAdvanceTo(to)).isEqualTo(allowed);
    }

    @Test
    void aDispatchStartsTheRightDeliveryState() {
        assertThat(DispatchStatus.ACCEPTED.initialDeliveryState(true)).isEqualTo(DeliveryState.PENDING);
        assertThat(DispatchStatus.ACCEPTED.initialDeliveryState(false)).isEqualTo(DeliveryState.NOT_TRACKED);
        assertThat(DispatchStatus.DELIVERY_UNCONFIRMED.initialDeliveryState(false)).isEqualTo(DeliveryState.NOT_TRACKED);
        assertThat(DispatchStatus.SUPPRESSED.initialDeliveryState(true)).isEqualTo(DeliveryState.NOT_SENT);
        assertThat(DispatchStatus.SKIPPED_NO_CONTACT.initialDeliveryState(false)).isEqualTo(DeliveryState.NOT_SENT);
    }

    @Test
    void parsesOnlyTrackingRefsThisServiceIssues() {
        String uuid = "7d0f6c0a-1111-2222-3333-444455556666";
        assertThat(LedgerRef.parseTrackingRef("tenant_mp:" + uuid)).containsExactly("tenant_mp", uuid);
        assertThat(LedgerRef.parseTrackingRef("Tenant-MP:" + uuid)).isNull();
        assertThat(LedgerRef.parseTrackingRef("tenant_mp:not-a-uuid")).isNull();
        assertThat(LedgerRef.parseTrackingRef("tenant_mp")).isNull();
        assertThat(LedgerRef.parseTrackingRef(null)).isNull();
        assertThat(new LedgerRef("tenant_mp", uuid, 1, false, 0).trackingRef()).isNull();
    }
}
