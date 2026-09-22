package org.arghyam.jalsoochak.message.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.arghyam.jalsoochak.message.service.SecretCryptoService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;

import jakarta.annotation.PostConstruct;

/**
 * PER-TENANT-PROVIDERS: unit tests for {@link PerTenantProviderStartupValidator}.
 *
 * <p>The asymmetry is the whole design: the flag off must never require the new environment
 * variable, and the flag on must never be allowed without it.
 */
@DisplayName("PerTenantProviderStartupValidator Tests")
class PerTenantProviderStartupValidatorTest {

    private static SecretCryptoService cryptoWith(Map<String, String> keys, String activeId) {
        MessagingSecretProperties props = new MessagingSecretProperties();
        props.setActiveMasterKeyId(activeId);
        props.setMasterKeys(new LinkedHashMap<>(keys));
        return new SecretCryptoService(props);
    }

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static PerTenantProviderProperties properties(boolean enabled) {
        PerTenantProviderProperties props = new PerTenantProviderProperties();
        props.setEnabled(enabled);
        return props;
    }

    @Test
    @DisplayName("the flag off needs no master key")
    void flagOffNeedsNoMasterKey() {
        // A deployment that does not use the feature must need no new environment variable at all.
        PerTenantProviderStartupValidator validator = new PerTenantProviderStartupValidator(
                properties(false), cryptoWith(Map.of(), null));

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the flag on with a master key starts")
    void flagOnWithAMasterKeyStarts() {
        PerTenantProviderStartupValidator validator = new PerTenantProviderStartupValidator(
                properties(true), cryptoWith(Map.of("v1", randomKey()), "v1"));

        assertThatCode(validator::validate).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the flag on without a master key refuses to start")
    void flagOnWithoutAMasterKeyRefusesToStart() {
        // Starting anyway would mean every configured tenant silently using the platform's account
        // while its state believed it was sending from its own — the worst outcome available, since
        // nothing about it looks like a failure.
        PerTenantProviderStartupValidator validator = new PerTenantProviderStartupValidator(
                properties(true), cryptoWith(Map.of(), null));

        assertThatThrownBy(validator::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no messaging.secret.master-keys entry is configured")
                .hasMessageContaining("MESSAGING_SECRET_MASTER_KEY_V");
    }

    @Test
    @DisplayName("the check runs before the Kafka listeners start")
    void checkRunsBeforeTheKafkaListenersStart() throws Exception {
        // The lifecycle point is the whole value of the check. KafkaListenerEndpointRegistry is a
        // SmartLifecycle started inside finishRefresh(), which precedes ApplicationReadyEvent: a
        // check deferred to that event would let NotificationEventRouter drain common-topic first,
        // and every message in that window would send from the platform's account. @PostConstruct
        // is the point SingleTenantModeStartupValidator uses, and it is before any container starts.
        Method validate = PerTenantProviderStartupValidator.class.getDeclaredMethod("validate");

        assertThat(validate.isAnnotationPresent(PostConstruct.class)).isTrue();
        assertThat(validate.isAnnotationPresent(EventListener.class)).isFalse();
    }

    @Test
    @DisplayName("the failure message names no key material")
    void failureMessageNamesNoKeyMaterial() {
        String key = randomKey();
        PerTenantProviderStartupValidator validator = new PerTenantProviderStartupValidator(
                properties(true), cryptoWith(Map.of(), null));

        assertThatThrownBy(validator::validate).hasMessageNotContaining(key);
    }
}
