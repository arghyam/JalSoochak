package org.arghyam.jalsoochak.message.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.channel.provider.EmailSender;
import org.arghyam.jalsoochak.message.channel.provider.ProviderAcceptance;
import org.arghyam.jalsoochak.message.channel.provider.TenantChannelProviders;
import org.arghyam.jalsoochak.message.ledger.DispatchStatus;
import org.arghyam.jalsoochak.message.ledger.LedgerChannel;
import org.arghyam.jalsoochak.message.ledger.LedgerEntry;
import org.arghyam.jalsoochak.message.ledger.LedgerOutcome;
import org.arghyam.jalsoochak.message.ledger.LedgerRef;
import org.arghyam.jalsoochak.message.ledger.NotificationLedger;
import org.arghyam.jalsoochak.message.dto.MailRequest;
import org.arghyam.jalsoochak.message.dto.MailTemplate;
import org.arghyam.jalsoochak.message.dto.TenantRef;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Use-case service for transactional account lifecycle emails.
 *
 * <p>This class is provider-agnostic — it has no knowledge of SMTP or SendGrid. It delegates
 * delivery to the {@link EmailSender} port.
 *
 * <p>PER-TENANT-PROVIDERS: which implementation of that port, and which account behind it, is
 * {@link TenantChannelProviders}' decision alone (O2-1). Every method therefore takes the
 * {@link TenantRef} the event was normalised to and asks for that tenant's sender immediately
 * before sending, so a settings change takes effect without a restart; the lookup is cached, so it
 * costs nothing per mail. A tenant that has configured nothing, an event that carries no tenant
 * — a super-user invitation belongs to no state — and every send while the flag is off all get the
 * system default, which is today's behaviour exactly (O2-9).
 *
 * <p>DELIVERY-LEDGER: given a {@link LedgerEntry}, each send is recorded in the delivery ledger — the
 * row opened once the tenant's provider is known and before the provider is called, its uuid sent
 * along as the tracking reference, closed with the provider's answer or the failure. The overloads
 * without one record nothing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountEmailService {

    private final TenantChannelProviders channelProviders;
    private final NotificationLedger ledger;

    public void sendInviteEmail(TenantRef tenant, String to, String name, String role,
                                String inviteLink, int expiryHours) {
        sendInviteEmail(tenant, to, name, role, inviteLink, expiryHours, null);
    }

    public ProviderAcceptance sendInviteEmail(TenantRef tenant, String to, String name, String role,
                                              String inviteLink, int expiryHours, LedgerEntry ledgerEntry) {
        MailTemplate template = resolveInviteTemplate(role);
        if (template == MailTemplate.STATE_ADMIN_INVITATION) {
            log.warn("[AccountEmailService] sendInviteEmail called with role=STATE_ADMIN but no stateName provided; "
                    + "use sendStateAdminInviteEmail(tenant, to, name, stateName, inviteLink, expiryHours) instead");
            throw new IllegalArgumentException(
                    "STATE_ADMIN invitations require a stateName; use sendStateAdminInviteEmail() instead");
        }
        Map<String, Object> vars = Map.of(
                "name",            name != null ? name : "User",
                "activation_link", inviteLink,
                "expiry_hours",    expiryHours
        );
        ProviderAcceptance acceptance = dispatch(tenant, new MailRequest(to, template, vars), ledgerEntry);
        log.info("[AccountEmailService] invite dispatched template={} role={}", template, role);
        return acceptance;
    }

    public void sendStateAdminInviteEmail(TenantRef tenant, String to, String name, String stateName,
                                          String inviteLink, int expiryHours) {
        sendStateAdminInviteEmail(tenant, to, name, stateName, inviteLink, expiryHours, null);
    }

    public ProviderAcceptance sendStateAdminInviteEmail(TenantRef tenant, String to, String name, String stateName,
                                                        String inviteLink, int expiryHours, LedgerEntry ledgerEntry) {
        if (stateName == null || stateName.isBlank()) {
            throw new IllegalArgumentException("stateName must not be null or blank for STATE_ADMIN invitations");
        }
        Map<String, Object> vars = Map.of(
                "name",            name != null ? name : "User",
                "state_name",      stateName,
                "activation_link", inviteLink,
                "expiry_hours",    expiryHours
        );
        ProviderAcceptance acceptance =
                dispatch(tenant, new MailRequest(to, MailTemplate.STATE_ADMIN_INVITATION, vars), ledgerEntry);
        log.info("[AccountEmailService] state-admin invite dispatched");
        return acceptance;
    }

    public void sendReinviteEmail(TenantRef tenant, String to, String name, String inviteLink, int expiryHours) {
        sendReinviteEmail(tenant, to, name, inviteLink, expiryHours, null);
    }

    public ProviderAcceptance sendReinviteEmail(TenantRef tenant, String to, String name, String inviteLink,
                                                int expiryHours, LedgerEntry ledgerEntry) {
        Map<String, Object> vars = Map.of(
                "name",            name != null ? name : "User",
                "activation_link", inviteLink,
                "expiry_hours",    expiryHours
        );
        ProviderAcceptance acceptance =
                dispatch(tenant, new MailRequest(to, MailTemplate.REINVITATION, vars), ledgerEntry);
        log.info("[AccountEmailService] reinvite dispatched");
        return acceptance;
    }

    public void sendPasswordResetEmail(TenantRef tenant, String to, String resetLink, int expiryMinutes) {
        sendPasswordResetEmail(tenant, to, resetLink, expiryMinutes, null);
    }

    public ProviderAcceptance sendPasswordResetEmail(TenantRef tenant, String to, String resetLink,
                                                     int expiryMinutes, LedgerEntry ledgerEntry) {
        Map<String, Object> vars = Map.of(
                "reset_link",     resetLink,
                "expiry_minutes", expiryMinutes
        );
        ProviderAcceptance acceptance =
                dispatch(tenant, new MailRequest(to, MailTemplate.PASSWORD_RESET, vars), ledgerEntry);
        log.info("[AccountEmailService] password-reset dispatched");
        return acceptance;
    }

    /**
     * Sends through the tenant's provider, recording the send in the ledger when an entry is given. A
     * failure is recorded and then rethrown unchanged, so the caller's dead-letter path is untouched.
     */
    private ProviderAcceptance dispatch(TenantRef tenant, MailRequest request, LedgerEntry ledgerEntry) {
        EmailSender sender = channelProviders.emailFor(tenant);
        if (ledgerEntry == null) {
            return sender.send(request);
        }
        LedgerRef ref = ledger.open(ledgerEntry.toBuilder()
                .tenantSchema(ledger.schemaFor(tenant))
                .tenantId(tenant == null ? null : tenant.id())
                .channel(LedgerChannel.EMAIL)
                .provider(sender.providerId())
                .recipient(request.to())
                .templateRef(request.template().name())
                .metadata(Map.of("mailTemplate", request.template().name()))
                .build());
        try {
            ProviderAcceptance acceptance = sender.send(
                    new MailRequest(request.to(), request.template(), request.templateVariables(), ref.trackingRef()));
            ledger.close(ref, LedgerOutcome.accepted(acceptance));
            return acceptance;
        } catch (RuntimeException e) {
            ledger.close(ref, LedgerOutcome.failed(DispatchStatus.FAILED_DELIVERY, "SEND", e));
            throw e;
        }
    }

    private static MailTemplate resolveInviteTemplate(String role) {
        if (role == null) return MailTemplate.DEFAULT_INVITATION;
        return switch (role.toUpperCase()) {
            case "STATE_ADMIN" -> MailTemplate.STATE_ADMIN_INVITATION;
            case "SUPER_USER"  -> MailTemplate.SUPER_USER_INVITATION;
            default            -> MailTemplate.DEFAULT_INVITATION;
        };
    }
}
