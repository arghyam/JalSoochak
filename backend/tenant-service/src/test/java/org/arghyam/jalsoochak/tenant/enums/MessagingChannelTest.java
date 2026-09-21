package org.arghyam.jalsoochak.tenant.enums;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MESSAGING-PROVIDER-SECRETS: guards the secret-name allowlist that stands between a
 * caller-supplied name and a storage location.
 */
@DisplayName("MessagingChannel Tests")
class MessagingChannelTest {

    @Test
    @DisplayName("EMAIL accepts the SendGrid and SMTP credential names only")
    void emailSecretNames() {
        assertThat(MessagingChannel.EMAIL.getSecretNames()).containsExactlyInAnyOrder("apiKey", "password");
    }

    @Test
    @DisplayName("SMS accepts the SMSCountry credential names only")
    void smsSecretNames() {
        assertThat(MessagingChannel.SMS.getSecretNames()).containsExactlyInAnyOrder("authKey", "authToken");
    }

    @Test
    @DisplayName("A name belonging to another channel is not supported")
    void namesDoNotCrossChannels() {
        assertThat(MessagingChannel.EMAIL.supportsSecret("authKey")).isFalse();
        assertThat(MessagingChannel.SMS.supportsSecret("apiKey")).isFalse();
    }

    @Test
    @DisplayName("Unknown, null and differently-cased names are not supported")
    void unknownNamesRejected() {
        assertThat(MessagingChannel.EMAIL.supportsSecret("apikey")).isFalse();
        assertThat(MessagingChannel.EMAIL.supportsSecret("SPRING_DATASOURCE_PASSWORD")).isFalse();
        assertThat(MessagingChannel.EMAIL.supportsSecret("")).isFalse();
        assertThat(MessagingChannel.EMAIL.supportsSecret(null)).isFalse();
    }

    @Test
    @DisplayName("Each channel names the config key holding its provider settings")
    void settingsConfigKeys() {
        // These strings become TenantConfigKeyEnum constants in a later change. Until then a
        // secret write publishes them by name, so message-service evicts the right cache entry.
        assertThat(MessagingChannel.EMAIL.getSettingsConfigKey()).isEqualTo("EMAIL_PROVIDER_SETTINGS");
        assertThat(MessagingChannel.SMS.getSettingsConfigKey()).isEqualTo("SMS_PROVIDER_SETTINGS");
    }

    @Test
    @DisplayName("Only EMAIL and SMS exist — WHATSAPP is reserved in the column, not addressable")
    void whatsappIsNotAChannel() {
        assertThat(MessagingChannel.values())
                .containsExactly(MessagingChannel.EMAIL, MessagingChannel.SMS);
        assertThatThrownBy(() -> MessagingChannel.valueOf("WHATSAPP"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("The secret-name set is immutable")
    void secretNamesImmutable() {
        assertThatThrownBy(() -> MessagingChannel.SMS.getSecretNames().add("anything"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
