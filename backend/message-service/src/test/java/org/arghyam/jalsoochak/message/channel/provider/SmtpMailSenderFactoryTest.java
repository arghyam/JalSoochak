package org.arghyam.jalsoochak.message.channel.provider;

import java.util.Map;
import java.util.Properties;

import org.arghyam.jalsoochak.message.config.MailProperties;
import org.arghyam.jalsoochak.message.dto.EmailProviderSettings;
import org.arghyam.jalsoochak.message.dto.TenantSecrets;
import org.arghyam.jalsoochak.message.enums.EmailProviderType;
import org.arghyam.jalsoochak.message.enums.MessagingChannel;
import org.arghyam.jalsoochak.message.exception.ProviderNotUsableException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PER-TENANT-PROVIDERS: tests for {@link SmtpMailSenderFactory}.
 *
 * <p>Two tenants configured with different relays get separate {@code JavaMailSenderImpl}s
 * carrying their own host, credentials and TLS settings — never each other's. There is no SMTP
 * server to point WireMock at, so the built relay is inspected directly; what it is configured
 * with is exactly what {@code SmtpMailSender} would connect to.
 *
 * <p>The rest assert O2-9's half of the contract, that a settings row which cannot produce a
 * working sender raises {@link ProviderNotUsableException} while it is being built, so
 * {@code TenantChannelProviders} can answer with the system default instead of mail that fails
 * per message.
 */
class SmtpMailSenderFactoryTest {

    private static final String PLATFORM_LOGO = "https://platform/logo.png";
    private static final String TENANT_A_PASSWORD = "tenantAPassword";
    private static final String TENANT_B_PASSWORD = "tenantBPassword";

    private final SmtpMailSenderFactory factory = new SmtpMailSenderFactory(platformProperties());

    @Test
    void providerId_isSmtp() {
        assertThat(factory.providerId()).isEqualTo(EmailProviderType.SMTP);
    }

    @Test
    void secretNameConstants_matchTheOnesTheProviderTypeDeclares() {
        // The factory duplicates the name because the enum is a copy of tenant-service's, which is
        // what the secret store writes against. If the two drift, every configured tenant falls back.
        assertThat(EmailProviderType.SMTP.getRequiredSecretNames())
                .containsExactly(SmtpMailSenderFactory.SECRET_PASSWORD);
    }

    @Test
    void create_twoTenants_eachGetsItsOwnRelay() {
        EmailSender tenantA = factory.create(
                settings("smtp.mp.gov.in", 587, "mp-mailer", true, "noreply@mp.gov.in", null),
                secrets(TENANT_A_PASSWORD));
        EmailSender tenantB = factory.create(
                settings("smtp.tr.gov.in", 465, "tr-mailer", false, "noreply@tr.gov.in", null),
                secrets(TENANT_B_PASSWORD));

        JavaMailSenderImpl relayA = relayOf(tenantA);
        assertThat(relayA.getHost()).isEqualTo("smtp.mp.gov.in");
        assertThat(relayA.getPort()).isEqualTo(587);
        assertThat(relayA.getUsername()).isEqualTo("mp-mailer");
        assertThat(relayA.getPassword()).isEqualTo(TENANT_A_PASSWORD);

        JavaMailSenderImpl relayB = relayOf(tenantB);
        assertThat(relayB.getHost()).isEqualTo("smtp.tr.gov.in");
        assertThat(relayB.getPort()).isEqualTo(465);
        assertThat(relayB.getUsername()).isEqualTo("tr-mailer");
        assertThat(relayB.getPassword()).isEqualTo(TENANT_B_PASSWORD);

        assertThat(relayA).isNotSameAs(relayB);
    }

    @Test
    void create_startTls_enablesStarttlsAndLeavesImplicitTlsOff() {
        Properties session = sessionOf(factory.create(
                settings("smtp.mp.gov.in", 587, "mp-mailer", true, "noreply@mp.gov.in", null),
                secrets(TENANT_A_PASSWORD)));

        assertThat(session.getProperty("mail.smtp.auth")).isEqualTo("true");
        assertThat(session.getProperty("mail.smtp.starttls.enable")).isEqualTo("true");
        assertThat(session.getProperty("mail.smtp.starttls.required")).isEqualTo("true");
        assertThat(session.getProperty("mail.smtp.ssl.enable")).isEqualTo("false");
    }

    @Test
    void create_implicitTlsPort_enablesSslInsteadOfStarttls() {
        Properties session = sessionOf(factory.create(
                settings("smtp.tr.gov.in", 465, "tr-mailer", false, "noreply@tr.gov.in", null),
                secrets(TENANT_B_PASSWORD)));

        assertThat(session.getProperty("mail.smtp.starttls.enable")).isEqualTo("false");
        assertThat(session.getProperty("mail.smtp.ssl.enable")).isEqualTo("true");
        assertThat(session.getProperty("mail.smtp.ssl.protocols")).isEqualTo("TLSv1.2");
    }

    @Test
    void create_setsSessionTimeouts_soASilentRelayCannotHoldTheListenerThread() {
        Properties session = sessionOf(factory.create(
                settings("smtp.mp.gov.in", 587, "mp-mailer", true, "noreply@mp.gov.in", null),
                secrets(TENANT_A_PASSWORD)));

        assertThat(session.getProperty("mail.smtp.connectiontimeout")).isEqualTo("30000");
        assertThat(session.getProperty("mail.smtp.timeout")).isEqualTo("30000");
        assertThat(session.getProperty("mail.smtp.writetimeout")).isEqualTo("30000");
    }

    @Test
    void create_plaintextPortWithoutStartTls_isRefused() {
        // Re-checked although tenant-service enforces it on write: a stored row can predate the
        // rule, and sending a state's SMTP password in the clear does not show up as a failure.
        assertThatThrownBy(() -> factory.create(
                settings("smtp.mp.gov.in", 587, "mp-mailer", false, "noreply@mp.gov.in", null),
                secrets(TENANT_A_PASSWORD)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("smtp.startTls must be true on port 587");
    }

    @Test
    void create_nullStartTls_isTreatedAsOff_andRefusedOnAPlaintextPort() {
        assertThatThrownBy(() -> factory.create(
                settings("smtp.mp.gov.in", 587, "mp-mailer", null, "noreply@mp.gov.in", null),
                secrets(TENANT_A_PASSWORD)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("smtp.startTls");
    }

    @Test
    void create_subjectAndBodyTemplatesComeFromThePlatform_notFromTheTenant() {
        // O2-17: only the connection and the sender's identity belong to the tenant.
        SmtpSettings settings = settingsOf(factory.create(
                settings("smtp.mp.gov.in", 587, "mp-mailer", true, "noreply@mp.gov.in", null),
                secrets(TENANT_A_PASSWORD)));

        assertThat(settings.templates()).isSameAs(platformTemplates());
        assertThat(settings.fromAddress()).isEqualTo("noreply@mp.gov.in");
    }

    @Test
    void create_blankLogo_fallsBackToThePlatformOne() {
        SmtpSettings blank = settingsOf(factory.create(
                settings("smtp.mp.gov.in", 587, "mp-mailer", true, "noreply@mp.gov.in", "  "),
                secrets(TENANT_A_PASSWORD)));
        assertThat(blank.logoImageUrl()).isEqualTo(PLATFORM_LOGO);

        SmtpSettings own = settingsOf(factory.create(
                settings("smtp.mp.gov.in", 587, "mp-mailer", true, "noreply@mp.gov.in",
                        "https://mp/logo.png"),
                secrets(TENANT_A_PASSWORD)));
        assertThat(own.logoImageUrl()).isEqualTo("https://mp/logo.png");
    }

    @Test
    void create_settingsWithoutSmtpBlock_isRefused() {
        EmailProviderSettings noBlock = new EmailProviderSettings(
                EmailProviderType.SMTP.getWireName(), "noreply@mp.gov.in", "MP Jal", null, null, null);

        assertThatThrownBy(() -> factory.create(noBlock, secrets(TENANT_A_PASSWORD)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("smtp");
    }

    @Test
    void create_platformWithNoTemplates_isRefused() {
        // Nothing to put in the mail. A refusal leaves the tenant on the system default with one
        // ERROR rather than an IllegalStateException per message.
        SmtpMailSenderFactory noTemplates = new SmtpMailSenderFactory(
                new MailProperties("sendgrid", "noreply@example.com", "Jalsoochak", PLATFORM_LOGO,
                        null, null));

        assertThatThrownBy(() -> noTemplates.create(
                settings("smtp.mp.gov.in", 587, "mp-mailer", true, "noreply@mp.gov.in", null),
                secrets(TENANT_A_PASSWORD)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("notification.mail.smtp.templates");
    }

    @Test
    void create_incompleteConnection_isRefused() {
        assertThatThrownBy(() -> factory.create(
                settings("   ", 587, "mp-mailer", true, "noreply@mp.gov.in", null),
                secrets(TENANT_A_PASSWORD)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("smtp.host");

        assertThatThrownBy(() -> factory.create(
                settings("smtp.mp.gov.in", 587, null, true, "noreply@mp.gov.in", null),
                secrets(TENANT_A_PASSWORD)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("smtp.username");

        assertThatThrownBy(() -> factory.create(
                settings("smtp.mp.gov.in", null, "mp-mailer", true, "noreply@mp.gov.in", null),
                secrets(TENANT_A_PASSWORD)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("smtp.port");

        assertThatThrownBy(() -> factory.create(
                settings("smtp.mp.gov.in", 70000, "mp-mailer", true, "noreply@mp.gov.in", null),
                secrets(TENANT_A_PASSWORD)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("smtp.port");
    }

    @Test
    void create_blankFromAddress_isRefused() {
        assertThatThrownBy(() -> factory.create(
                settings("smtp.mp.gov.in", 587, "mp-mailer", true, "   ", null),
                secrets(TENANT_A_PASSWORD)))
                .isInstanceOf(ProviderNotUsableException.class)
                .hasMessageContaining("fromAddress");
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static MailProperties.SmtpTemplates PLATFORM_TEMPLATES;

    private static MailProperties.SmtpTemplates platformTemplates() {
        if (PLATFORM_TEMPLATES == null) {
            MailProperties.SmtpTemplate template =
                    new MailProperties.SmtpTemplate("Subject", "Body {logo_image}");
            PLATFORM_TEMPLATES = new MailProperties.SmtpTemplates(
                    template, template, template, template, template);
        }
        return PLATFORM_TEMPLATES;
    }

    private static MailProperties platformProperties() {
        return new MailProperties("smtp", "noreply@example.com", "Jalsoochak", PLATFORM_LOGO,
                null, new MailProperties.Smtp(platformTemplates()));
    }

    private static EmailProviderSettings settings(String host, Integer port, String username,
            Boolean startTls, String fromAddress, String logoImageUrl) {
        return new EmailProviderSettings(EmailProviderType.SMTP.getWireName(), fromAddress, "MP Jal",
                logoImageUrl, null,
                new EmailProviderSettings.Smtp(host, port, username, startTls));
    }

    private static TenantSecrets secrets(String password) {
        return TenantSecrets.of(MessagingChannel.EMAIL,
                Map.of(SmtpMailSenderFactory.SECRET_PASSWORD, password));
    }

    private static JavaMailSenderImpl relayOf(EmailSender sender) {
        return (JavaMailSenderImpl) ReflectionTestUtils.getField(sender, "javaMailSender");
    }

    private static Properties sessionOf(EmailSender sender) {
        return relayOf(sender).getJavaMailProperties();
    }

    private static SmtpSettings settingsOf(EmailSender sender) {
        return (SmtpSettings) ReflectionTestUtils.getField(sender, "settings");
    }
}
