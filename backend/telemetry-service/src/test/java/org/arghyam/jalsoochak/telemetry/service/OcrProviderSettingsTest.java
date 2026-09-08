package org.arghyam.jalsoochak.telemetry.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OcrProviderSettingsTest {

    @Test
    void toStringRedactsApiKeyButKeepsOtherFields() {
        OcrProviderSettings settings =
                new OcrProviderSettings("vision-x", "https://vision-x/extract", "super-secret", "X-Api-Key");

        String rendered = settings.toString();

        assertFalse(rendered.contains("super-secret"), "api key must not appear in toString()");
        assertTrue(rendered.contains("***REDACTED***"));
        assertTrue(rendered.contains("vision-x"));
        assertTrue(rendered.contains("https://vision-x/extract"));
        assertTrue(rendered.contains("X-Api-Key"));
    }

    @Test
    void toStringShowsNoRedactionMarkerWhenApiKeyBlank() {
        OcrProviderSettings settings =
                new OcrProviderSettings("flowvision", "https://default/extract", null, "Authorization");

        assertFalse(settings.toString().contains("REDACTED"));
    }

    @Test
    void accessorsRemainUnchanged() {
        OcrProviderSettings settings =
                new OcrProviderSettings("vision-x", "https://vision-x/extract", "k", "X-Api-Key");

        assertEquals("vision-x", settings.providerId());
        assertEquals("https://vision-x/extract", settings.endpointUrl());
        assertEquals("k", settings.apiKey());
        assertTrue(settings.hasApiKey());
        assertEquals("X-Api-Key", settings.resolvedAuthHeaderName());
    }
}
