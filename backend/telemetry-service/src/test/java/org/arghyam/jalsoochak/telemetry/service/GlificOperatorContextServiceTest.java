package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserLanguagePreferenceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Resolution of a Glific contact id — which may arrive as a plain phone number, an HMAC phone hash,
 * or an encrypted blob — to the tenant schema and operator behind it, plus the language fallback
 * chain used to localise replies.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GlificOperatorContextService")
class GlificOperatorContextServiceTest {

    private static final String PHONE = "919999900001";
    private static final String SCHEMA = "tenant_as";

    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;
    @Mock
    private UserLanguagePreferenceRepository userLanguagePreferenceRepository;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private PiiEncryptionService piiEncryptionService;

    @InjectMocks
    private GlificOperatorContextService service;

    private static TelemetryOperatorWithSchema operator(Integer languageId) {
        return new TelemetryOperatorWithSchema(SCHEMA,
                new TelemetryOperator(11L, 3, "Asha", "asha@example.org", PHONE, languageId));
    }

    @Nested
    @DisplayName("resolveOperatorWithSchema")
    class Resolve {

        @Test
        void rejectsMissingContactId() {
            assertThatThrownBy(() -> service.resolveOperatorWithSchema(null, 3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("contactId is required");
            assertThatThrownBy(() -> service.resolveOperatorWithSchema("   ", 3))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void resolvesPlainPhoneNumberThroughCrossTenantLookup() {
            when(telemetryTenantRepository.findOperatorByPhoneAcrossTenants(PHONE, 3))
                    .thenReturn(Optional.of(operator(1)));

            assertThat(service.resolveOperatorWithSchema(PHONE, 3).schemaName()).isEqualTo(SCHEMA);
        }

        @Test
        void trimsContactIdBeforeLookup() {
            when(telemetryTenantRepository.findOperatorByPhoneAcrossTenants(PHONE, 3))
                    .thenReturn(Optional.of(operator(1)));

            assertThat(service.resolveOperatorWithSchema("  " + PHONE + "  ", 3)).isNotNull();
        }

        @Test
        void routesA64CharHexContactIdToTheHashLookup() {
            String hash = "A".repeat(64);
            when(telemetryTenantRepository.findOperatorByPhoneHashAcrossTenants(hash, 3))
                    .thenReturn(Optional.of(operator(1)));

            assertThat(service.resolveOperatorWithSchema(hash, 3).operator().id()).isEqualTo(11L);
            verify(telemetryTenantRepository, never()).findOperatorByPhoneAcrossTenants(anyString(), any());
        }

        @Test
        void throwsWhenHashLookupFindsNoOperator() {
            String hash = "b".repeat(64);
            when(telemetryTenantRepository.findOperatorByPhoneHashAcrossTenants(hash, 3))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolveOperatorWithSchema(hash, 3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No operator found");
        }

        @Test
        void decryptsBase64LookingContactIdAndRetriesWithThePlaintext() {
            String encrypted = "QUJDREVGR0hJSktMTU5PUFFSU1RVVg==";
            when(piiEncryptionService.decrypt(encrypted)).thenReturn(PHONE);
            when(telemetryTenantRepository.findOperatorByPhoneAcrossTenants(PHONE, 3))
                    .thenReturn(Optional.of(operator(1)));

            assertThat(service.resolveOperatorWithSchema(encrypted, 3).operator().phoneNumber())
                    .isEqualTo(PHONE);
        }

        @Test
        void fallsBackToRawLookupWhenDecryptionFails() {
            String encrypted = "QUJDREVGR0hJSktMTU5PUFFSU1RVVg==";
            when(piiEncryptionService.decrypt(encrypted)).thenThrow(new IllegalStateException("bad key"));
            when(telemetryTenantRepository.findOperatorByPhoneAcrossTenants(encrypted, 3))
                    .thenReturn(Optional.of(operator(1)));

            assertThat(service.resolveOperatorWithSchema(encrypted, 3)).isNotNull();
        }

        @Test
        void fallsBackToRawLookupWhenDecryptedValueFindsNoOperator() {
            String encrypted = "QUJDREVGR0hJSktMTU5PUFFSU1RVVg==";
            when(piiEncryptionService.decrypt(encrypted)).thenReturn("910000000000");
            when(telemetryTenantRepository.findOperatorByPhoneAcrossTenants("910000000000", 3))
                    .thenReturn(Optional.empty());
            when(telemetryTenantRepository.findOperatorByPhoneAcrossTenants(encrypted, 3))
                    .thenReturn(Optional.of(operator(1)));

            assertThat(service.resolveOperatorWithSchema(encrypted, 3)).isNotNull();
        }

        @Test
        void throwsWhenNoOperatorMatchesThePhoneNumber() {
            when(telemetryTenantRepository.findOperatorByPhoneAcrossTenants(PHONE, 3))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.resolveOperatorWithSchema(PHONE, 3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No operator found for contactId");
        }

        @Test
        void singleArgOverloadTakesPreferredTenantFromTheContactsLanguagePreference() {
            when(userLanguagePreferenceRepository.findPreferredTenantIdByContactId(PHONE))
                    .thenReturn(Optional.of(7));
            when(telemetryTenantRepository.findOperatorByPhoneAcrossTenants(PHONE, 7))
                    .thenReturn(Optional.of(operator(1)));

            assertThat(service.resolveOperatorWithSchema(PHONE)).isNotNull();
            verify(telemetryTenantRepository).findOperatorByPhoneAcrossTenants(PHONE, 7);
        }

        @Test
        void singleArgOverloadPassesNullWhenNoPreferenceIsStored() {
            when(userLanguagePreferenceRepository.findPreferredTenantIdByContactId(PHONE))
                    .thenReturn(Optional.empty());
            when(telemetryTenantRepository.findOperatorByPhoneAcrossTenants(PHONE, null))
                    .thenReturn(Optional.of(operator(1)));

            assertThat(service.resolveOperatorWithSchema(PHONE)).isNotNull();
        }
    }

    @Nested
    @DisplayName("tryResolveOperatorWithSchema")
    class TryResolve {

        @Test
        void returnsEmptyWhenNoOperatorIsRegistered() {
            when(telemetryTenantRepository.findOperatorByPhoneAcrossTenants(PHONE, 3))
                    .thenReturn(Optional.empty());

            assertThat(service.tryResolveOperatorWithSchema(PHONE, 3)).isEmpty();
        }

        @Test
        void returnsTheOperatorWhenRegistered() {
            when(telemetryTenantRepository.findOperatorByPhoneAcrossTenants(PHONE, 3))
                    .thenReturn(Optional.of(operator(1)));

            assertThat(service.tryResolveOperatorWithSchema(PHONE, 3)).isPresent();
        }

        @Test
        void stillThrowsForAMissingContactIdBecauseThatIsAValidationError() {
            assertThatThrownBy(() -> service.tryResolveOperatorWithSchema(null, 3))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("contactId is required");
            assertThatThrownBy(() -> service.tryResolveOperatorWithSchema("  ", 3))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("resolveOperatorLanguage")
    class LanguageResolution {

        @Test
        void defaultsToEnglishForAMissingOperator() {
            assertThat(service.resolveOperatorLanguage(null, 3)).isEqualTo("English");
            assertThat(service.resolveOperatorLanguage(
                    new TelemetryOperatorWithSchema(SCHEMA, null), 3)).isEqualTo("English");
        }

        @Test
        void prefersTheContactsExplicitlyStoredLanguage() {
            when(userLanguagePreferenceRepository.findLanguage(3, PHONE))
                    .thenReturn(Optional.of("Assamese"));

            assertThat(service.resolveOperatorLanguage(operator(2), 3)).isEqualTo("Assamese");
            verify(tenantConfigRepository, never()).findLanguageOptions(anyInt());
        }

        @Test
        void ignoresABlankStoredLanguage() {
            when(userLanguagePreferenceRepository.findLanguage(3, PHONE)).thenReturn(Optional.of("  "));
            when(tenantConfigRepository.findLanguageOptions(3)).thenReturn(List.of("English", "Hindi"));

            assertThat(service.resolveOperatorLanguage(operator(2), 3)).isEqualTo("Hindi");
        }

        @Test
        void fallsBackToTheTenantLanguageListIndexedByLanguageId() {
            when(userLanguagePreferenceRepository.findLanguage(eq(3), anyString())).thenReturn(Optional.empty());
            when(tenantConfigRepository.findLanguageOptions(3))
                    .thenReturn(List.of("English", "Hindi", "Assamese"));

            assertThat(service.resolveOperatorLanguage(operator(3), 3)).isEqualTo("Assamese");
        }

        @Test
        void defaultsToEnglishWhenLanguageIdIsMissingOrNonPositive() {
            when(userLanguagePreferenceRepository.findLanguage(eq(3), anyString())).thenReturn(Optional.empty());

            assertThat(service.resolveOperatorLanguage(operator(null), 3)).isEqualTo("English");
            assertThat(service.resolveOperatorLanguage(operator(0), 3)).isEqualTo("English");
            assertThat(service.resolveOperatorLanguage(operator(-1), 3)).isEqualTo("English");
        }

        @Test
        void defaultsToEnglishWhenLanguageIdIsOutOfRangeForTheTenant() {
            when(userLanguagePreferenceRepository.findLanguage(eq(3), anyString())).thenReturn(Optional.empty());
            when(tenantConfigRepository.findLanguageOptions(3)).thenReturn(List.of("English", "Hindi"));

            assertThat(service.resolveOperatorLanguage(operator(9), 3)).isEqualTo("English");
        }

        @Test
        void defaultsToEnglishWhenTheTenantHasNoConfiguredLanguages() {
            when(userLanguagePreferenceRepository.findLanguage(eq(3), anyString())).thenReturn(Optional.empty());
            when(tenantConfigRepository.findLanguageOptions(3)).thenReturn(List.of());

            assertThat(service.resolveOperatorLanguage(operator(1), 3)).isEqualTo("English");
        }

        @Test
        void skipsThePreferenceLookupWhenNoTenantIdIsKnown() {
            when(tenantConfigRepository.findLanguageOptions(null)).thenReturn(List.of("English", "Hindi"));

            assertThat(service.resolveOperatorLanguage(operator(2), null)).isEqualTo("Hindi");
            verify(userLanguagePreferenceRepository, never()).findLanguage(any(), anyString());
        }
    }
}
