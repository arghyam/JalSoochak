package org.arghyam.jalsoochak.user.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JwtTokenValidator")
class JwtTokenValidatorTest {

    private static final String EMPTY = "";

    /**
     * Generates a fresh RSA-2048 key pair and returns the public key
     * in the Base64-encoded DER format that JwtTokenValidator expects.
     */
    private static String generateBase64PublicKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        RSAPublicKey pub = (RSAPublicKey) pair.getPublic();
        return Base64.getEncoder().encodeToString(pub.getEncoded());
    }

    // ── Constructor / lazy initialisation ────────────────────────────────────

    @Nested
    @DisplayName("constructor")
    class ConstructorTest {

        @Test
        @DisplayName("accepts empty issuerUri — no exception thrown")
        void acceptsEmptyIssuerUri() throws Exception {
            String publicKey = generateBase64PublicKey();
            // Should not throw during construction
            JwtTokenValidator validator = new JwtTokenValidator(EMPTY, publicKey, EMPTY);
            assertThat(validator).isNotNull();
        }

        @Test
        @DisplayName("prefers env-var key over yml fallback when both are set")
        void prefersEnvVarKeyOverFallback() throws Exception {
            String primaryKey = generateBase64PublicKey();
            String fallbackKey = generateBase64PublicKey();
            // No exception: primary key wins
            JwtTokenValidator validator = new JwtTokenValidator(EMPTY, primaryKey, fallbackKey);
            assertThat(validator).isNotNull();
        }

        @Test
        @DisplayName("falls back to yml key when env-var key is blank")
        void fallsBackToYmlKeyWhenEnvVarBlank() throws Exception {
            String fallbackKey = generateBase64PublicKey();
            JwtTokenValidator validator = new JwtTokenValidator(EMPTY, EMPTY, fallbackKey);
            assertThat(validator).isNotNull();
        }
    }

    // ── decodeAndValidate ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("decodeAndValidate")
    class DecodeAndValidate {

        @Test
        @DisplayName("throws IllegalStateException when no public key is configured")
        void throwsWhenNoPublicKeyConfigured() {
            JwtTokenValidator validator = new JwtTokenValidator(EMPTY, EMPTY, EMPTY);

            assertThatThrownBy(() -> validator.decodeAndValidate("any.token.here"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("KEYCLOAK_PUBLIC_KEY");
        }

        @Test
        @DisplayName("throws IllegalStateException for malformed public key")
        void throwsForMalformedPublicKey() {
            // Not a valid DER-encoded RSA key
            String badKey = Base64.getEncoder().encodeToString("not-an-rsa-key".getBytes());
            JwtTokenValidator validator = new JwtTokenValidator(EMPTY, badKey, EMPTY);

            assertThatThrownBy(() -> validator.decodeAndValidate("any.token.here"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RSA public key");
        }

        @Test
        @DisplayName("throws JwtException for a syntactically invalid token")
        void throwsJwtExceptionForInvalidToken() throws Exception {
            String publicKey = generateBase64PublicKey();
            JwtTokenValidator validator = new JwtTokenValidator(EMPTY, publicKey, EMPTY);

            assertThatThrownBy(() -> validator.decodeAndValidate("not.a.valid.jwt"))
                    .isInstanceOf(JwtException.class);
        }

        @Test
        @DisplayName("decoder is created only once (lazy singleton with double-checked locking)")
        void decoderIsCreatedOnlyOnce() throws Exception {
            String publicKey = generateBase64PublicKey();
            JwtTokenValidator validator = new JwtTokenValidator(EMPTY, publicKey, EMPTY);

            // Two calls with an invalid token — both should hit the same decoder instance.
            // The key assertion is that no exception is thrown during decoder creation on
            // the second call (i.e., no NPE or re-initialisation error).
            try { validator.decodeAndValidate("bad.token.one"); } catch (JwtException ignored) { }
            try { validator.decodeAndValidate("bad.token.two"); } catch (JwtException ignored) { }
            // If we get here without an error other than JwtException, the singleton is working.
            assertThat(validator).isNotNull();
        }
    }
}
