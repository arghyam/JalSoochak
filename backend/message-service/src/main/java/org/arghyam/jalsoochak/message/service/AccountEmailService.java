package org.arghyam.jalsoochak.message.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.channel.EmailSender;
import org.arghyam.jalsoochak.message.dto.MailRequest;
import org.arghyam.jalsoochak.message.dto.MailTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Use-case service for transactional account lifecycle emails.
 *
 * <p>This class is provider-agnostic — it has no knowledge of SMTP or SendGrid.
 * It delegates delivery to the {@link EmailSender} port, whose implementation
 * is determined solely by the {@code notification.mail.provider} config property.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountEmailService {

    private final EmailSender mailSender;

    public void sendInviteEmail(String to, String name, String role, String inviteLink, int expiryHours) {
        MailTemplate template = resolveInviteTemplate(role);
        Map<String, Object> vars = new HashMap<>();
        vars.put("name", name != null ? name : "User");
        vars.put("activation_link", inviteLink);
        vars.put("expiry_hours", expiryHours);
        if (template == MailTemplate.STATE_ADMIN_INVITATION) {
            vars.put("state_name", "");  // fallback until event carries stateName
        }
        mailSender.send(new MailRequest(to, template, vars));
        log.info("[AccountEmailService] invite dispatched template={} role={}", template, role);
    }

    public void sendStateAdminInviteEmail(String to, String name, String stateName,
                                          String inviteLink, int expiryHours) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("name", name != null ? name : "User");
        vars.put("state_name", stateName != null ? stateName : "");
        vars.put("activation_link", inviteLink);
        vars.put("expiry_hours", expiryHours);
        mailSender.send(new MailRequest(to, MailTemplate.STATE_ADMIN_INVITATION, vars));
        log.info("[AccountEmailService] state-admin invite dispatched");
    }

    public void sendReinviteEmail(String to, String name, String inviteLink, int expiryHours) {
        Map<String, Object> vars = Map.of(
                "name",            name != null ? name : "User",
                "activation_link", inviteLink,
                "expiry_hours",    expiryHours
        );
        mailSender.send(new MailRequest(to, MailTemplate.REINVITATION, vars));
        log.info("[AccountEmailService] reinvite dispatched");
    }

    public void sendPasswordResetEmail(String to, String resetLink, int expiryMinutes) {
        Map<String, Object> vars = Map.of(
                "reset_link",     resetLink,
                "expiry_minutes", expiryMinutes
        );
        mailSender.send(new MailRequest(to, MailTemplate.PASSWORD_RESET, vars));
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
