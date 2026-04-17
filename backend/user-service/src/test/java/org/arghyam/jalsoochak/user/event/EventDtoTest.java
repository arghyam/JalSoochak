package org.arghyam.jalsoochak.user.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Event DTO Tests")
class EventDtoTest {

    @Nested
    @DisplayName("InviteEmailEvent")
    class InviteEmailEventTests {

        @Test
        @DisplayName("builder sets all fields correctly")
        void builderSetsAllFields() {
            InviteEmailEvent event = InviteEmailEvent.builder()
                    .eventType("INVITE_EMAIL")
                    .to("user@example.com")
                    .name("Alice")
                    .role("STATE_ADMIN")
                    .inviteLink("https://example.com/invite?token=abc")
                    .expiryHours(24)
                    .stateName("Madhya Pradesh")
                    .build();

            assertThat(event.getEventType()).isEqualTo("INVITE_EMAIL");
            assertThat(event.getTo()).isEqualTo("user@example.com");
            assertThat(event.getName()).isEqualTo("Alice");
            assertThat(event.getRole()).isEqualTo("STATE_ADMIN");
            assertThat(event.getInviteLink()).isEqualTo("https://example.com/invite?token=abc");
            assertThat(event.getExpiryHours()).isEqualTo(24);
            assertThat(event.getStateName()).isEqualTo("Madhya Pradesh");
        }

        @Test
        @DisplayName("stateName is null when not set (non-STATE_ADMIN role)")
        void stateNameIsNullByDefault() {
            InviteEmailEvent event = InviteEmailEvent.builder()
                    .eventType("INVITE_EMAIL")
                    .to("officer@example.com")
                    .name("Bob")
                    .role("SECTION_OFFICER")
                    .inviteLink("https://example.com/invite?token=xyz")
                    .expiryHours(48)
                    .build();

            assertThat(event.getStateName()).isNull();
        }

        @Test
        @DisplayName("setters update fields")
        void settersUpdateFields() {
            InviteEmailEvent event = InviteEmailEvent.builder()
                    .eventType("INVITE_EMAIL")
                    .to("user@example.com")
                    .name("Alice")
                    .role("STATE_ADMIN")
                    .inviteLink("https://example.com/invite")
                    .expiryHours(24)
                    .build();

            event.setEventType("UPDATED_TYPE");
            event.setTo("new@example.com");
            event.setStateName("Gujarat");

            assertThat(event.getEventType()).isEqualTo("UPDATED_TYPE");
            assertThat(event.getTo()).isEqualTo("new@example.com");
            assertThat(event.getStateName()).isEqualTo("Gujarat");
        }

        @Test
        @DisplayName("equals and hashCode work correctly")
        void equalsAndHashCode() {
            InviteEmailEvent e1 = InviteEmailEvent.builder()
                    .eventType("INVITE_EMAIL").to("a@b.com").name("X").role("R")
                    .inviteLink("link").expiryHours(1).build();
            InviteEmailEvent e2 = InviteEmailEvent.builder()
                    .eventType("INVITE_EMAIL").to("a@b.com").name("X").role("R")
                    .inviteLink("link").expiryHours(1).build();

            assertThat(e1).isEqualTo(e2);
            assertThat(e1.hashCode()).isEqualTo(e2.hashCode());
        }

        @Test
        @DisplayName("toString contains key fields")
        void toStringContainsFields() {
            InviteEmailEvent event = InviteEmailEvent.builder()
                    .eventType("INVITE_EMAIL").to("a@b.com").name("Alice").role("ADMIN")
                    .inviteLink("link").expiryHours(12).build();

            assertThat(event.toString()).contains("INVITE_EMAIL", "Alice", "ADMIN");
        }
    }

    @Nested
    @DisplayName("ResetPasswordEmailEvent")
    class ResetPasswordEmailEventTests {

        @Test
        @DisplayName("builder sets all fields correctly")
        void builderSetsAllFields() {
            ResetPasswordEmailEvent event = ResetPasswordEmailEvent.builder()
                    .eventType("RESET_PASSWORD_EMAIL")
                    .to("admin@example.com")
                    .resetLink("https://example.com/reset?token=def")
                    .expiryMinutes(30)
                    .build();

            assertThat(event.getEventType()).isEqualTo("RESET_PASSWORD_EMAIL");
            assertThat(event.getTo()).isEqualTo("admin@example.com");
            assertThat(event.getResetLink()).isEqualTo("https://example.com/reset?token=def");
            assertThat(event.getExpiryMinutes()).isEqualTo(30);
        }

        @Test
        @DisplayName("setters update fields")
        void settersUpdateFields() {
            ResetPasswordEmailEvent event = ResetPasswordEmailEvent.builder()
                    .eventType("RESET_PASSWORD_EMAIL")
                    .to("admin@example.com")
                    .resetLink("https://example.com/reset")
                    .expiryMinutes(30)
                    .build();

            event.setTo("new@example.com");
            event.setExpiryMinutes(60);

            assertThat(event.getTo()).isEqualTo("new@example.com");
            assertThat(event.getExpiryMinutes()).isEqualTo(60);
        }

        @Test
        @DisplayName("equals and hashCode work correctly")
        void equalsAndHashCode() {
            ResetPasswordEmailEvent e1 = ResetPasswordEmailEvent.builder()
                    .eventType("RESET").to("a@b.com").resetLink("link").expiryMinutes(10).build();
            ResetPasswordEmailEvent e2 = ResetPasswordEmailEvent.builder()
                    .eventType("RESET").to("a@b.com").resetLink("link").expiryMinutes(10).build();

            assertThat(e1).isEqualTo(e2);
            assertThat(e1.hashCode()).isEqualTo(e2.hashCode());
        }

        @Test
        @DisplayName("toString contains key fields")
        void toStringContainsFields() {
            ResetPasswordEmailEvent event = ResetPasswordEmailEvent.builder()
                    .eventType("RESET_PASSWORD_EMAIL").to("x@y.com")
                    .resetLink("link").expiryMinutes(15).build();

            assertThat(event.toString()).contains("RESET_PASSWORD_EMAIL", "x@y.com");
        }
    }

    @Nested
    @DisplayName("SendLoginOtpEvent")
    class SendLoginOtpEventTests {

        @Test
        @DisplayName("builder sets all fields correctly including nullable glificId")
        void builderSetsAllFields() {
            SendLoginOtpEvent event = SendLoginOtpEvent.builder()
                    .eventType("SEND_LOGIN_OTP")
                    .officerPhoneNumber("919876543210")
                    .glificId(42L)
                    .otp("123456")
                    .expiryMinutes(5)
                    .deliveryChannel("WHATSAPP")
                    .build();

            assertThat(event.getEventType()).isEqualTo("SEND_LOGIN_OTP");
            assertThat(event.getOfficerPhoneNumber()).isEqualTo("919876543210");
            assertThat(event.getGlificId()).isEqualTo(42L);
            assertThat(event.getOtp()).isEqualTo("123456");
            assertThat(event.getExpiryMinutes()).isEqualTo(5);
            assertThat(event.getDeliveryChannel()).isEqualTo("WHATSAPP");
        }

        @Test
        @DisplayName("glificId can be null for SMS delivery")
        void glificIdNullForSms() {
            SendLoginOtpEvent event = SendLoginOtpEvent.builder()
                    .eventType("SEND_LOGIN_OTP")
                    .officerPhoneNumber("919876543210")
                    .glificId(null)
                    .otp("654321")
                    .expiryMinutes(5)
                    .deliveryChannel("SMS")
                    .build();

            assertThat(event.getGlificId()).isNull();
            assertThat(event.getDeliveryChannel()).isEqualTo("SMS");
        }

        @Test
        @DisplayName("equals and hashCode work correctly")
        void equalsAndHashCode() {
            SendLoginOtpEvent e1 = SendLoginOtpEvent.builder()
                    .eventType("SEND_LOGIN_OTP").officerPhoneNumber("919999999999")
                    .otp("111111").expiryMinutes(5).deliveryChannel("SMS").build();
            SendLoginOtpEvent e2 = SendLoginOtpEvent.builder()
                    .eventType("SEND_LOGIN_OTP").officerPhoneNumber("919999999999")
                    .otp("111111").expiryMinutes(5).deliveryChannel("SMS").build();

            assertThat(e1).isEqualTo(e2);
            assertThat(e1.hashCode()).isEqualTo(e2.hashCode());
        }

        @Test
        @DisplayName("toString does not expose phone number at INFO level (field present in object)")
        void toStringContainsEventType() {
            SendLoginOtpEvent event = SendLoginOtpEvent.builder()
                    .eventType("SEND_LOGIN_OTP").officerPhoneNumber("919876543210")
                    .otp("000000").expiryMinutes(5).deliveryChannel("WHATSAPP").build();

            assertThat(event.toString()).contains("SEND_LOGIN_OTP");
            assertThat(event.toString()).doesNotContain("919876543210");
        }
    }

    @Nested
    @DisplayName("UserLanguageUpdatedEvent")
    class UserLanguageUpdatedEventTests {

        @Test
        @DisplayName("builder sets all fields correctly")
        void builderSetsAllFields() {
            UserLanguageUpdatedEvent event = UserLanguageUpdatedEvent.builder()
                    .eventType("USER_LANGUAGE_UPDATED")
                    .tenantId(1)
                    .phoneNumber("919876543210")
                    .languageId(2)
                    .source("CSV_ONBOARDED")
                    .build();

            assertThat(event.getEventType()).isEqualTo("USER_LANGUAGE_UPDATED");
            assertThat(event.getTenantId()).isEqualTo(1);
            assertThat(event.getPhoneNumber()).isEqualTo("919876543210");
            assertThat(event.getLanguageId()).isEqualTo(2);
            assertThat(event.getSource()).isEqualTo("CSV_ONBOARDED");
        }

        @Test
        @DisplayName("no-args constructor creates empty object")
        void noArgsConstructor() {
            UserLanguageUpdatedEvent event = new UserLanguageUpdatedEvent();

            assertThat(event.getEventType()).isNull();
            assertThat(event.getTenantId()).isNull();
            assertThat(event.getPhoneNumber()).isNull();
            assertThat(event.getLanguageId()).isNull();
            assertThat(event.getSource()).isNull();
        }

        @Test
        @DisplayName("all-args constructor sets all fields")
        void allArgsConstructor() {
            UserLanguageUpdatedEvent event =
                    new UserLanguageUpdatedEvent("USER_LANGUAGE_UPDATED", 3, "919988776655", 1, "MANUAL");

            assertThat(event.getEventType()).isEqualTo("USER_LANGUAGE_UPDATED");
            assertThat(event.getTenantId()).isEqualTo(3);
            assertThat(event.getPhoneNumber()).isEqualTo("919988776655");
            assertThat(event.getLanguageId()).isEqualTo(1);
            assertThat(event.getSource()).isEqualTo("MANUAL");
        }

        @Test
        @DisplayName("setters update fields")
        void settersUpdateFields() {
            UserLanguageUpdatedEvent event = new UserLanguageUpdatedEvent();
            event.setEventType("USER_LANGUAGE_UPDATED");
            event.setTenantId(5);
            event.setPhoneNumber("919112233445");
            event.setLanguageId(3);
            event.setSource("APP");

            assertThat(event.getEventType()).isEqualTo("USER_LANGUAGE_UPDATED");
            assertThat(event.getTenantId()).isEqualTo(5);
            assertThat(event.getPhoneNumber()).isEqualTo("919112233445");
            assertThat(event.getLanguageId()).isEqualTo(3);
            assertThat(event.getSource()).isEqualTo("APP");
        }

        @Test
        @DisplayName("equals and hashCode work correctly")
        void equalsAndHashCode() {
            UserLanguageUpdatedEvent e1 = new UserLanguageUpdatedEvent("TYPE", 1, "91XXXXXXXXXX", 2, "S");
            UserLanguageUpdatedEvent e2 = new UserLanguageUpdatedEvent("TYPE", 1, "91XXXXXXXXXX", 2, "S");

            assertThat(e1).isEqualTo(e2);
            assertThat(e1.hashCode()).isEqualTo(e2.hashCode());
        }

        @Test
        @DisplayName("toString contains key fields")
        void toStringContainsFields() {
            UserLanguageUpdatedEvent event = new UserLanguageUpdatedEvent(
                    "USER_LANGUAGE_UPDATED", 1, "91XXXXXXXXXX", 2, "CSV_ONBOARDED");

            assertThat(event.toString()).contains("USER_LANGUAGE_UPDATED", "CSV_ONBOARDED");
        }
    }

    @Nested
    @DisplayName("PumpOperatorMessagingEvent")
    class PumpOperatorMessagingEventTests {

        @Test
        @DisplayName("builder sets all fields correctly")
        void builderSetsAllFields() {
            PumpOperatorMessagingEvent event = PumpOperatorMessagingEvent.builder()
                    .eventType("SEND_WELCOME_MESSAGE")
                    .tenantCode("MP")
                    .tenantId(1)
                    .triggeredAt("2026-01-01T10:00:00.000Z")
                    .glificLanguageId("2")
                    .pumpOperatorPhones(List.of("919876543210", "919123456789"))
                    .build();

            assertThat(event.getEventType()).isEqualTo("SEND_WELCOME_MESSAGE");
            assertThat(event.getTenantCode()).isEqualTo("MP");
            assertThat(event.getTenantId()).isEqualTo(1);
            assertThat(event.getTriggeredAt()).isEqualTo("2026-01-01T10:00:00.000Z");
            assertThat(event.getGlificLanguageId()).isEqualTo("2");
            assertThat(event.getPumpOperatorPhones())
                    .containsExactly("919876543210", "919123456789");
        }

        @Test
        @DisplayName("no-args constructor creates empty object")
        void noArgsConstructor() {
            PumpOperatorMessagingEvent event = new PumpOperatorMessagingEvent();

            assertThat(event.getEventType()).isNull();
            assertThat(event.getPumpOperatorPhones()).isNull();
        }

        @Test
        @DisplayName("all-args constructor sets all fields")
        void allArgsConstructor() {
            List<String> phones = List.of("919000000001");
            PumpOperatorMessagingEvent event = new PumpOperatorMessagingEvent(
                    "UPDATE_USER_LANGUAGE", "TR", 2, "2026-03-01T08:00:00.000Z", "3", phones);

            assertThat(event.getEventType()).isEqualTo("UPDATE_USER_LANGUAGE");
            assertThat(event.getTenantCode()).isEqualTo("TR");
            assertThat(event.getPumpOperatorPhones()).isEqualTo(phones);
        }

        @Test
        @DisplayName("setters update fields")
        void settersUpdateFields() {
            PumpOperatorMessagingEvent event = new PumpOperatorMessagingEvent();
            event.setEventType("SEND_WELCOME_MESSAGE_ADMIN");
            event.setTenantCode("MP");
            event.setTenantId(1);
            event.setPumpOperatorPhones(List.of("919876543210"));

            assertThat(event.getEventType()).isEqualTo("SEND_WELCOME_MESSAGE_ADMIN");
            assertThat(event.getPumpOperatorPhones()).hasSize(1);
        }

        @Test
        @DisplayName("equals and hashCode work correctly")
        void equalsAndHashCode() {
            List<String> phones = List.of("919000000001");
            PumpOperatorMessagingEvent e1 = new PumpOperatorMessagingEvent(
                    "TYPE", "MP", 1, "ts", "1", phones);
            PumpOperatorMessagingEvent e2 = new PumpOperatorMessagingEvent(
                    "TYPE", "MP", 1, "ts", "1", phones);

            assertThat(e1).isEqualTo(e2);
            assertThat(e1.hashCode()).isEqualTo(e2.hashCode());
        }

        @Test
        @DisplayName("toString contains key fields")
        void toStringContainsFields() {
            PumpOperatorMessagingEvent event = PumpOperatorMessagingEvent.builder()
                    .eventType("SEND_WELCOME_MESSAGE").tenantCode("MP").tenantId(1)
                    .triggeredAt("2026-01-01T10:00:00.000Z").build();

            assertThat(event.toString()).contains("SEND_WELCOME_MESSAGE", "MP");
        }
    }
}
