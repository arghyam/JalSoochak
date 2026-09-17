package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tenant resolution from the inbound {@code X-API-Key} header. Only the SHA-256 hash of the key
 * ever reaches the database, so the stored config never holds a usable credential.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TelemetryApiKeyService")
class TelemetryApiKeyServiceTest {

    private static final String SECRET_KEY = "secret-key";

    @Mock
    private TenantConfigRepository tenantConfigRepository;

    @InjectMocks
    private TelemetryApiKeyService service;

    @Test
    void hashProducesLowercaseHexSha256() {
        String hash = service.hash(SECRET_KEY);

        assertThat(hash).hasSize(64).matches("^[0-9a-f]{64}$");
    }

    @Test
    void hashIsStableAcrossCalls() {
        assertThat(service.hash(SECRET_KEY)).isEqualTo(service.hash(SECRET_KEY));
    }

    @Test
    void hashDiffersForDifferentKeys() {
        assertThat(service.hash("key-a")).isNotEqualTo(service.hash("key-b"));
    }

    @Test
    void hashMatchesTheKnownSha256OfTheEmptyString() {
        assertThat(service.hash(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void hashIsSensitiveToTrailingWhitespace() {
        assertThat(service.hash(SECRET_KEY)).isNotEqualTo(service.hash(SECRET_KEY + " "));
    }

    @Test
    void resolveTenantIdReturnsEmptyForAMissingKey() {
        assertThat(service.resolveTenantIdFromRawApiKey(null)).isEmpty();
        assertThat(service.resolveTenantIdFromRawApiKey("")).isEmpty();
        assertThat(service.resolveTenantIdFromRawApiKey("   ")).isEmpty();

        verify(tenantConfigRepository, never()).findTenantIdByApiKeyHash(anyString());
    }

    @Test
    void resolveTenantIdLooksUpTheHashRatherThanTheRawKey() {
        when(tenantConfigRepository.findTenantIdByApiKeyHash(anyString())).thenReturn(Optional.of(17));

        assertThat(service.resolveTenantIdFromRawApiKey(SECRET_KEY)).contains(17);
        verify(tenantConfigRepository).findTenantIdByApiKeyHash(service.hash(SECRET_KEY));
        verify(tenantConfigRepository, never()).findTenantIdByApiKeyHash(SECRET_KEY);
    }

    @Test
    void resolveTenantIdTrimsTheKeyBeforeHashing() {
        when(tenantConfigRepository.findTenantIdByApiKeyHash(service.hash(SECRET_KEY)))
                .thenReturn(Optional.of(17));

        assertThat(service.resolveTenantIdFromRawApiKey("  " + SECRET_KEY + "  ")).contains(17);
    }

    @Test
    void resolveTenantIdReturnsEmptyForAnUnknownKey() {
        when(tenantConfigRepository.findTenantIdByApiKeyHash(anyString())).thenReturn(Optional.empty());

        assertThat(service.resolveTenantIdFromRawApiKey("unknown-key")).isEmpty();
    }
}
