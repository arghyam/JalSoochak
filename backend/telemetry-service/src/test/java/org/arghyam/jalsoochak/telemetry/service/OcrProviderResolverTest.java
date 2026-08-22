package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OcrProviderResolverTest {

    private static final String DEFAULT_PROVIDER = "flowvision";
    private static final String DEFAULT_URL = "https://default/extract";
    private static final Integer TENANT = 7;

    @Mock
    private TenantConfigRepository tenantConfigRepository;

    private MockEnvironment environment;
    private OcrProviderResolver resolver;

    @BeforeEach
    void setUp() {
        environment = new MockEnvironment();
        resolver = new OcrProviderResolver(
                tenantConfigRepository,
                environment,
                DEFAULT_PROVIDER,
                DEFAULT_URL,
                "",
                "Authorization");
        // Default every key to absent; individual tests override the ones they care about.
        lenient().when(tenantConfigRepository.findConfigValue(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void returnsNullWhenNoTenantOverrideConfigured() {
        assertNull(resolver.resolve(TENANT));
    }

    @Test
    void returnsNullForNullTenant() {
        assertNull(resolver.resolve(null));
    }

    @Test
    void usesTenantProviderAndFillsUnspecifiedFromDefaults() {
        stub("ocr_provider", "vision-x");

        OcrProviderSettings settings = resolver.resolve(TENANT);

        assertEquals("vision-x", settings.providerId());
        assertEquals(DEFAULT_URL, settings.endpointUrl());
        assertNull(settings.apiKey());
        assertEquals("Authorization", settings.resolvedAuthHeaderName());
    }

    @Test
    void appliesTenantUrlAndApiKeyAndAuthHeaderOverrides() {
        stub("ocr_provider", "vision-x");
        stub("ocr_url", "https://vision-x/extract");
        stub("ocr_api_key", "secret-token");
        stub("ocr_auth_header", "X-Api-Key");

        OcrProviderSettings settings = resolver.resolve(TENANT);

        assertEquals("vision-x", settings.providerId());
        assertEquals("https://vision-x/extract", settings.endpointUrl());
        assertEquals("secret-token", settings.apiKey());
        assertEquals("X-Api-Key", settings.resolvedAuthHeaderName());
    }

    @Test
    void resolvesEnvReferencedApiKeyFromEnvironment() {
        environment.setProperty("VISION_X_KEY", "env-secret");
        stub("ocr_url", "https://vision-x/extract");
        stub("ocr_api_key", "env:VISION_X_KEY");

        OcrProviderSettings settings = resolver.resolve(TENANT);

        assertEquals("env-secret", settings.apiKey());
    }

    @Test
    void unsetEnvReferencedApiKeyResolvesToNull() {
        stub("ocr_url", "https://vision-x/extract");
        stub("ocr_api_key", "env:MISSING_KEY");

        OcrProviderSettings settings = resolver.resolve(TENANT);

        assertNull(settings.apiKey());
    }

    private void stub(String key, String value) {
        when(tenantConfigRepository.findConfigValue(TENANT, key)).thenReturn(Optional.of(value));
    }
}
