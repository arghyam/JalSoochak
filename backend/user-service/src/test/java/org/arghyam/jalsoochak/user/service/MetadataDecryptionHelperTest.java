package org.arghyam.jalsoochak.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MetadataDecryptionHelper")
class MetadataDecryptionHelperTest {

    @Mock
    private PiiEncryptionService pii;

    private MetadataDecryptionHelper helper;

    @BeforeEach
    void setUp() {
        helper = new MetadataDecryptionHelper(new ObjectMapper(), pii);
    }

    @Nested
    @DisplayName("parseAndDecrypt")
    class ParseAndDecrypt {

        @Test
        @DisplayName("returns null when json is null")
        void returnsNullForNullJson() {
            assertThat(helper.parseAndDecrypt(null, "key")).isNull();
        }

        @Test
        @DisplayName("returns null when key is absent from JSON")
        void returnsNullForAbsentKey() {
            // parse returns null for absent key; pii.safeDecrypt is never called
            String result = helper.parseAndDecrypt("{\"other\":\"value\"}", "missing");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns decrypted value when key exists")
        void returnsDecryptedValue() {
            String json = "{\"firstName\":\"enc-abc\"}";
            when(pii.safeDecrypt("enc-abc")).thenReturn("Alice");

            String result = helper.parseAndDecrypt(json, "firstName");
            assertThat(result).isEqualTo("Alice");
        }

        @Test
        @DisplayName("returns null when json is malformed")
        void returnsNullForMalformedJson() {
            // parse catches JsonProcessingException and returns null; pii.safeDecrypt is never called
            String result = helper.parseAndDecrypt("not-json!!!", "key");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("delegates raw value to pii.safeDecrypt for legacy plaintext tokens")
        void delegatesRawValueToPiiSafeDecrypt() {
            String json = "{\"lastName\":\"plaintext-name\"}";
            when(pii.safeDecrypt("plaintext-name")).thenReturn("plaintext-name");

            String result = helper.parseAndDecrypt(json, "lastName");
            assertThat(result).isEqualTo("plaintext-name");
        }
    }
}
