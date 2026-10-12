package org.arghyam.jalsoochak.message.service;

import org.arghyam.jalsoochak.message.channel.provider.EmailSender;
import org.arghyam.jalsoochak.message.channel.provider.ProviderAcceptance;
import org.arghyam.jalsoochak.message.channel.provider.TenantChannelProviders;
import org.arghyam.jalsoochak.message.dto.MailRequest;
import org.arghyam.jalsoochak.message.dto.TenantRef;
import org.arghyam.jalsoochak.message.ledger.DispatchStatus;
import org.arghyam.jalsoochak.message.ledger.LedgerChannel;
import org.arghyam.jalsoochak.message.ledger.LedgerEntry;
import org.arghyam.jalsoochak.message.ledger.LedgerOutcome;
import org.arghyam.jalsoochak.message.ledger.LedgerRef;
import org.arghyam.jalsoochak.message.ledger.NotificationLedger;
import org.arghyam.jalsoochak.message.ledger.NotificationType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * An account email given a ledger entry is recorded around its send: opened once the tenant's provider is
 * known, the uuid sent as the tracking reference, closed with the answer — and a failure still reaches
 * the caller unchanged, so its dead-letter path is untouched.
 */
@ExtendWith(MockitoExtension.class)
class AccountEmailServiceLedgerTest {

    private static final TenantRef MP = new TenantRef(7, "MP");
    private static final LedgerRef REF = new LedgerRef("tenant_mp", "7d0f6c0a-1111-2222-3333-444455556666", 7, true, 0);

    @Mock private TenantChannelProviders channelProviders;
    @Mock private NotificationLedger ledger;
    @Mock private EmailSender sender;

    private AccountEmailService service;

    @BeforeEach
    void setUp() {
        service = new AccountEmailService(channelProviders, ledger);
        lenient().when(channelProviders.emailFor(MP)).thenReturn(sender);
        lenient().when(sender.providerId()).thenReturn("mail-provider");
        lenient().when(ledger.schemaFor(MP)).thenReturn("tenant_mp");
        lenient().when(ledger.open(any())).thenReturn(REF);
    }

    private static LedgerEntry entry() {
        return LedgerEntry.builder().type(NotificationType.PASSWORD_RESET).eventType("SEND_PASSWORD_RESET_EMAIL")
                .adminUserId(9L).build();
    }

    @Test
    void recordsTheSendAndPassesTheTrackingRef() {
        ProviderAcceptance accepted = ProviderAcceptance.of("sg-1", "202");
        when(sender.send(any())).thenReturn(accepted);

        assertThat(service.sendPasswordResetEmail(MP, "user@example.org", "https://r", 30, entry())).isEqualTo(accepted);

        ArgumentCaptor<LedgerEntry> opened = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledger).open(opened.capture());
        assertThat(opened.getValue().channel()).isEqualTo(LedgerChannel.EMAIL);
        assertThat(opened.getValue().provider()).isEqualTo("mail-provider");
        assertThat(opened.getValue().tenantSchema()).isEqualTo("tenant_mp");
        assertThat(opened.getValue().recipient()).isEqualTo("user@example.org");
        assertThat(opened.getValue().adminUserId()).isEqualTo(9L);
        verify(sender).send(argThat((MailRequest r) -> REF.trackingRef().equals(r.trackingRef())));
        verify(ledger).close(eq(REF), argThat((LedgerOutcome o) -> o.status() == DispatchStatus.ACCEPTED
                && "sg-1".equals(o.acceptance().providerMessageId())));
    }

    @Test
    void aFailureIsRecordedAndRethrownUnchanged() {
        RuntimeException failure = new RuntimeException("SendGrid returned HTTP 401");
        when(sender.send(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.sendPasswordResetEmail(MP, "user@example.org", "https://r", 30, entry()))
                .isSameAs(failure);
        verify(ledger).close(eq(REF), argThat((LedgerOutcome o) -> o.status() == DispatchStatus.FAILED_DELIVERY));
    }

    @Test
    void withoutAnEntryNothingIsRecorded() {
        service.sendPasswordResetEmail(MP, "user@example.org", "https://r", 30);

        verify(sender).send(argThat((MailRequest r) -> r.trackingRef() == null));
        verifyNoInteractions(ledger);
    }
}
