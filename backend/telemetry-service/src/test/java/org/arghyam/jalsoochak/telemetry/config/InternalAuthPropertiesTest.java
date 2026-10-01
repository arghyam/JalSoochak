package org.arghyam.jalsoochak.telemetry.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("InternalAuthProperties")
class InternalAuthPropertiesTest {

    private static final String TOKEN = "js_internal_test_token";
    private static final String TOKEN_HASH = WebhookAuthProperties.sha256Hex(TOKEN);

    private static InternalAuthProperties properties(String tokenHash) {
        InternalAuthProperties properties = new InternalAuthProperties();
        properties.setTokenHash(tokenHash);
        properties.init();
        return properties;
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    @DisplayName("no hash disables the internal routes without failing startup")
    void noHashDisables(String tokenHash) {
        InternalAuthProperties properties = properties(tokenHash);

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.matches(TOKEN)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {TOKEN, "abc123", "g000000000000000000000000000000000000000000000000000000000000000"})
    @DisplayName("a value that is not SHA-256 hex disables the internal routes without failing startup")
    void malformedHashDisables(String tokenHash) {
        InternalAuthProperties properties = properties(tokenHash);

        assertThat(properties.isEnabled()).isFalse();
        // Above all, a raw token pasted in place of its hash must not be accepted as itself.
        assertThat(properties.matches(tokenHash)).isFalse();
    }

    @Test
    @DisplayName("accepts the token whose hash is configured")
    void acceptsTheConfiguredToken() {
        InternalAuthProperties properties = properties(TOKEN_HASH);

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.matches(TOKEN)).isTrue();
        assertThat(properties.matches("  " + TOKEN + " ")).isTrue();
    }

    @Test
    @DisplayName("accepts the hash in upper case and with surrounding spaces")
    void normalizesTheHash() {
        InternalAuthProperties properties = properties("  " + TOKEN_HASH.toUpperCase(Locale.ROOT) + " ");

        assertThat(properties.matches(TOKEN)).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "js_wrong_token"})
    @DisplayName("refuses any other token")
    void refusesAnyOtherToken(String token) {
        InternalAuthProperties properties = properties(TOKEN_HASH);

        assertThat(properties.matches(token)).isFalse();
    }

    @Test
    @DisplayName("refuses the hash itself sent as the token")
    void refusesTheHashAsTheToken() {
        assertThat(properties(TOKEN_HASH).matches(TOKEN_HASH)).isFalse();
    }
}
