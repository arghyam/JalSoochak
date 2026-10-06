package org.arghyam.jalsoochak.message.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.arghyam.jalsoochak.message.channel.provider.DeliveryReceipt;
import org.arghyam.jalsoochak.message.channel.provider.DeliveryState;
import org.arghyam.jalsoochak.message.channel.provider.ProviderAcceptance;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSender;
import org.arghyam.jalsoochak.message.dto.TenantRef;
import org.arghyam.jalsoochak.message.service.PiiEncryptionService;
import org.arghyam.jalsoochak.message.service.TenantRefResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The ledger's contract with the senders: it never throws into a send, never lets an address reach the
 * database, keeps only allow-listed metadata, and routes provider reports to the right schema.
 */
@ExtendWith(MockitoExtension.class)
class NotificationLedgerTest {

    private static final String PHONE = "919876500001"; // fabricated

    @Mock private NotificationLedgerRepository repository;
    @Mock private NotificationDeliveryEventPublisher publisher;
    @Mock private PiiEncryptionService pii;
    @Mock private TenantRefResolver tenantRefResolver;
    @Mock private ObjectProvider<WhatsAppSender> whatsAppSenderProvider;
    @Mock private WhatsAppSender whatsAppSender;

    private SimpleMeterRegistry meters;
    private NotificationLedger ledger;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        ledger = ledger(true);
        lenient().when(pii.hmac(anyString())).thenAnswer(inv -> "hmac(" + inv.getArgument(0) + ")");
        lenient().when(tenantRefResolver.resolve(any(), any())).thenReturn(new TenantRef(7, "MP"));
        lenient().when(whatsAppSenderProvider.getIfAvailable()).thenReturn(whatsAppSender);
        lenient().when(whatsAppSender.providerId()).thenReturn("wa-provider");
    }

    private NotificationLedger ledger(boolean enabled) {
        return new NotificationLedger(repository, publisher, pii, tenantRefResolver, new ObjectMapper(), meters,
                whatsAppSenderProvider, enabled);
    }

    private static LedgerEntry.LedgerEntryBuilder entry() {
        return LedgerEntry.builder()
                .tenantSchema("tenant_mp")
                .type(NotificationType.DAILY_REPORT)
                .channel(LedgerChannel.WHATSAPP)
                .userId(11L)
                .recipient(PHONE)
                .subjectDate(LocalDate.of(2026, 10, 5))
                .eventType("DAILY_REPORT_KPIS");
    }

    @Test
    void opensARowWithTheHashNeverTheAddress() {
        LedgerRef ref = ledger.open(entry().build());

        ArgumentCaptor<NotificationLedgerRepository.NewRow> row = ArgumentCaptor.forClass(NotificationLedgerRepository.NewRow.class);
        verify(repository).open(eq("tenant_mp"), row.capture());
        assertThat(row.getValue().recipientHash()).isEqualTo("hmac(" + PHONE + ")");
        assertThat(row.getValue().toString()).doesNotContain("=" + PHONE);
        assertThat(row.getValue().provider()).isEqualTo("wa-provider");
        assertThat(row.getValue().dedupeKey()).isEqualTo("DAILY_REPORT:u11:2026-10-05");
        assertThat(ref.recorded()).isTrue();
        assertThat(ref.tenantId()).isEqualTo(7);
        assertThat(ref.trackingRef()).isEqualTo("tenant_mp:" + ref.uuid());
    }

    @Test
    void normalisesABareTenDigitNumberBeforeHashing() {
        ledger.open(entry().recipient("98765 00001").build());

        verify(pii).hmac(PHONE);
    }

    @Test
    void anEmailIsHashedLowerCased() {
        ledger.open(entry().channel(LedgerChannel.EMAIL).provider("mail").recipient(" Someone@Example.ORG ").build());

        verify(pii).hmac("someone@example.org");
    }

    @Test
    void keepsOnlyAllowListedMetadata_andStripsAQueryString() {
        String json = ledger.metadataJson(Map.of(
                "documentUrl", "https://store/r.pdf?X-Amz-Signature=secret",
                "reportDate", "2026-10-05",
                "officerName", "Someone",
                "otp", "123456"));

        assertThat(json).contains("\"documentUrl\":\"https://store/r.pdf\"").contains("reportDate")
                .doesNotContain("Someone").doesNotContain("123456").doesNotContain("secret");
    }

    @Test
    void aTenantLessEntryIsRecordedInCommonSchema() {
        ledger.open(entry().tenantSchema(null).build());

        verify(repository).open(eq("common_schema"), any());
        assertThat(ledger.schemaFor(TenantRef.NONE)).isEqualTo("common_schema");
        assertThat(ledger.schemaFor(new TenantRef(3, "UP"))).isEqualTo("tenant_up");
    }

    @Test
    void aFailingInsertNeverReachesTheSend_andTheRefIsSimplyUnrecorded() {
        doThrow(new IllegalStateException("db down")).when(repository).open(anyString(), any());

        LedgerRef ref = ledger.open(entry().build());

        assertThat(ref.recorded()).isFalse();
        assertThat(ref.trackingRef()).isNull();
        assertThatCode(() -> ledger.close(ref, LedgerOutcome.acceptedUntracked(null))).doesNotThrowAnyException();
        verify(repository, never()).close(anyString(), anyString(), any());
        assertThat(meters.counter("notification.ledger.write.failures", "operation", "open").count()).isEqualTo(1);
    }

    @Test
    void aFailingCloseIsSwallowed() {
        when(repository.close(anyString(), anyString(), any())).thenThrow(new IllegalStateException("timeout"));
        LedgerRef ref = ledger.open(entry().build());

        assertThatCode(() -> ledger.close(ref, LedgerOutcome.acceptedUntracked(null))).doesNotThrowAnyException();
    }

    @Test
    void closingPublishesTheSnapshotAndCountsIt() {
        LedgerSnapshot snapshot = snapshot("PENDING");
        when(repository.close(anyString(), anyString(), any())).thenReturn(Optional.of(snapshot));
        LedgerRef ref = ledger.open(entry().build());

        ledger.close(ref, LedgerOutcome.accepted(ProviderAcceptance.of("msg-1", "accepted")));

        ArgumentCaptor<NotificationLedgerRepository.Closing> closing =
                ArgumentCaptor.forClass(NotificationLedgerRepository.Closing.class);
        verify(repository).close(eq("tenant_mp"), eq(ref.uuid()), closing.capture());
        assertThat(closing.getValue().dispatchStatus()).isEqualTo("ACCEPTED");
        assertThat(closing.getValue().deliveryStatus()).isEqualTo("PENDING");
        assertThat(closing.getValue().providerMessageId()).isEqualTo("msg-1");
        assertThat(closing.getValue().dispatched()).isTrue();
        verify(publisher).publish(snapshot, 7);
        assertThat(meters.counter("notification.ledger.records", "type", "DAILY_REPORT", "channel", "WHATSAPP",
                "provider", "wa-provider", "status", "ACCEPTED").count()).isEqualTo(1);
    }

    @Test
    void anAcceptanceWithoutAnIdIsUntracked_andAFailureNotSent() {
        assertThat(LedgerOutcome.acceptedUntracked("flow_started").deliveryState()).isEqualTo(DeliveryState.NOT_TRACKED);
        assertThat(LedgerOutcome.accepted(ProviderAcceptance.of("x", null)).deliveryState()).isEqualTo(DeliveryState.PENDING);
        assertThat(LedgerOutcome.failed(DispatchStatus.FAILED_UPLOAD, null, null, null).deliveryState())
                .isEqualTo(DeliveryState.NOT_SENT);
        assertThat(LedgerOutcome.suppressed().deliveryState()).isEqualTo(DeliveryState.NOT_SENT);
    }

    @Test
    void anErrorMessageIsRedactedAndTruncated() {
        when(repository.close(anyString(), anyString(), any())).thenReturn(Optional.empty());
        LedgerRef ref = ledger.open(entry().build());

        ledger.close(ref, LedgerOutcome.failed(DispatchStatus.FAILED_DELIVERY, "SEND", "E",
                "rejected for " + PHONE + " " + "x".repeat(600)));

        ArgumentCaptor<NotificationLedgerRepository.Closing> closing =
                ArgumentCaptor.forClass(NotificationLedgerRepository.Closing.class);
        verify(repository).close(anyString(), anyString(), closing.capture());
        assertThat(closing.getValue().errorMessage()).doesNotContain(PHONE).hasSize(500);
    }

    @Test
    void whenDisabled_nothingIsWritten_butARefIsStillReturned() {
        NotificationLedger off = ledger(false);

        LedgerRef ref = off.open(entry().build());
        off.close(ref, LedgerOutcome.acceptedUntracked(null));
        off.recordOutcome(entry().build(), LedgerOutcome.suppressed());

        assertThat(ref.recorded()).isFalse();
        verifyNoInteractions(repository, publisher);
        assertThat(off.applyReceipt(receipt("x", "tenant_mp:" + java.util.UUID.randomUUID()))).isZero();
    }

    @Test
    void anEntryWithoutTypeOrChannelIsNotRecorded() {
        assertThat(ledger.open(entry().type(null).build()).recorded()).isFalse();
        verify(repository, never()).open(anyString(), any());
    }

    @Test
    void aReceiptWithOurTrackingRefGoesStraightToItsRow() {
        String uuid = "7d0f6c0a-1111-2222-3333-444455556666";
        when(repository.applyStatus(eq("tenant_up"), eq(uuid), any())).thenReturn(List.of(snapshot("DELIVERED")));

        assertThat(ledger.applyReceipt(receipt("sg-1", "tenant_up:" + uuid))).isEqualTo(1);
        verify(repository, never()).ledgerSchemas();
    }

    @Test
    void aReceiptWithoutOneIsLookedUpSchemaBySchema_stoppingAtTheFirstMatch() {
        when(repository.ledgerSchemas()).thenReturn(List.of("common_schema", "tenant_a", "tenant_b"));
        when(repository.applyStatus(anyString(), isNull(), any())).thenReturn(List.of());
        when(repository.applyStatus(eq("tenant_a"), isNull(), any())).thenReturn(List.of(snapshot("FAILED")));

        assertThat(ledger.applyReceipt(receipt("sg-2", null))).isEqualTo(1);
        verify(repository, never()).applyStatus(eq("tenant_b"), any(), any());
    }

    @Test
    void aForgedTrackingRefIsIgnored() {
        when(repository.ledgerSchemas()).thenReturn(List.of());

        assertThat(ledger.applyReceipt(receipt("sg-3", "tenant_x; DROP TABLE:abc"))).isZero();
        verify(repository, never()).applyStatus(anyString(), anyString(), any());
    }

    @Test
    void markingUnresolvedPublishesEachChangedRow() {
        when(repository.markUnresolved("tenant_mp", 72, 10)).thenReturn(List.of(snapshot("UNRESOLVED")));

        assertThat(ledger.markUnresolved("tenant_mp", 72, 10)).isEqualTo(1);
        verify(publisher).publish(any(), eq(7));
    }

    @Test
    void theTenantIdBehindASchemaIsCached() {
        ledger.tenantIdFor("tenant_mp");
        ledger.tenantIdFor("tenant_mp");

        verify(tenantRefResolver).resolve(null, "mp");
        assertThat(ledger.tenantIdFor("common_schema")).isNull();
    }

    private static DeliveryReceipt receipt(String id, String ref) {
        return new DeliveryReceipt("sendgrid", id, ref, DeliveryState.DELIVERED, "delivered", null, null,
                Instant.now(), null, null);
    }

    private static LedgerSnapshot snapshot(String delivery) {
        return new LedgerSnapshot("tenant_mp", "u-1", 1, 11L, "SECTION_OFFICER", "DAILY_REPORT", 6, "wa-provider",
                "ACCEPTED", null, delivery, null, 10, LocalDate.of(2026, 10, 5), null, null,
                Instant.now(), Instant.now(), null, null, null);
    }
}
