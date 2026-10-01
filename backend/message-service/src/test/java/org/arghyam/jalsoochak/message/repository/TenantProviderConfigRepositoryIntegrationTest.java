package org.arghyam.jalsoochak.message.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSender;
import org.arghyam.jalsoochak.message.channel.provider.glific.GlificAuthService;
import org.arghyam.jalsoochak.message.dto.EmailProviderSettings;
import org.arghyam.jalsoochak.message.dto.MessagingAllowedHosts;
import org.arghyam.jalsoochak.message.dto.SmsProviderSettings;
import org.arghyam.jalsoochak.message.enums.EmailProviderType;
import org.arghyam.jalsoochak.message.enums.SmsProviderType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * PER-TENANT-PROVIDERS: integration tests for {@link TenantProviderConfigRepository} against a real
 * PostgreSQL.
 *
 * <p>Testcontainers rather than a mocked {@code JdbcTemplate}, because what is being tested is the
 * SQL: the {@code deleted_at IS NULL} predicate, the ordering that picks the newest row, and the
 * fact that the JSON tenant-service writes deserialises into these records. None of those would
 * fail against a mock.
 *
 * <p>The rows are written as raw JSON strings rather than serialised from a DTO, because they stand
 * in for what tenant-service wrote. A fixture built from this service's own records would pass
 * however the two shapes drifted.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("TenantProviderConfigRepository Integration Tests")
class TenantProviderConfigRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Suppress the @PostConstruct network calls these beans make at startup.
    @MockBean
    private GlificAuthService glificAuthService;

    @MockBean
    private WhatsAppSender whatsAppSender;

    @Autowired
    private TenantProviderConfigRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int TENANT_ID = 1; // seeded in test-schema.sql

    private static final String SENDGRID_JSON = """
            {"provider":"sendgrid","fromAddress":"noreply@mp.gov.in","fromName":"Jal Soochak MP",
             "logoImageUrl":"https://example.test/logo.png",
             "sendgrid":{"templates":{"passwordReset":"d-pw","reinvitation":"d-re",
                         "defaultInvitation":"d-def","superUserInvitation":"d-su",
                         "stateAdminInvitation":"d-sa"}}}
            """;

    private static final String SMTP_JSON = """
            {"provider":"smtp","fromAddress":"noreply@mp.gov.in","fromName":"Jal Soochak MP",
             "smtp":{"host":"smtp.mp.gov.in","port":587,"username":"mailer","startTls":true}}
            """;

    private static final String SMSCOUNTRY_JSON = """
            {"provider":"smscountry",
             "smscountry":{"senderId":"MPJLSK","dltPrincipalEntityId":"pe-1","dltTemplateId":"tpl-1",
                           "dltHeaderId":"hdr-1","otpTemplate":"Your OTP is {otp}."}}
            """;

    @AfterEach
    void cleanConfig() {
        jdbcTemplate.execute("DELETE FROM common_schema.tenant_config_master_table");
    }

    private void writeConfig(int tenantId, String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO common_schema.tenant_config_master_table (tenant_id, config_key, config_value)"
                        + " VALUES (?, ?, ?)", tenantId, key, value);
    }

    // ── email settings ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("SendGrid settings written by tenant-service deserialise in full")
    void sendGridSettingsDeserialise() {
        writeConfig(TENANT_ID, "EMAIL_PROVIDER_SETTINGS", SENDGRID_JSON);

        EmailProviderSettings settings = repository.findEmailSettings(TENANT_ID).orElseThrow();

        assertThat(settings.providerType()).isEqualTo(EmailProviderType.SENDGRID);
        assertThat(settings.fromAddress()).isEqualTo("noreply@mp.gov.in");
        assertThat(settings.fromName()).isEqualTo("Jal Soochak MP");
        assertThat(settings.logoImageUrl()).isEqualTo("https://example.test/logo.png");
        assertThat(settings.sendgrid().templates().passwordReset()).isEqualTo("d-pw");
        assertThat(settings.sendgrid().templates().stateAdminInvitation()).isEqualTo("d-sa");
        assertThat(settings.sendgrid().templates().isComplete()).isTrue();
        assertThat(settings.smtp()).isNull();
        assertThat(settings.blockForProvider()).isSameAs(settings.sendgrid());
    }

    @Test
    @DisplayName("SMTP settings written by tenant-service deserialise in full")
    void smtpSettingsDeserialise() {
        writeConfig(TENANT_ID, "EMAIL_PROVIDER_SETTINGS", SMTP_JSON);

        EmailProviderSettings settings = repository.findEmailSettings(TENANT_ID).orElseThrow();

        assertThat(settings.providerType()).isEqualTo(EmailProviderType.SMTP);
        assertThat(settings.smtp().host()).isEqualTo("smtp.mp.gov.in");
        assertThat(settings.smtp().port()).isEqualTo(587);
        assertThat(settings.smtp().username()).isEqualTo("mailer");
        assertThat(settings.smtp().startTls()).isTrue();
        assertThat(settings.sendgrid()).isNull();
    }

    @Test
    @DisplayName("SMS settings written by tenant-service deserialise in full")
    void smsSettingsDeserialise() {
        writeConfig(TENANT_ID, "SMS_PROVIDER_SETTINGS", SMSCOUNTRY_JSON);

        SmsProviderSettings settings = repository.findSmsSettings(TENANT_ID).orElseThrow();

        assertThat(settings.providerType()).isEqualTo(SmsProviderType.SMSCOUNTRY);
        assertThat(settings.smscountry().senderId()).isEqualTo("MPJLSK");
        assertThat(settings.smscountry().dltPrincipalEntityId()).isEqualTo("pe-1");
        assertThat(settings.smscountry().dltTemplateId()).isEqualTo("tpl-1");
        assertThat(settings.smscountry().dltHeaderId()).isEqualTo("hdr-1");
        assertThat(settings.smscountry().otpTemplateOrDefault()).isEqualTo("Your OTP is {otp}.");
    }

    @Test
    @DisplayName("an unset otpTemplate falls back to today's exact text")
    void unsetOtpTemplateFallsBackToTodaysText() {
        writeConfig(TENANT_ID, "SMS_PROVIDER_SETTINGS", """
                {"provider":"smscountry","smscountry":{"senderId":"MPJLSK",
                 "dltPrincipalEntityId":"pe-1","dltTemplateId":"tpl-1","dltHeaderId":"hdr-1"}}
                """);

        SmsProviderSettings settings = repository.findSmsSettings(TENANT_ID).orElseThrow();

        assertThat(settings.smscountry().otpTemplate()).isNull();
        assertThat(settings.smscountry().otpTemplateOrDefault())
                .isEqualTo(SmsProviderSettings.SmsCountry.DEFAULT_OTP_TEMPLATE);
    }

    @Test
    @DisplayName("a tenant with no settings reads as empty")
    void noSettingsReadsAsEmpty() {
        assertThat(repository.findEmailSettings(TENANT_ID)).isEmpty();
        assertThat(repository.findSmsSettings(TENANT_ID)).isEmpty();
    }

    @Test
    @DisplayName("a null tenant id reads as empty rather than querying")
    void nullTenantIdReadsAsEmpty() {
        assertThat(repository.findEmailSettings(null)).isEmpty();
        assertThat(repository.findSmsSettings(null)).isEmpty();
    }

    // ── soft delete and versioning ──────────────────────────────────────────────

    @Test
    @DisplayName("a soft-deleted settings row is invisible")
    void softDeletedSettingsAreInvisible() {
        // This is what DELETE /messaging-providers/{channel} does in tenant-service. A read that
        // ignored deleted_at would keep serving settings the tenant has explicitly turned off.
        writeConfig(TENANT_ID, "EMAIL_PROVIDER_SETTINGS", SENDGRID_JSON);
        jdbcTemplate.update("UPDATE common_schema.tenant_config_master_table SET deleted_at = NOW()"
                + " WHERE tenant_id = ? AND config_key = ?", TENANT_ID, "EMAIL_PROVIDER_SETTINGS");

        assertThat(repository.findEmailSettings(TENANT_ID)).isEmpty();
    }

    @Test
    @DisplayName("a live row beside a soft-deleted one is the one returned")
    void liveRowBesideASoftDeletedOneWins() {
        // The partial unique index lets a re-enable insert a fresh row beside the deleted one.
        writeConfig(TENANT_ID, "EMAIL_PROVIDER_SETTINGS", SMTP_JSON);
        jdbcTemplate.update("UPDATE common_schema.tenant_config_master_table SET deleted_at = NOW()"
                + " WHERE tenant_id = ? AND config_key = ?", TENANT_ID, "EMAIL_PROVIDER_SETTINGS");
        writeConfig(TENANT_ID, "EMAIL_PROVIDER_SETTINGS", SENDGRID_JSON);

        assertThat(repository.findEmailSettings(TENANT_ID).orElseThrow().providerType())
                .isEqualTo(EmailProviderType.SENDGRID);
    }

    @Test
    @DisplayName("one tenant's settings are never served to another")
    void settingsAreScopedToTheirTenant() {
        writeConfig(TENANT_ID, "EMAIL_PROVIDER_SETTINGS", SENDGRID_JSON);

        assertThat(repository.findEmailSettings(TENANT_ID)).isPresent();
        assertThat(repository.findEmailSettings(TENANT_ID + 99)).isEmpty();
    }

    // ── malformed rows ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a malformed settings row reads as empty rather than throwing")
    void malformedSettingsReadAsEmpty() {
        // A read failure must cost the tenant its own provider, not the message: this sits on the
        // path of a login OTP.
        writeConfig(TENANT_ID, "EMAIL_PROVIDER_SETTINGS", "{not json");

        assertThat(repository.findEmailSettings(TENANT_ID)).isEmpty();
    }

    @Test
    @DisplayName("a blank settings row reads as empty")
    void blankSettingsReadAsEmpty() {
        writeConfig(TENANT_ID, "EMAIL_PROVIDER_SETTINGS", "   ");

        assertThat(repository.findEmailSettings(TENANT_ID)).isEmpty();
    }

    @Test
    @DisplayName("an unknown provider parses to null rather than failing the row")
    void unknownProviderParsesToNull() {
        // A settings row written by a newer tenant-service. The row still parses, so the fallback
        // decision is taken by TenantChannelProviders with a provider name to log.
        writeConfig(TENANT_ID, "EMAIL_PROVIDER_SETTINGS",
                "{\"provider\":\"mailgun\",\"fromAddress\":\"noreply@mp.gov.in\"}");

        EmailProviderSettings settings = repository.findEmailSettings(TENANT_ID).orElseThrow();

        // The name survives — it is what the ERROR line and the outcome=fallback counter name —
        // while the type does not resolve, which is what sends the tenant to the system default.
        assertThat(settings.provider()).isEqualTo("mailgun");
        assertThat(settings.providerType()).isNull();
        assertThat(settings.blockForProvider()).isNull();
    }

    @Test
    @DisplayName("an unknown SMS provider parses to null rather than failing the row")
    void unknownSmsProviderParsesToNull() {
        writeConfig(TENANT_ID, "SMS_PROVIDER_SETTINGS",
                "{\"provider\":\"twilio\",\"smscountry\":null}");

        SmsProviderSettings settings = repository.findSmsSettings(TENANT_ID).orElseThrow();

        assertThat(settings.provider()).isEqualTo("twilio");
        assertThat(settings.providerType()).isNull();
    }

    @Test
    @DisplayName("an unknown property in a stored row is ignored, not a failure")
    void unknownPropertyIsIgnoredOnTheReadPath() {
        // The write side rejects these; the read side must not, or a field added by a newer
        // tenant-service would take a working tenant back to the system default.
        writeConfig(TENANT_ID, "EMAIL_PROVIDER_SETTINGS", """
                {"provider":"sendgrid","fromAddress":"noreply@mp.gov.in",
                 "someFieldAddedLater":"whatever",
                 "sendgrid":{"templates":{"passwordReset":"d-pw","reinvitation":"d-re",
                             "defaultInvitation":"d-def","superUserInvitation":"d-su",
                             "stateAdminInvitation":"d-sa"},"alsoNew":1}}
                """);

        EmailProviderSettings settings = repository.findEmailSettings(TENANT_ID).orElseThrow();

        assertThat(settings.providerType()).isEqualTo(EmailProviderType.SENDGRID);
        assertThat(settings.sendgrid().templates().isComplete()).isTrue();
    }

    @Test
    @DisplayName("settings never print a field that is half a credential")
    void settingsNeverPrintHalfACredential() {
        writeConfig(TENANT_ID, "EMAIL_PROVIDER_SETTINGS", SMTP_JSON);

        EmailProviderSettings settings = repository.findEmailSettings(TENANT_ID).orElseThrow();

        assertThat(settings.toString())
                .doesNotContain("mailer")
                .contains(EmailProviderType.SMTP.getWireName());
    }

    // ── allowlist ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the SMTP allowlist is read from the system tenant's config")
    void allowlistIsReadFromTheSystemTenant() {
        writeConfig(0, "MESSAGING_PROVIDER_ALLOWED_HOSTS",
                "{\"smtp\":[\"smtp.mp.gov.in\",\"*.nic.in\"]}");

        MessagingAllowedHosts hosts = repository.findAllowedHosts();

        assertThat(hosts.allowsSmtpHost("smtp.mp.gov.in")).isTrue();
        assertThat(hosts.allowsSmtpHost("mail.up.nic.in")).isTrue();
        assertThat(hosts.allowsSmtpHost("smtp.elsewhere.test")).isFalse();
    }

    @Test
    @DisplayName("an unset allowlist allows nothing")
    void unsetAllowlistAllowsNothing() {
        // Fail closed. A control that defaults to "everything" until someone populates it is not a
        // control, and this is the state a fresh environment is in.
        assertThat(repository.findAllowedHosts().allowsSmtpHost("smtp.mp.gov.in")).isFalse();
    }

    @Test
    @DisplayName("a malformed allowlist allows nothing")
    void malformedAllowlistAllowsNothing() {
        writeConfig(0, "MESSAGING_PROVIDER_ALLOWED_HOSTS", "{not json");

        assertThat(repository.findAllowedHosts().allowsSmtpHost("smtp.mp.gov.in")).isFalse();
    }

    @Test
    @DisplayName("an allowlist with no smtp list allows nothing")
    void allowlistWithNoSmtpListAllowsNothing() {
        writeConfig(0, "MESSAGING_PROVIDER_ALLOWED_HOSTS", "{}");

        assertThat(repository.findAllowedHosts().allowsSmtpHost("smtp.mp.gov.in")).isFalse();
    }

    @Test
    @DisplayName("a soft-deleted allowlist allows nothing")
    void softDeletedAllowlistAllowsNothing() {
        writeConfig(0, "MESSAGING_PROVIDER_ALLOWED_HOSTS", "{\"smtp\":[\"smtp.mp.gov.in\"]}");
        jdbcTemplate.update("UPDATE common_schema.tenant_config_master_table SET deleted_at = NOW()"
                + " WHERE tenant_id = 0");

        assertThat(repository.findAllowedHosts().allowsSmtpHost("smtp.mp.gov.in")).isFalse();
    }
}
