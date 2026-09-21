package org.arghyam.jalsoochak.message.channel.provider;

import java.util.Properties;

import org.arghyam.jalsoochak.message.config.MailProperties;
import org.arghyam.jalsoochak.message.dto.EmailProviderSettings;
import org.arghyam.jalsoochak.message.dto.TenantSecrets;
import org.arghyam.jalsoochak.message.enums.EmailProviderType;
import org.arghyam.jalsoochak.message.exception.ProviderNotUsableException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/**
 * PER-TENANT-PROVIDERS: builds one tenant's {@link SmtpMailSender}, and the
 * {@code JavaMailSenderImpl} behind it, from its stored settings and its decrypted password
 * (O2-2).
 *
 * <p>Registered unconditionally, unlike the system default bean in {@code SystemDefaultProviders}:
 * the factories exist so that several relays can coexist, so gating one on
 * {@code notification.mail.provider} — which now names only the <em>system default</em> provider
 * (O2-4) — would stop a state using its own relay because the platform sends through SendGrid.
 *
 * <p>The subject and body templates come from {@code notification.mail.smtp.templates.*}, not from
 * the tenant (O2-17); only the connection and the from address are its own. The host has already
 * been through {@code ProviderEndpointPolicy} by the time this runs, so what is left to check here
 * is the rest of the connection.
 *
 * <p>The session timeouts match {@code spring.mail.properties.mail.smtp.*} in
 * {@code application.yml}. They are not optional: this sender is called on the Kafka listener
 * thread, and a relay that accepts a connection and then stops responding would otherwise hold
 * that thread until the OS gave up.
 *
 * <p>Every check here is a build-time one, so a failure is a {@link ProviderNotUsableException}
 * that leaves the tenant on the system default with an ERROR rather than stopping its mail (O2-9).
 */
@Component
public class SmtpMailSenderFactory implements EmailSenderFactory {

    /**
     * The name {@code EmailProviderType.SMTP.getRequiredSecretNames()} declares, which is what
     * {@code TenantChannelProviders} has already resolved by the time {@link #create} is called.
     * Duplicated because the enum is kept byte-identical to tenant-service's twin;
     * {@code SmtpMailSenderFactoryTest} asserts the two agree.
     */
    static final String SECRET_PASSWORD = "password";

    /** The one port on which the connection is already encrypted before SMTP starts. */
    static final int IMPLICIT_TLS_PORT = 465;

    private static final String TIMEOUT_MS = "30000";

    private final MailProperties mailProperties;

    public SmtpMailSenderFactory(MailProperties mailProperties) {
        this.mailProperties = mailProperties;
    }

    @Override
    public EmailProviderType providerId() {
        return EmailProviderType.SMTP;
    }

    @Override
    public EmailSender create(EmailProviderSettings settings, TenantSecrets secrets) {
        EmailProviderSettings.Smtp block = settings == null ? null : settings.smtp();
        if (block == null) {
            throw new ProviderNotUsableException("email settings carry no 'smtp' block");
        }
        MailProperties.SmtpTemplates templates = platformTemplates();
        if (templates == null) {
            throw new ProviderNotUsableException(
                    "notification.mail.smtp.templates is not configured, so no relay can be used");
        }
        int port = requirePort(block.port());
        boolean startTls = Boolean.TRUE.equals(block.startTls());
        // Re-checked although tenant-service enforces it on write, for the reason
        // ProviderEndpointPolicy re-checks the host: a stored row can predate a rule. Sending a
        // state's SMTP password over an unencrypted connection is the failure this prevents, and
        // it is not one that shows up in a delivery report.
        if (!startTls && port != IMPLICIT_TLS_PORT) {
            throw new ProviderNotUsableException("smtp.startTls must be true on port " + port
                    + "; only port " + IMPLICIT_TLS_PORT + " may disable it, where TLS is implicit");
        }

        JavaMailSenderImpl javaMailSender = new JavaMailSenderImpl();
        javaMailSender.setHost(require(block.host(), "smtp.host"));
        javaMailSender.setPort(port);
        javaMailSender.setUsername(require(block.username(), "smtp.username"));
        javaMailSender.setPassword(secrets.get(SECRET_PASSWORD));
        javaMailSender.setProtocol("smtp");

        Properties session = javaMailSender.getJavaMailProperties();
        session.setProperty("mail.transport.protocol", "smtp");
        session.setProperty("mail.smtp.auth", "true");
        session.setProperty("mail.smtp.starttls.enable", String.valueOf(startTls));
        session.setProperty("mail.smtp.starttls.required", String.valueOf(startTls));
        session.setProperty("mail.smtp.ssl.enable", String.valueOf(!startTls));
        session.setProperty("mail.smtp.ssl.protocols", "TLSv1.2");
        session.setProperty("mail.smtp.connectiontimeout", TIMEOUT_MS);
        session.setProperty("mail.smtp.timeout", TIMEOUT_MS);
        session.setProperty("mail.smtp.writetimeout", TIMEOUT_MS);

        SmtpSettings resolved = new SmtpSettings(
                require(settings.fromAddress(), "fromAddress"),
                orPlatform(settings.logoImageUrl(), mailProperties.logoImageUrl()),
                templates);
        return new SmtpMailSender(resolved, javaMailSender);
    }

    private MailProperties.SmtpTemplates platformTemplates() {
        MailProperties.Smtp platform = mailProperties.smtp();
        return platform == null ? null : platform.templates();
    }

    private static int requirePort(Integer port) {
        if (port == null || port < 1 || port > 65535) {
            throw new ProviderNotUsableException("smtp.port must be between 1 and 65535");
        }
        return port;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ProviderNotUsableException(field + " is required but is not set");
        }
        return value.trim();
    }

    private static String orPlatform(String tenantValue, String platformValue) {
        return (tenantValue == null || tenantValue.isBlank()) ? platformValue : tenantValue.trim();
    }
}
