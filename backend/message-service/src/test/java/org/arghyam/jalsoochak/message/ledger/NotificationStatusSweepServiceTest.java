package org.arghyam.jalsoochak.message.ledger;

import org.arghyam.jalsoochak.message.channel.provider.DeliveryReceipt;
import org.arghyam.jalsoochak.message.channel.provider.DeliveryState;
import org.arghyam.jalsoochak.message.channel.provider.EmailSender;
import org.arghyam.jalsoochak.message.channel.provider.SmsSender;
import org.arghyam.jalsoochak.message.channel.provider.TenantChannelProviders;
import org.arghyam.jalsoochak.message.dto.TenantRef;
import org.arghyam.jalsoochak.message.service.TenantRefResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Email and SMS rows are asked about through the account that sent them, and only when that account can answer. */
@ExtendWith(MockitoExtension.class)
class NotificationStatusSweepServiceTest {

    private static final TenantRef MP = new TenantRef(7, "MP");

    @Mock private NotificationLedger ledger;
    @Mock private NotificationLedgerRepository repository;
    @Mock private TenantChannelProviders channelProviders;
    @Mock private TenantRefResolver tenantRefResolver;
    @Mock private SmsSender smsSender;
    @Mock private EmailSender emailSender;

    private NotificationStatusSweepService sweep;

    @BeforeEach
    void setUp() {
        sweep = new NotificationStatusSweepService(ledger, repository, channelProviders, tenantRefResolver, 5, 72, 200, 5000);
        lenient().when(repository.ledgerSchemas()).thenReturn(List.of("tenant_mp"));
        lenient().when(tenantRefResolver.resolve(null, "mp")).thenReturn(MP);
        lenient().when(repository.pendingProviders(anyString(), anyInt(), anyInt())).thenReturn(List.of());
        lenient().when(channelProviders.smsFor(MP)).thenReturn(smsSender);
        lenient().when(channelProviders.emailFor(MP)).thenReturn(emailSender);
    }

    @Test
    void asksTheTenantsOwnSmsAccount_andAppliesWhatItSays() {
        when(repository.pendingProviders("tenant_mp", LedgerChannel.SMS.id(), 72)).thenReturn(List.of("smscountry"));
        when(smsSender.providerId()).thenReturn("smscountry");
        when(smsSender.supportsStatusLookup()).thenReturn(true);
        Instant sent = Instant.parse("2026-10-05T10:00:00Z");
        when(repository.pendingForSweep("tenant_mp", LedgerChannel.SMS.id(), "smscountry", 5, 72, 200))
                .thenReturn(List.of(new NotificationLedgerRepository.PendingRow("u1", "sms-1", sent)));
        DeliveryReceipt delivered = new DeliveryReceipt("smscountry", "sms-1", null, DeliveryState.DELIVERED,
                "Delivered", null, null, sent, null, null);
        when(smsSender.lookupStatuses(Set.of("sms-1"), sent, sent)).thenReturn(List.of(delivered));
        when(ledger.applyReceipt("tenant_mp", delivered)).thenReturn(1);

        assertThat(sweep.sweep()).isEqualTo(1);
        verify(ledger).markUnresolved("tenant_mp", 72, 5000);
    }

    @Test
    void skipsRowsSentThroughAProviderTheTenantNoLongerUses() {
        when(repository.pendingProviders("tenant_mp", LedgerChannel.EMAIL.id(), 72)).thenReturn(List.of("sendgrid"));
        when(emailSender.providerId()).thenReturn("smtp");

        sweep.sweep();

        verify(repository, never()).pendingForSweep(anyString(), eq(LedgerChannel.EMAIL.id()), anyString(),
                anyInt(), anyInt(), anyInt());
    }

    @Test
    void skipsAProviderThatCannotBeAsked() {
        when(repository.pendingProviders("tenant_mp", LedgerChannel.EMAIL.id(), 72)).thenReturn(List.of("sendgrid"));
        when(emailSender.providerId()).thenReturn("sendgrid");
        when(emailSender.supportsStatusLookup()).thenReturn(false);

        sweep.sweep();

        verify(emailSender, never()).lookupStatuses(any(), any(), any());
    }

    @Test
    void ignoresAReportForAMessageItDidNotAskAbout() {
        when(repository.pendingProviders("tenant_mp", LedgerChannel.SMS.id(), 72)).thenReturn(List.of("smscountry"));
        when(smsSender.providerId()).thenReturn("smscountry");
        when(smsSender.supportsStatusLookup()).thenReturn(true);
        when(repository.pendingForSweep(anyString(), anyInt(), anyString(), anyInt(), anyInt(), anyInt()))
                .thenReturn(List.of(new NotificationLedgerRepository.PendingRow("u1", "sms-1", Instant.now())));
        when(smsSender.lookupStatuses(any(), any(), any())).thenReturn(List.of(new DeliveryReceipt("smscountry",
                "someone-else", null, DeliveryState.FAILED, "Failed", null, null, null, null, null)));

        sweep.sweep();

        verify(ledger, never()).applyReceipt(anyString(), any());
    }

    @Test
    void platformRowsAreAskedThroughTheSystemDefault() {
        when(repository.ledgerSchemas()).thenReturn(List.of("common_schema"));
        when(repository.pendingProviders("common_schema", LedgerChannel.EMAIL.id(), 72)).thenReturn(List.of("sendgrid"));
        when(channelProviders.emailFor(TenantRef.NONE)).thenReturn(emailSender);
        when(emailSender.providerId()).thenReturn("sendgrid");

        sweep.sweep();

        verify(channelProviders).emailFor(TenantRef.NONE);
    }
}
