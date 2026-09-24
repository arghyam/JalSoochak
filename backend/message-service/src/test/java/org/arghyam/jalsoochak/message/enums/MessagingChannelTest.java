package org.arghyam.jalsoochak.message.enums;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * PER-TENANT-PROVIDERS: guards the strings this service shares with tenant-service.
 *
 * <p>These are the two halves of a contract nothing else checks. {@code settingsConfigKey} is the
 * name tenant-service writes into {@code tenant_config_master_table} and into the
 * {@code TENANT_CONFIG_UPDATED} event, and it is what this service queries by and evicts on. A
 * rename on either side would not break a build; it would quietly produce a tenant whose settings
 * save, whose cache never evicts, and whose provider is never found. The literals are therefore
 * asserted rather than derived, so a change has to be made deliberately in both services.
 */
@DisplayName("MessagingChannel Tests")
class MessagingChannelTest {

    @Test
    @DisplayName("settings config keys match tenant-service's TenantConfigKeyEnum constants")
    void settingsConfigKeysMatchTenantService() {
        assertThat(MessagingChannel.EMAIL.getSettingsConfigKey()).isEqualTo("EMAIL_PROVIDER_SETTINGS");
        assertThat(MessagingChannel.SMS.getSettingsConfigKey()).isEqualTo("SMS_PROVIDER_SETTINGS");
    }

    @Test
    @DisplayName("secret names match tenant-service's MessagingChannel")
    void secretNamesMatchTenantService() {
        assertThat(MessagingChannel.EMAIL.getSecretNames()).containsExactlyInAnyOrder("apiKey", "password");
        assertThat(MessagingChannel.SMS.getSecretNames()).containsExactlyInAnyOrder("authKey", "authToken");
    }

    @Test
    @DisplayName("WHATSAPP is absent: every tenant shares one WhatsApp provider organisation")
    void whatsAppIsNotAChannel() {
        assertThat(MessagingChannel.values()).hasSize(2);
    }

    @ParameterizedTest
    @EnumSource(MessagingChannel.class)
    @DisplayName("every channel's settings key round-trips through forSettingsConfigKey")
    void settingsConfigKeyRoundTrips(MessagingChannel channel) {
        assertThat(MessagingChannel.forSettingsConfigKey(channel.getSettingsConfigKey()))
                .isEqualTo(channel);
    }

    @Test
    @DisplayName("an unrelated or absent config key maps to no channel")
    void unrelatedConfigKeyMapsToNothing() {
        assertThat(MessagingChannel.forSettingsConfigKey("REGULARITY_THRESHOLD_PERCENT")).isNull();
        assertThat(MessagingChannel.forSettingsConfigKey("")).isNull();
        assertThat(MessagingChannel.forSettingsConfigKey(null)).isNull();
    }

    @Test
    @DisplayName("a secret name outside the channel's set is not supported")
    void supportsSecretIsClosed() {
        assertThat(MessagingChannel.EMAIL.supportsSecret("apiKey")).isTrue();
        // A real name, but on the wrong channel — the check is per channel, not global.
        assertThat(MessagingChannel.EMAIL.supportsSecret("authKey")).isFalse();
        assertThat(MessagingChannel.EMAIL.supportsSecret("SPRING_DATASOURCE_PASSWORD")).isFalse();
        assertThat(MessagingChannel.EMAIL.supportsSecret(null)).isFalse();
    }

    @Test
    @DisplayName("provider wire names match tenant-service's")
    void providerWireNamesMatchTenantService() {
        assertThat(EmailProviderType.SENDGRID.getWireName()).isEqualTo("sendgrid");
        assertThat(EmailProviderType.SMTP.getWireName()).isEqualTo("smtp");
        assertThat(SmsProviderType.SMSCOUNTRY.getWireName()).isEqualTo("smscountry");
    }

    @Test
    @DisplayName("provider required secret names are a subset of their channel's")
    void requiredSecretNamesAreChannelSecrets() {
        for (EmailProviderType provider : EmailProviderType.values()) {
            assertThat(MessagingChannel.EMAIL.getSecretNames())
                    .containsAll(provider.getRequiredSecretNames());
        }
        for (SmsProviderType provider : SmsProviderType.values()) {
            assertThat(MessagingChannel.SMS.getSecretNames())
                    .containsAll(provider.getRequiredSecretNames());
        }
    }

    @Test
    @DisplayName("an unknown provider is null on the read path, not an exception")
    void unknownProviderIsNullOnTheReadPath() {
        // A settings row written by a newer tenant-service must cost that tenant its provider, not
        // the parse of the whole row.
        assertThat(EmailProviderType.fromWireNameOrNull("mailgun")).isNull();
        assertThat(SmsProviderType.fromWireNameOrNull("twilio")).isNull();
        assertThat(EmailProviderType.fromWireNameOrNull("SMTP")).isEqualTo(EmailProviderType.SMTP);
    }
}
