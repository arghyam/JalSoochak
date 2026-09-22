package org.arghyam.jalsoochak.message.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deserialisation contract for the account-email events published by user-service.
 *
 * <p>The tenant fields are optional and additive: an event published before they existed,
 * or one for a super user (who belongs to no tenant), deserialises with them null and is
 * served by the system default provider.
 */
@DisplayName("Account email event DTOs")
class EventDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Nested
    @DisplayName("InviteEmailEvent")
    class InviteEmailEventTests {

        @Test
        @DisplayName("reads tenantCode from the event payload")
        void readsTenantCode() throws Exception {
            String json = """
                    {"eventType":"SEND_INVITE_EMAIL","to":"sa@mp.gov.in","name":"Alice",
                     "role":"STATE_ADMIN","inviteLink":"https://example.com/invite","expiryHours":24,
                     "stateName":"Madhya Pradesh","tenantCode":"MP"}
                    """;

            InviteEmailEvent event = objectMapper.readValue(json, InviteEmailEvent.class);

            assertThat(event.getTenantCode()).isEqualTo("MP");
            assertThat(event.getStateName()).isEqualTo("Madhya Pradesh");
        }

        @Test
        @DisplayName("tenantCode is null when the payload omits it")
        void tenantCodeNullWhenAbsent() throws Exception {
            String json = """
                    {"eventType":"SEND_INVITE_EMAIL","to":"super@example.com","name":"Carol",
                     "role":"SUPER_USER","inviteLink":"https://example.com/invite","expiryHours":24}
                    """;

            InviteEmailEvent event = objectMapper.readValue(json, InviteEmailEvent.class);

            assertThat(event.getTenantCode()).isNull();
            assertThat(event.getTo()).isEqualTo("super@example.com");
        }
    }

    @Nested
    @DisplayName("ResetPasswordEmailEvent")
    class ResetPasswordEmailEventTests {

        @Test
        @DisplayName("reads tenantId and tenantCode from the event payload")
        void readsTenantFields() throws Exception {
            String json = """
                    {"eventType":"SEND_PASSWORD_RESET_EMAIL","to":"sa@mp.gov.in",
                     "resetLink":"https://example.com/reset","expiryMinutes":30,
                     "tenantId":1,"tenantCode":"MP"}
                    """;

            ResetPasswordEmailEvent event = objectMapper.readValue(json, ResetPasswordEmailEvent.class);

            assertThat(event.getTenantId()).isEqualTo(1);
            assertThat(event.getTenantCode()).isEqualTo("MP");
        }

        @Test
        @DisplayName("tenant fields are null when the payload omits them")
        void tenantFieldsNullWhenAbsent() throws Exception {
            String json = """
                    {"eventType":"SEND_PASSWORD_RESET_EMAIL","to":"super@example.com",
                     "resetLink":"https://example.com/reset","expiryMinutes":30}
                    """;

            ResetPasswordEmailEvent event = objectMapper.readValue(json, ResetPasswordEmailEvent.class);

            assertThat(event.getTenantId()).isNull();
            assertThat(event.getTenantCode()).isNull();
            assertThat(event.getExpiryMinutes()).isEqualTo(30);
        }

        @Test
        @DisplayName("toString does not expose the recipient or the reset link")
        void toStringHidesSensitiveFields() {
            ResetPasswordEmailEvent event = new ResetPasswordEmailEvent();
            event.setTo("sa@mp.gov.in");
            event.setResetLink("https://example.com/reset?token=secret");
            event.setTenantCode("MP");

            assertThat(event.toString())
                    .doesNotContain("sa@mp.gov.in")
                    .doesNotContain("secret")
                    .contains("MP");
        }
    }
}
