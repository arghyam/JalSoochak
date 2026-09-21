package org.arghyam.jalsoochak.message.channel;

import org.arghyam.jalsoochak.message.config.MailProperties;

/**
 * PER-TENANT-PROVIDERS: everything one {@link SmtpMailSender} instance needs beyond its
 * {@code JavaMailSender}, assembled for it by whoever builds it.
 *
 * <p>The connection — host, port, username, password, STARTTLS — is deliberately absent: it is
 * held by the {@code JavaMailSender} the sender is constructed with, which
 * {@code SystemDefaultProviders} takes from Spring Boot's auto-configured {@code spring.mail.*}
 * bean and {@link SmtpMailSenderFactory} builds per tenant.
 *
 * <p>{@link #templates()} is {@code MailProperties}' own type rather than a copy, because by O2-17
 * the subject and body templates are <em>not</em> tenant data: they stay in this service's
 * {@code application.yml} and are the same text whichever relay carries them. Only the connection
 * and the sender's identity belong to the tenant. The type says so.
 *
 * @param fromAddress  the envelope and header sender
 * @param logoImageUrl merged into every body as {@code {logo_image}}
 * @param templates    the platform's subject and body templates; may be null, which fails at send
 *                     as it did before this was a parameter
 */
public record SmtpSettings(
        String fromAddress,
        String logoImageUrl,
        MailProperties.SmtpTemplates templates) {

    /** Identity only: a body template can carry anything, including a link minted for one user. */
    @Override
    public String toString() {
        return "SmtpSettings(fromAddress=" + fromAddress + ")";
    }
}
