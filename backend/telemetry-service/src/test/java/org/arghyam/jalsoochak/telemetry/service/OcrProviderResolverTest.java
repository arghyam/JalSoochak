package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.env.MockEnvironment;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
        assertNull(resolver.resolve(TENANT, ReadingChannel.BFM));
    }

    @Test
    void returnsNullForNullTenant() {
        assertNull(resolver.resolve(null, ReadingChannel.BFM));
    }

    @Test
    void usesTenantProviderAndFillsUnspecifiedFromDefaults() {
        stub("ocr_provider", "vision-x");

        OcrProviderSettings settings = resolver.resolve(TENANT, ReadingChannel.BFM);

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

        OcrProviderSettings settings = resolver.resolve(TENANT, ReadingChannel.BFM);

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

        OcrProviderSettings settings = resolver.resolve(TENANT, ReadingChannel.BFM);

        assertEquals("env-secret", settings.apiKey());
    }

    @Test
    void unsetEnvReferencedApiKeyResolvesToNull() {
        stub("ocr_url", "https://vision-x/extract");
        stub("ocr_api_key", "env:MISSING_KEY");

        OcrProviderSettings settings = resolver.resolve(TENANT, ReadingChannel.BFM);

        assertNull(settings.apiKey());
    }

    @Test
    void doesNotInheritDefaultApiKeyWhenTenantOverridesEndpoint() {
        OcrProviderResolver keyed = keyedResolver("global-key");
        stub("ocr_url", "https://custom/extract"); // custom endpoint, no ocr_api_key

        OcrProviderSettings settings = keyed.resolve(TENANT, ReadingChannel.BFM);

        assertEquals("https://custom/extract", settings.endpointUrl());
        assertNull(settings.apiKey());
    }

    @Test
    void inheritsDefaultApiKeyWhenEndpointStaysDefault() {
        OcrProviderResolver keyed = keyedResolver("global-key");
        stub("ocr_provider", "flowvision"); // override path triggered, but endpoint unchanged

        OcrProviderSettings settings = keyed.resolve(TENANT, ReadingChannel.BFM);

        assertEquals(DEFAULT_URL, settings.endpointUrl());
        assertEquals("global-key", settings.apiKey());
    }

    @Test
    void tenantApiKeyIsUsedEvenWithCustomEndpoint() {
        OcrProviderResolver keyed = keyedResolver("global-key");
        stub("ocr_url", "https://custom/extract");
        stub("ocr_api_key", "tenant-key");

        OcrProviderSettings settings = keyed.resolve(TENANT, ReadingChannel.BFM);

        assertEquals("tenant-key", settings.apiKey());
    }

    @Test
    void bfmNeverReadsTheElmKeys() {
        stubUnread("ocr_elm_provider", "elm-vision");

        assertNull(resolver.resolve(TENANT, ReadingChannel.BFM));
        verify(tenantConfigRepository, never()).findConfigValue(any(), startsWith("ocr_elm_"));
    }

    @Test
    void elmReturnsNullWhenNoElmKeysConfigured() {
        stubUnread("ocr_provider", "vision-x");
        stubUnread("ocr_url", "https://vision-x/extract");

        assertNull(resolver.resolve(TENANT, ReadingChannel.ELM));
    }

    @Test
    void elmAppliesItsOwnKeys() {
        stub("ocr_elm_provider", "elm-vision");
        stub("ocr_elm_url", "https://elm-vision/extract");
        stub("ocr_elm_api_key", "elm-token");
        stub("ocr_elm_auth_header", "X-Elm-Key");

        OcrProviderSettings settings = resolver.resolve(TENANT, ReadingChannel.ELM);

        assertEquals("elm-vision", settings.providerId());
        assertEquals("https://elm-vision/extract", settings.endpointUrl());
        assertEquals("elm-token", settings.apiKey());
        assertEquals("X-Elm-Key", settings.resolvedAuthHeaderName());
    }

    @Test
    void elmNeverFallsBackToTheGlobalOcrSettings() {
        OcrProviderResolver keyed = keyedResolver("global-key");
        stub("ocr_elm_provider", "elm-vision");

        OcrProviderSettings settings = keyed.resolve(TENANT, ReadingChannel.ELM);

        assertEquals("elm-vision", settings.providerId());
        assertNull(settings.endpointUrl());
        assertNull(settings.apiKey());
        assertNull(settings.authHeaderName());
    }

    @Test
    void elmLeavesAnUnsetProviderToTheChannelsDefault() {
        stub("ocr_elm_url", "https://elm-vision/extract");

        assertNull(resolver.resolve(TENANT, ReadingChannel.ELM).providerId());
    }

    @Test
    void elmResolvesAnEnvReferencedApiKey() {
        environment.setProperty("ELM_KEY", "env-elm-secret");
        stub("ocr_elm_api_key", "env:ELM_KEY");

        assertEquals("env-elm-secret", resolver.resolve(TENANT, ReadingChannel.ELM).apiKey());
    }

    @ParameterizedTest
    @EnumSource(value = ReadingChannel.class, names = {"PDU", "IOT", "MAN"})
    void aChannelThatDoesNotReadPhotosReadsNoConfig(ReadingChannel channel) {
        assertNull(resolver.resolve(TENANT, channel));
        verifyNoInteractions(tenantConfigRepository);
    }

    private OcrProviderResolver keyedResolver(String defaultApiKey) {
        return new OcrProviderResolver(
                tenantConfigRepository, environment, DEFAULT_PROVIDER, DEFAULT_URL, defaultApiKey, "Authorization");
    }

    private void stub(String key, String value) {
        when(tenantConfigRepository.findConfigValue(TENANT, key)).thenReturn(Optional.of(value));
    }

    /** A key the tenant set that the channel under test must not read. */
    private void stubUnread(String key, String value) {
        lenient().when(tenantConfigRepository.findConfigValue(TENANT, key)).thenReturn(Optional.of(value));
    }
}
