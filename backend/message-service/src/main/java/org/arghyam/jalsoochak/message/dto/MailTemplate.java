package org.arghyam.jalsoochak.message.dto;

/**
 * Canonical identifiers for transactional account emails.
 *
 * <p>Each adapter maps these values to its provider-specific representation:
 * SendGrid adapters resolve them to dynamic template IDs ({@code notification.mail.sendgrid.templates.*});
 * SMTP adapters resolve them to subject/body pairs ({@code notification.mail.smtp.templates.*}).
 */
public enum MailTemplate {
    PASSWORD_RESET,
    REINVITATION,
    DEFAULT_INVITATION,
    SUPER_USER_INVITATION,
    STATE_ADMIN_INVITATION
}
