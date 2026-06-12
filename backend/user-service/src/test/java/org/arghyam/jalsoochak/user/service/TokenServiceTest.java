package org.arghyam.jalsoochak.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TokenService")
class TokenServiceTest {

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService();
    }

    @Nested
    @DisplayName("generateRawToken")
    class GenerateRawToken {

        @Test
        @DisplayName("returns a non-null non-blank string")
        void returnsNonBlankString() {
            String token = tokenService.generateRawToken();
            assertThat(token).isNotBlank();
        }

        @Test
        @DisplayName("returns a 43-char Base64URL string (32 bytes without padding)")
        void returnsCorrectLength() {
            String token = tokenService.generateRawToken();
            assertThat(token).hasSize(43);
        }

        @Test
        @DisplayName("contains only Base64URL characters")
        void containsOnlyBase64UrlChars() {
            String token = tokenService.generateRawToken();
            assertThat(token).matches("[A-Za-z0-9_-]+");
        }

        @RepeatedTest(5)
        @DisplayName("produces unique tokens on each call")
        void producesUniqueTokens() {
            String t1 = tokenService.generateRawToken();
            String t2 = tokenService.generateRawToken();
            assertThat(t1).isNotEqualTo(t2);
        }
    }

    @Nested
    @DisplayName("hash")
    class Hash {

        @Test
        @DisplayName("returns 64-char lowercase hex string for SHA-256")
        void returnsCorrectLength() {
            String hash = tokenService.hash("someToken");
            assertThat(hash).hasSize(64).matches("[0-9a-f]+");
        }

        @Test
        @DisplayName("same input always produces same hash (deterministic)")
        void isDeterministic() {
            String input = "testToken123";
            assertThat(tokenService.hash(input)).isEqualTo(tokenService.hash(input));
        }

        @Test
        @DisplayName("different inputs produce different hashes")
        void differentInputsDifferentHashes() {
            assertThat(tokenService.hash("token1")).isNotEqualTo(tokenService.hash("token2"));
        }

        @Test
        @DisplayName("throws IllegalArgumentException for null input")
        void throwsOnNull() {
            assertThatThrownBy(() -> tokenService.hash(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("hash of raw token from generateRawToken is stable")
        void hashOfGeneratedToken() {
            String raw = tokenService.generateRawToken();
            String hash1 = tokenService.hash(raw);
            String hash2 = tokenService.hash(raw);
            assertThat(hash1).isEqualTo(hash2).hasSize(64);
        }
    }
}
