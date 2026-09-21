package org.arghyam.jalsoochak.message.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.channel.EmailSender;
import org.arghyam.jalsoochak.message.channel.TenantChannelProviders;
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
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountEmailService {

    private final TenantChannelProviders channelProviders;

    public void sendInviteEmail(TenantRef tenant, String to, String name, String role,
                                String inviteLink, int expiryHours) {
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
        channelProviders.emailFor(tenant).send(new MailRequest(to, template, vars));
        log.info("[AccountEmailService] invite dispatched template={} role={}", template, role);
    }

    public void sendStateAdminInviteEmail(TenantRef tenant, String to, String name, String stateName,
                                          String inviteLink, int expiryHours) {
        if (stateName == null || stateName.isBlank()) {
            throw new IllegalArgumentException("stateName must not be null or blank for STATE_ADMIN invitations");
        }
        Map<String, Object> vars = Map.of(
                "name",            name != null ? name : "User",
                "state_name",      stateName,
                "activation_link", inviteLink,
                "expiry_hours",    expiryHours
        );
        channelProviders.emailFor(tenant)
                .send(new MailRequest(to, MailTemplate.STATE_ADMIN_INVITATION, vars));
        log.info("[AccountEmailService] state-admin invite dispatched");
    }

    public void sendReinviteEmail(TenantRef tenant, String to, String name, String inviteLink, int expiryHours) {
        Map<String, Object> vars = Map.of(
                "name",            name != null ? name : "User",
                "activation_link", inviteLink,
                "expiry_hours",    expiryHours
        );
        channelProviders.emailFor(tenant)
                .send(new MailRequest(to, MailTemplate.REINVITATION, vars));
        log.info("[AccountEmailService] reinvite dispatched");
    }

    public void sendPasswordResetEmail(TenantRef tenant, String to, String resetLink, int expiryMinutes) {
        Map<String, Object> vars = Map.of(
                "reset_link",     resetLink,
                "expiry_minutes", expiryMinutes
        );
        channelProviders.emailFor(tenant)
                .send(new MailRequest(to, MailTemplate.PASSWORD_RESET, vars));
        log.info("[AccountEmailService] password-reset dispatched");
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
