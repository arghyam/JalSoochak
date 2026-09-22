package org.arghyam.jalsoochak.tenant.dto.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.stream.Collectors;

import org.arghyam.jalsoochak.tenant.enums.EmailProviderType;
import org.arghyam.jalsoochak.tenant.enums.SmsProviderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * MESSAGING-PROVIDER-SETTINGS: the field-shape rules on the two settings DTOs, and the one Jackson
 * rule that matters most — an unknown property is a 400, not a silently dropped field.
 *
 * <p>The ObjectMapper here has {@code FAIL_ON_UNKNOWN_PROPERTIES} explicitly <em>disabled</em>,
 * matching Spring Boot's auto-configured one. That is the whole point: under that mapper
 * {@code @JsonIgnoreProperties(ignoreUnknown = false)} is inert, so these tests would pass on a
 * bare mapper while the running service silently dropped the property. What they assert is that
 * the {@code @JsonAnySetter} guard rejects it regardless of how the mapper is configured.
 */
@DisplayName("Messaging provider settings DTO Tests")
class MessagingProviderConfigDTOTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static Validator validator;

    private static Validator validator() {
        if (validator == null) {
            try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
                validator = factory.getValidator();
            }
        }
        return validator;
    }

    private static Set<String> violationPaths(Object dto) {
        return validator().validate(dto).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    private static EmailProviderConfigDTO.SmtpSettings smtpSettings() {
        return EmailProviderConfigDTO.SmtpSettings.builder()
                .host("smtp.mp.gov.in").port(587).username("jalsoochak").startTls(true).build();
    }

    private static EmailProviderConfigDTO.SendGridSettings sendGridSettings() {
        return EmailProviderConfigDTO.SendGridSettings.builder()
                .templates(EmailProviderConfigDTO.Templates.builder()
                        .passwordReset("d-1").reinvitation("d-2").defaultInvitation("d-3")
                        .superUserInvitation("d-4").stateAdminInvitation("d-5").build())
                .build();
    }

    private static SmsProviderConfigDTO.SmsCountrySettings smsCountrySettings() {
        return SmsProviderConfigDTO.SmsCountrySettings.builder()
                .senderId("JLSCHK").dltPrincipalEntityId("PE-1").dltTemplateId("DT-1").dltHeaderId("DH-1")
                .build();
    }

    @Nested
    @DisplayName("EmailProviderConfigDTO")
    class Email {

        @Test
        @DisplayName("A complete SMTP value has no violations")
        void validSmtpValue() {
            EmailProviderConfigDTO dto = EmailProviderConfigDTO.builder()
                    .provider(EmailProviderType.SMTP)
                    .fromAddress("no-reply@mp.gov.in")
                    .fromName("Jal Soochak MP")
                    .smtp(smtpSettings())
                    .build();

            assertThat(violationPaths(dto)).isEmpty();
        }

        @Test
        @DisplayName("A complete SendGrid value has no violations")
        void validSendGridValue() {
            EmailProviderConfigDTO dto = EmailProviderConfigDTO.builder()
                    .provider(EmailProviderType.SENDGRID)
                    .fromAddress("no-reply@mp.gov.in")
                    .sendgrid(sendGridSettings())
                    .build();

            assertThat(violationPaths(dto)).isEmpty();
        }

        @Test
        @DisplayName("provider and fromAddress are required")
        void providerAndFromAddressRequired() {
            assertThat(violationPaths(new EmailProviderConfigDTO()))
                    .contains("provider", "fromAddress");
        }

        @Test
        @DisplayName("fromAddress must be an email address")
        void fromAddressMustBeAnEmail() {
            EmailProviderConfigDTO dto = EmailProviderConfigDTO.builder()
                    .provider(EmailProviderType.SMTP).fromAddress("not-an-address").smtp(smtpSettings())
                    .build();

            assertThat(violationPaths(dto)).contains("fromAddress");
        }

        @Test
        @DisplayName("Every SendGrid template id is required")
        void everyTemplateIdRequired() {
            EmailProviderConfigDTO dto = EmailProviderConfigDTO.builder()
                    .provider(EmailProviderType.SENDGRID)
                    .fromAddress("no-reply@mp.gov.in")
                    .sendgrid(EmailProviderConfigDTO.SendGridSettings.builder()
                            .templates(EmailProviderConfigDTO.Templates.builder()
                                    .passwordReset("d-1").build())
                            .build())
                    .build();

            assertThat(violationPaths(dto)).contains(
                    "sendgrid.templates.reinvitation",
                    "sendgrid.templates.defaultInvitation",
                    "sendgrid.templates.superUserInvitation",
                    "sendgrid.templates.stateAdminInvitation");
        }

        @Test
        @DisplayName("SMTP host, port, username and startTls are all required")
        void smtpFieldsRequired() {
            EmailProviderConfigDTO dto = EmailProviderConfigDTO.builder()
                    .provider(EmailProviderType.SMTP).fromAddress("no-reply@mp.gov.in")
                    .smtp(new EmailProviderConfigDTO.SmtpSettings())
                    .build();

            assertThat(violationPaths(dto))
                    .contains("smtp.host", "smtp.port", "smtp.username", "smtp.startTls");
        }

        @Test
        @DisplayName("An out-of-range SMTP port is rejected")
        void smtpPortRange() {
            EmailProviderConfigDTO.SmtpSettings smtp = smtpSettings();
            smtp.setPort(70000);
            EmailProviderConfigDTO dto = EmailProviderConfigDTO.builder()
                    .provider(EmailProviderType.SMTP).fromAddress("no-reply@mp.gov.in").smtp(smtp).build();

            assertThat(violationPaths(dto)).contains("smtp.port");
        }

        @Test
        @DisplayName("A credential smuggled into the settings JSON is rejected, not ignored")
        void credentialInSettingsRejected() {
            String json = """
                    {"provider":"smtp","fromAddress":"no-reply@mp.gov.in",
                     "smtp":{"host":"smtp.mp.gov.in","port":587,"username":"u","startTls":true,
                              "password":"hunter2"}}
                    """;

            assertThatThrownBy(() -> MAPPER.readValue(json, EmailProviderConfigDTO.class))
                    .isInstanceOf(JsonMappingException.class)
                    .rootCause().isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("password")
                    // The name, never the value.
                    .hasMessageNotContaining("hunter2");
        }

        @Test
        @DisplayName("An unknown top-level property is rejected")
        void unknownPropertyRejected() {
            String json = """
                    {"provider":"smtp","fromAddress":"no-reply@mp.gov.in","apiKeyRef":"env:SOMETHING",
                     "smtp":{"host":"smtp.mp.gov.in","port":587,"username":"u","startTls":true}}
                    """;

            assertThatThrownBy(() -> MAPPER.readValue(json, EmailProviderConfigDTO.class))
                    .isInstanceOf(JsonMappingException.class)
                    .rootCause().isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("apiKeyRef");
        }

        @Test
        @DisplayName("provider round-trips as its lower-case wire name and binds case-insensitively")
        void providerWireForm() throws Exception {
            EmailProviderConfigDTO dto = EmailProviderConfigDTO.builder()
                    .provider(EmailProviderType.SENDGRID).fromAddress("a@b.in").sendgrid(sendGridSettings())
                    .build();

            assertThat(MAPPER.writeValueAsString(dto)).contains("\"provider\":\"sendgrid\"");
            assertThat(MAPPER.readValue("{\"provider\":\"SMTP\",\"fromAddress\":\"a@b.in\"}",
                    EmailProviderConfigDTO.class).getProvider()).isEqualTo(EmailProviderType.SMTP);
        }

        @Test
        @DisplayName("An unsupported provider is rejected on binding")
        void unsupportedProviderRejected() {
            assertThatThrownBy(() -> MAPPER.readValue(
                    "{\"provider\":\"mailgun\",\"fromAddress\":\"a@b.in\"}", EmailProviderConfigDTO.class))
                    .hasMessageContaining("mailgun");
        }
    }

    @Nested
    @DisplayName("SmsProviderConfigDTO")
    class Sms {

        @Test
        @DisplayName("A complete SMSCountry value has no violations")
        void validValue() {
            SmsProviderConfigDTO dto = SmsProviderConfigDTO.builder()
                    .provider(SmsProviderType.SMSCOUNTRY).smscountry(smsCountrySettings()).build();

            assertThat(violationPaths(dto)).isEmpty();
        }

        @Test
        @DisplayName("The sender id and every DLT registration is required")
        void senderAndDltIdsRequired() {
            SmsProviderConfigDTO dto = SmsProviderConfigDTO.builder()
                    .provider(SmsProviderType.SMSCOUNTRY)
                    .smscountry(new SmsProviderConfigDTO.SmsCountrySettings())
                    .build();

            assertThat(violationPaths(dto)).contains(
                    "smscountry.senderId",
                    "smscountry.dltPrincipalEntityId",
                    "smscountry.dltTemplateId",
                    "smscountry.dltHeaderId");
        }

        @Test
        @DisplayName("An over-length OTP template is rejected")
        void otpTemplateLengthCapped() {
            SmsProviderConfigDTO.SmsCountrySettings settings = smsCountrySettings();
            settings.setOtpTemplate("{otp} ".repeat(SmsProviderConfigDTO.MAX_OTP_TEMPLATE_LENGTH));
            SmsProviderConfigDTO dto = SmsProviderConfigDTO.builder()
                    .provider(SmsProviderType.SMSCOUNTRY).smscountry(settings).build();

            assertThat(violationPaths(dto)).contains("smscountry.otpTemplate");
        }

        @Test
        @DisplayName("The default OTP template is today's message, with named placeholders")
        void defaultOtpTemplateMatchesTodaysMessage() {
            // Character-for-character what SmsCountryService.MESSAGE_TEMPLATE sends, with %s and %d
            // rewritten as {otp} and {expiryMinutes}. A tenant that sets no template keeps it.
            assertThat(SmsProviderConfigDTO.DEFAULT_OTP_TEMPLATE).isEqualTo(
                    "Your OTP for Jalsoochak login is {otp}. Do not share this OTP. "
                            + "Valid for {expiryMinutes} minutes.");
        }

        @Test
        @DisplayName("A credential smuggled into the settings JSON is rejected, not ignored")
        void credentialInSettingsRejected() {
            String json = """
                    {"provider":"smscountry",
                     "smscountry":{"senderId":"JLSCHK","dltPrincipalEntityId":"PE-1","dltTemplateId":"DT-1",
                                   "dltHeaderId":"DH-1","authToken":"hunter2"}}
                    """;

            assertThatThrownBy(() -> MAPPER.readValue(json, SmsProviderConfigDTO.class))
                    .isInstanceOf(JsonMappingException.class)
                    .rootCause().isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("authToken")
                    .hasMessageNotContaining("hunter2");
        }

        @Test
        @DisplayName("An unsupported provider is rejected on binding")
        void unsupportedProviderRejected() {
            assertThatThrownBy(() -> MAPPER.readValue("{\"provider\":\"twilio\"}", SmsProviderConfigDTO.class))
                    .hasMessageContaining("twilio");
        }
    }
}
