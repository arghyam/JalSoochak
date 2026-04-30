package org.arghyam.jalsoochak.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

@DisplayName("ApiKeyService")
class ApiKeyServiceTest {

    private ApiKeyService apiKeyService;

    @BeforeEach
    void setUp() {
        apiKeyService = new ApiKeyService();
    }

    @Nested
    @DisplayName("generate()")
    class GenerateTests {

        @Test
        void rawToken_hasJsPrefix() {
            assertThat(apiKeyService.generate().rawToken()).startsWith("js_");
        }

        @Test
        void rawToken_hasExpectedLength() {
            // "js_" (3) + Base64URL of 32 bytes without padding = 43 chars → total 46
            assertThat(apiKeyService.generate().rawToken()).hasSize(46);
        }

        @Test
        void hash_is64HexChars() {
            String hash = apiKeyService.generate().hash();
            assertThat(hash).matches("[0-9a-f]{64}");
        }

        @Test
        void hash_matchesSha256OfRawToken() {
            ApiKeyService.GeneratedApiToken token = apiKeyService.generate();
            assertThat(token.hash()).isEqualTo(apiKeyService.hash(token.rawToken()));
        }

        @RepeatedTest(5)
        void consecutiveTokens_areUnique() {
            String t1 = apiKeyService.generate().rawToken();
            String t2 = apiKeyService.generate().rawToken();
            assertThat(t1).isNotEqualTo(t2);
        }

        @RepeatedTest(5)
        void consecutiveHashes_areUnique() {
            String h1 = apiKeyService.generate().hash();
            String h2 = apiKeyService.generate().hash();
            assertThat(h1).isNotEqualTo(h2);
        }
    }

    @Nested
    @DisplayName("hash()")
    class HashTests {

        @Test
        void sameInput_producesSameHash() {
            String raw = "js_sometoken";
            assertThat(apiKeyService.hash(raw)).isEqualTo(apiKeyService.hash(raw));
        }

        @Test
        void differentInputs_produceDifferentHashes() {
            assertThat(apiKeyService.hash("js_aaa")).isNotEqualTo(apiKeyService.hash("js_bbb"));
        }
    }
}
