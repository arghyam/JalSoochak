package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlificMessageTemplatesServiceCacheTest {

    @Mock
    private TenantConfigRepository tenantConfigRepository;

    private GlificMessageTemplatesService service;

    @BeforeEach
    void setUp() {
        service = new GlificMessageTemplatesService(tenantConfigRepository, new ObjectMapper());
        ReflectionTestUtils.setField(service, "templatesCacheEnabled", true);
        ReflectionTestUtils.setField(service, "templatesCacheTtlMs", 120_000L);
    }

    @Test
    void loadTemplatesCachesParsedJsonPerTenant() {
        String rawJson = """
                {"screens":{"LANGUAGE_SELECTION":{"prompt":{"en":"Choose"}}}}
                """;
        when(tenantConfigRepository.findConfigValue(5, GlificMessageTemplatesService.CONFIG_KEY))
                .thenReturn(Optional.of(rawJson));

        Optional<com.fasterxml.jackson.databind.JsonNode> first = service.loadTemplates(5);
        Optional<com.fasterxml.jackson.databind.JsonNode> second = service.loadTemplates(5);

        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertEquals("Choose", first.get().path("screens").path("LANGUAGE_SELECTION").path("prompt").path("en").asText());
        verify(tenantConfigRepository, times(1)).findConfigValue(5, GlificMessageTemplatesService.CONFIG_KEY);
    }

    @Test
    void invalidateTemplatesCacheForcesReload() {
        when(tenantConfigRepository.findConfigValue(6, GlificMessageTemplatesService.CONFIG_KEY))
                .thenReturn(Optional.of("{\"screens\":{}}"));

        service.loadTemplates(6);
        service.invalidateTemplatesCache(6);
        service.loadTemplates(6);

        verify(tenantConfigRepository, times(2)).findConfigValue(6, GlificMessageTemplatesService.CONFIG_KEY);
    }
}
