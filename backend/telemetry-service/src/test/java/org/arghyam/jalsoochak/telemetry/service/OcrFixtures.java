package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;

import java.util.List;

import static org.mockito.Mockito.when;

/** Builds OCR collaborators for service tests that send photos through the real image capture. */
public final class OcrFixtures {

    private OcrFixtures() {
    }

    /**
     * The real registry, with {@code bfmDefault} (a mock) registered as BFM's default provider. Photos are
     * then chosen by channel as in production: BFM photos reach the mock, and other channels have no
     * provider.
     */
    public static OcrProviderRegistry registryWithBfmDefault(MeterReadingExtractor bfmDefault) {
        when(bfmDefault.providerId()).thenReturn(OcrProviderSettings.DEFAULT_PROVIDER_ID);
        when(bfmDefault.channel()).thenReturn(ReadingChannel.BFM);
        return new OcrProviderRegistry(List.of(bfmDefault), OcrProviderSettings.DEFAULT_PROVIDER_ID, null);
    }
}
