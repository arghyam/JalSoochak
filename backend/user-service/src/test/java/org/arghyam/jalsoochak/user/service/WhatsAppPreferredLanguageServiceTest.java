package org.arghyam.jalsoochak.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.user.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WhatsAppPreferredLanguageService")
class WhatsAppPreferredLanguageServiceTest {

    @Mock
    private TenantConfigRepository tenantConfigRepository;

    private WhatsAppPreferredLanguageService service;

    @BeforeEach
    void setUp() {
        service = new WhatsAppPreferredLanguageService(tenantConfigRepository, new ObjectMapper());
    }

    @Nested
    @DisplayName("fallback cases")
    class Fallback {

        @Test
        @DisplayName("returns 1 for null tenantId")
        void nullTenantId() {
            assertThat(service.resolvePreferredLanguageId(null)).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 1 for zero tenantId")
        void zeroTenantId() {
            assertThat(service.resolvePreferredLanguageId(0)).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 1 for negative tenantId")
        void negativeTenantId() {
            assertThat(service.resolvePreferredLanguageId(-1)).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 1 when config is absent under both key names")
        void configAbsent() {
            when(tenantConfigRepository.findConfigValue(1, WhatsAppPreferredLanguageService.CONFIG_KEY))
                    .thenReturn(Optional.empty());
            when(tenantConfigRepository.findConfigValue(1, WhatsAppPreferredLanguageService.LEGACY_CONFIG_KEY))
                    .thenReturn(Optional.empty());
            assertThat(service.resolvePreferredLanguageId(1)).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 1 when config value is blank")
        void configBlank() {
            when(tenantConfigRepository.findConfigValue(1, WhatsAppPreferredLanguageService.CONFIG_KEY))
                    .thenReturn(Optional.of("  "));
            assertThat(service.resolvePreferredLanguageId(1)).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 1 for invalid JSON")
        void invalidJson() {
            when(tenantConfigRepository.findConfigValue(1, WhatsAppPreferredLanguageService.CONFIG_KEY))
                    .thenReturn(Optional.of("not-json"));
            assertThat(service.resolvePreferredLanguageId(1)).isEqualTo(1);
        }

        @Test
        @DisplayName("returns 1 when JSON has no language fields")
        void noLanguageFields() {
            when(tenantConfigRepository.findConfigValue(1, WhatsAppPreferredLanguageService.CONFIG_KEY))
                    .thenReturn(Optional.of("{\"foo\":\"bar\"}"));
            assertThat(service.resolvePreferredLanguageId(1)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("config key names")
    class ConfigKeyNames {

        @Test
        @DisplayName("reads the canonical key, and never the legacy key when the canonical one is set")
        void canonicalKeyWins() {
            when(tenantConfigRepository.findConfigValue(1, "WHATSAPP_MESSAGE_TEMPLATES"))
                    .thenReturn(Optional.of("{\"preferredLanguageId\":3}"));

            assertThat(service.resolvePreferredLanguageId(1)).isEqualTo(3);
            verify(tenantConfigRepository, never()).findConfigValue(1, "GLIFIC_MESSAGE_TEMPLATES");
        }

        @Test
        @DisplayName("falls back to the legacy key when the canonical one is absent")
        void legacyKeyFallback() {
            when(tenantConfigRepository.findConfigValue(1, "WHATSAPP_MESSAGE_TEMPLATES"))
                    .thenReturn(Optional.empty());
            when(tenantConfigRepository.findConfigValue(1, "GLIFIC_MESSAGE_TEMPLATES"))
                    .thenReturn(Optional.of("{\"preferredLanguageId\":4}"));

            assertThat(service.resolvePreferredLanguageId(1)).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("explicit preferredLanguageId field")
    class ExplicitPreferred {

        @Test
        @DisplayName("returns preferredLanguageId when present")
        void returnsPreferredLanguageId() {
            when(tenantConfigRepository.findConfigValue(1, WhatsAppPreferredLanguageService.CONFIG_KEY))
                    .thenReturn(Optional.of("{\"preferredLanguageId\":3}"));
            assertThat(service.resolvePreferredLanguageId(1)).isEqualTo(3);
        }

        @Test
        @DisplayName("falls back to defaultLanguageId when preferredLanguageId is 0")
        void fallsToDefaultLanguageId() {
            when(tenantConfigRepository.findConfigValue(1, WhatsAppPreferredLanguageService.CONFIG_KEY))
                    .thenReturn(Optional.of("{\"preferredLanguageId\":0,\"defaultLanguageId\":5}"));
            assertThat(service.resolvePreferredLanguageId(1)).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("LANGUAGE_SELECTION screen options")
    class LanguageSelectionOptions {

        @Test
        @DisplayName("returns OPTION_1 order when present")
        void returnsOption1Order() {
            String json = "{\"screens\":{\"LANGUAGE_SELECTION\":{\"options\":{\"OPTION_1\":{\"order\":2},\"OPTION_2\":{\"order\":1}}}}}";
            when(tenantConfigRepository.findConfigValue(1, WhatsAppPreferredLanguageService.CONFIG_KEY))
                    .thenReturn(Optional.of(json));
            assertThat(service.resolvePreferredLanguageId(1)).isEqualTo(2);
        }

        @Test
        @DisplayName("returns lowest order when OPTION_1 has order 0")
        void returnsLowestOrderWhenOption1IsZero() {
            String json = "{\"screens\":{\"LANGUAGE_SELECTION\":{\"options\":{\"OPTION_1\":{\"order\":0},\"OPTION_2\":{\"order\":3},\"OPTION_3\":{\"order\":2}}}}}";
            when(tenantConfigRepository.findConfigValue(1, WhatsAppPreferredLanguageService.CONFIG_KEY))
                    .thenReturn(Optional.of(json));
            assertThat(service.resolvePreferredLanguageId(1)).isEqualTo(2);
        }

        @Test
        @DisplayName("returns fallback 1 when options object is absent")
        void fallbackWhenOptionsAbsent() {
            String json = "{\"screens\":{\"LANGUAGE_SELECTION\":{}}}";
            when(tenantConfigRepository.findConfigValue(1, WhatsAppPreferredLanguageService.CONFIG_KEY))
                    .thenReturn(Optional.of(json));
            assertThat(service.resolvePreferredLanguageId(1)).isEqualTo(1);
        }
    }
}
