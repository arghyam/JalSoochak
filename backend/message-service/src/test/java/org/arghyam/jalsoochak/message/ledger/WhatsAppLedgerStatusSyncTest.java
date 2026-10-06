package org.arghyam.jalsoochak.message.ledger;

import org.arghyam.jalsoochak.message.channel.provider.DeliveryReceipt;
import org.arghyam.jalsoochak.message.channel.provider.DeliveryState;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppDeliveryStatusReader;
import org.arghyam.jalsoochak.message.dto.WhatsAppDeliveryOutcome;
import org.arghyam.jalsoochak.message.dto.WhatsAppMessageStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** How the WhatsApp provider's statuses reach the ledger, without missing a message or re-reading a settled one. */
@ExtendWith(MockitoExtension.class)
class WhatsAppLedgerStatusSyncTest {

    private static final String PROVIDER = "wa-provider";

    @Mock private WhatsAppDeliveryStatusReader reader;
    @Mock private NotificationLedger ledger;
    @Mock private NotificationLedgerRepository repository;

    private WhatsAppLedgerStatusSync sync;

    @BeforeEach
    void setUp() {
        sync = new WhatsAppLedgerStatusSync(reader, ledger, repository, true, 10, 6, 250, 400, 120, 2, 72);
        lenient().when(ledger.isEnabled()).thenReturn(true);
        lenient().when(reader.providerId()).thenReturn(PROVIDER);
        lenient().when(reader.maxPageSize()).thenReturn(50);
        lenient().when(repository.ledgerSchemas()).thenReturn(List.of("common_schema", "tenant_mp"));
    }

    @Test
    void appliesOutboundTemplateMessagesToTheSchemaThatHoldsThem() {
        when(repository.openMessageIds(eq("tenant_mp"), eq(PROVIDER), anyCollection())).thenReturn(List.of("m1"));
        when(ledger.applyReceipt(eq("tenant_mp"), any())).thenReturn(1);

        WhatsAppLedgerStatusSync.SyncStats stats = sync.apply(List.of(
                outbound("m1", "SENT", WhatsAppDeliveryOutcome.PENDING),
                outbound("m1", "READ", WhatsAppDeliveryOutcome.READ),
                outbound("m2", "DELIVERED", WhatsAppDeliveryOutcome.DELIVERED),
                inbound("m3")));

        ArgumentCaptor<DeliveryReceipt> receipt = ArgumentCaptor.forClass(DeliveryReceipt.class);
        verify(ledger).applyReceipt(eq("tenant_mp"), receipt.capture());
        assertThat(receipt.getValue().state()).as("the later, more advanced status wins").isEqualTo(DeliveryState.READ);
        assertThat(receipt.getValue().providerId()).isEqualTo(PROVIDER);
        assertThat(stats).isEqualTo(new WhatsAppLedgerStatusSync.SyncStats(2, 1, 1));
    }

    @Test
    void aFailureCarriesTheProvidersCode() {
        when(repository.openMessageIds(eq("common_schema"), eq(PROVIDER), anyCollection())).thenReturn(List.of("m9"));

        sync.apply(List.of(new WhatsAppMessageStatus("m9", "gs", "ERROR", 1, true, "OUTBOUND", 5L,
                WhatsAppDeliveryOutcome.DELIVERY_FAILED, "131026", "undeliverable")));

        ArgumentCaptor<DeliveryReceipt> receipt = ArgumentCaptor.forClass(DeliveryReceipt.class);
        verify(ledger).applyReceipt(eq("common_schema"), receipt.capture());
        assertThat(receipt.getValue().errorCode()).isEqualTo("131026");
    }

    @Test
    void doesNothingWhileTheLedgerIsOff() {
        when(ledger.isEnabled()).thenReturn(false);

        sync.apply(List.of(outbound("m1", "SENT", WhatsAppDeliveryOutcome.PENDING)));
        sync.syncIncremental(Instant.now());
        sync.sweep();

        verifyNoInteractions(repository, reader);
    }

    @Test
    void readsChangesSinceTheCursor_inProgressionOrder_thenMovesTheCursor() {
        Instant now = Instant.parse("2026-10-05T12:00:00Z");
        Instant cursor = Instant.parse("2026-10-05T11:30:00Z");
        when(repository.readCursor(WhatsAppLedgerStatusSync.CURSOR_SOURCE)).thenReturn(Optional.of(cursor));
        when(reader.countMessages(any(), any(), anyString(), eq("updated_at"))).thenReturn(0);

        sync.syncIncremental(now);

        InOrder order = inOrder(reader);
        for (String status : WhatsAppLedgerStatusSync.STATUSES_IN_PROGRESSION) {
            order.verify(reader).countMessages(cursor.minus(Duration.ofMinutes(10)), now, status, "updated_at");
        }
        verify(repository).writeCursor(WhatsAppLedgerStatusSync.CURSOR_SOURCE, now);
    }

    @Test
    void aRefusedPassLeavesTheCursorWhereItWas() {
        when(repository.readCursor(any())).thenReturn(Optional.empty());
        when(reader.countMessages(any(), any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("unknown column"));

        sync.syncIncremental(Instant.now());

        verify(repository, never()).writeCursor(anyString(), any());
    }

    @Test
    void sizesThePageBudgetFromTheProvidersCount() {
        Instant from = Instant.parse("2026-10-05T00:00:00Z");
        Instant to = from.plusSeconds(3600);
        when(reader.countMessages(from, to, "DELIVERED", "inserted_at")).thenReturn(1234);

        WhatsAppLedgerStatusSync.fetchAll(reader, from, to, "DELIVERED", "inserted_at", 250, 400);

        // 1234 / 50 = 24.68 → 25 pages, plus one for messages inserted while paging.
        verify(reader).fetchMessages(from, to, "DELIVERED", "inserted_at", 50, 26);
    }

    @Test
    void neverAsksForAStatusTheProviderCountsAsEmpty() {
        when(reader.countMessages(any(), any(), anyString(), anyString())).thenReturn(0);

        assertThat(WhatsAppLedgerStatusSync.fetchAll(reader, Instant.now(), Instant.now(), "SENT", "inserted_at",
                250, 400)).isEmpty();
        verify(reader, never()).fetchMessages(any(), any(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void sweepsStragglersWithinTheBudget() {
        when(repository.pendingForSweep(eq("common_schema"), eq(6), eq(PROVIDER), eq(120), eq(72), eq(2)))
                .thenReturn(List.of());
        when(repository.pendingForSweep(eq("tenant_mp"), eq(6), eq(PROVIDER), eq(120), eq(72), eq(2)))
                .thenReturn(List.of(
                        new NotificationLedgerRepository.PendingRow("u1", "m1", Instant.now()),
                        new NotificationLedgerRepository.PendingRow("u2", "m2", Instant.now())));
        when(reader.fetchMessage("m1")).thenReturn(Optional.of(outbound("m1", "DELIVERED", WhatsAppDeliveryOutcome.DELIVERED)));
        when(reader.fetchMessage("m2")).thenThrow(new RuntimeException("timeout"));
        when(ledger.applyReceipt(eq("tenant_mp"), any())).thenReturn(1);

        assertThat(sync.sweep()).isEqualTo(1);
        verify(reader, times(2)).fetchMessage(anyString());
    }

    private static WhatsAppMessageStatus outbound(String id, String bsp, WhatsAppDeliveryOutcome outcome) {
        return new WhatsAppMessageStatus(id, "gs-" + id, bsp, 880557, true, "OUTBOUND", 42L, outcome, null, null);
    }

    private static WhatsAppMessageStatus inbound(String id) {
        return new WhatsAppMessageStatus(id, null, "RECEIVED", null, false, "INBOUND", 1L,
                WhatsAppDeliveryOutcome.IGNORED, null, null);
    }
}
