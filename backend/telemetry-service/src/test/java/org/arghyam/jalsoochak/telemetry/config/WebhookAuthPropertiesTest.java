package org.arghyam.jalsoochak.telemetry.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("WebhookAuthProperties")
class WebhookAuthPropertiesTest {

    private static final String TOKEN = "js_test_token_value";
    private static final String TOKEN_HASH = WebhookAuthProperties.sha256Hex(TOKEN);

    private static WebhookAuthProperties properties(String mode, String hashes) {
        WebhookAuthProperties props = new WebhookAuthProperties();
        props.setMode(mode);
        props.setTokenHashes(hashes);
        return props;
    }

    @Nested
    @DisplayName("mode parsing")
    class ModeParsing {

        @Test
        @DisplayName("defaults to ENFORCE")
        void defaultsToEnforce() {
            WebhookAuthProperties props = new WebhookAuthProperties();
            props.setTokenHashes(TOKEN_HASH);
            props.init();

            assertThat(props.getResolvedMode()).isEqualTo(WebhookAuthProperties.Mode.ENFORCE);
        }

        @ParameterizedTest
        @ValueSource(strings = {"audit", "AUDIT", "  Audit  "})
        @DisplayName("is case-insensitive and trimmed")
        void caseInsensitive(String raw) {
            WebhookAuthProperties props = properties(raw, "");
            props.init();

            assertThat(props.getResolvedMode()).isEqualTo(WebhookAuthProperties.Mode.AUDIT);
        }

        @Test
        @DisplayName("rejects an unknown mode at startup rather than guessing")
        void rejectsUnknownMode() {
            WebhookAuthProperties props = properties("ENFORCED", TOKEN_HASH);

            assertThatThrownBy(props::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ENFORCED")
                    .hasMessageContaining("ENFORCE, AUDIT, OFF");
        }

        @Test
        @DisplayName("rejects a blank mode")
        void rejectsBlankMode() {
            assertThatThrownBy(() -> properties("  ", TOKEN_HASH).init())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("must not be blank");
        }
    }

    @Nested
    @DisplayName("token-hash parsing")
    class TokenHashParsing {

        @Test
        @DisplayName("trims, drops blanks and deduplicates")
        void trimsAndDeduplicates() {
            String other = WebhookAuthProperties.sha256Hex("second-token");
            WebhookAuthProperties props = properties("ENFORCE",
                    "  " + TOKEN_HASH + " , ," + other + "," + TOKEN_HASH + ",");
            props.init();

            assertThat(props.getResolvedTokenHashes()).containsExactlyInAnyOrder(TOKEN_HASH, other);
        }

        @Test
        @DisplayName("accepts uppercase hex by normalising it")
        void acceptsUppercaseHex() {
            WebhookAuthProperties props = properties("ENFORCE", TOKEN_HASH.toUpperCase());
            props.init();

            assertThat(props.getResolvedTokenHashes()).containsExactly(TOKEN_HASH);
            assertThat(props.matches(TOKEN)).isTrue();
        }

        @Test
        @DisplayName("fails startup when a raw token was configured instead of its hash")
        void rejectsRawToken() {
            WebhookAuthProperties props = properties("ENFORCE", TOKEN);

            assertThatThrownBy(props::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SHA-256 hex");
        }

        @Test
        @DisplayName("does not echo the offending value, which may be a live secret")
        void doesNotEchoOffendingValue() {
            WebhookAuthProperties props = properties("ENFORCE", TOKEN);

            assertThatThrownBy(props::init)
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN));
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "abc",                                                                 // too short
                "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz",    // not hex
        })
        @DisplayName("fails startup on a malformed hash")
        void rejectsMalformedHash(String bad) {
            assertThatThrownBy(() -> properties("ENFORCE", bad).init())
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("startup invariants")
    class StartupInvariants {

        @Test
        @DisplayName("ENFORCE with no configured hash refuses to start")
        void enforceWithoutHashesFailsStartup() {
            assertThatThrownBy(() -> properties("ENFORCE", "").init())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("TELEMETRY_WEBHOOK_AUTH_TOKEN_HASHES");
        }

        @Test
        @DisplayName("AUDIT with no configured hash starts, so the kill switch always works")
        void auditWithoutHashesStarts() {
            assertThatCode(() -> properties("AUDIT", "").init()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("OFF with no configured hash starts, for local development")
        void offWithoutHashesStarts() {
            assertThatCode(() -> properties("OFF", "").init()).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("rejects a blank header name")
        void rejectsBlankHeaderName() {
            WebhookAuthProperties props = properties("ENFORCE", TOKEN_HASH);
            props.setHeaderName("  ");

            assertThatThrownBy(props::init)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("header-name");
        }
    }

    @Nested
    @DisplayName("matches")
    class Matches {

        @Test
        @DisplayName("accepts the configured token")
        void acceptsConfiguredToken() {
            WebhookAuthProperties props = properties("ENFORCE", TOKEN_HASH);
            props.init();

            assertThat(props.matches(TOKEN)).isTrue();
        }

        @Test
        @DisplayName("accepts either token during a rotation window")
        void acceptsEitherTokenDuringRotation() {
            String next = "js_rotated_token";
            WebhookAuthProperties props =
                    properties("ENFORCE", TOKEN_HASH + "," + WebhookAuthProperties.sha256Hex(next));
            props.init();

            assertThat(props.matches(TOKEN)).isTrue();
            assertThat(props.matches(next)).isTrue();
        }

        @Test
        @DisplayName("rejects a token differing only in its final character")
        void rejectsNearMiss() {
            WebhookAuthProperties props = properties("ENFORCE", TOKEN_HASH);
            props.init();

            // The constant-time property of the comparison is a code-review matter; a timing
            // assertion would be flaky in CI. This pins the behaviour only.
            assertThat(props.matches(TOKEN.substring(0, TOKEN.length() - 1) + "X")).isFalse();
        }

        @Test
        @DisplayName("tolerates surrounding whitespace from the header")
        void trimsInboundToken() {
            WebhookAuthProperties props = properties("ENFORCE", TOKEN_HASH);
            props.init();

            assertThat(props.matches("  " + TOKEN + "  ")).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "wrong-token"})
        @DisplayName("rejects blank and unknown tokens")
        void rejectsBlankAndUnknown(String candidate) {
            WebhookAuthProperties props = properties("ENFORCE", TOKEN_HASH);
            props.init();

            assertThat(props.matches(candidate)).isFalse();
        }

        @Test
        @DisplayName("rejects null")
        void rejectsNull() {
            WebhookAuthProperties props = properties("ENFORCE", TOKEN_HASH);
            props.init();

            assertThat(props.matches(null)).isFalse();
        }

        @Test
        @DisplayName("rejects everything when no hash is configured")
        void rejectsWhenNoHashConfigured() {
            WebhookAuthProperties props = properties("AUDIT", "");
            props.init();

            assertThat(props.matches(TOKEN)).isFalse();
        }
    }
}
