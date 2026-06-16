package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserChannelPreferenceRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserLanguagePreferenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlificSelectionServiceChannelSelectionTest {

    @Mock
    private GlificOperatorContextService operatorContextService;
    @Mock
    private GlificLocalizationService localizationService;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private GlificMessageTemplatesService templatesService;
    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;
    @Mock
    private UserChannelPreferenceRepository userChannelPreferenceRepository;
    @Mock
    private UserLanguagePreferenceRepository userLanguagePreferenceRepository;
    @Mock
    private GlificContactSyncService glificContactSyncService;

    @Test
    void channelSelectionMessageReturnsTenantSupportedChannels() {
        GlificSelectionService service = new GlificSelectionService(
                operatorContextService,
                localizationService,
                tenantConfigRepository,
                templatesService,
                telemetryTenantRepository,
                userChannelPreferenceRepository,
                userLanguagePreferenceRepository,
                glificContactSyncService,
                new ObjectMapper()
        );

        String contactId = "917815816856";
        Integer tenantId = 17;
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, tenantId, "op", "op@example.com", contactId, null)
        );

        when(operatorContextService.resolveOperatorWithSchema(eq(contactId))).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(eq(operatorWithSchema), eq(tenantId))).thenReturn("English");
        when(localizationService.normalizeLanguageKey(eq("English"))).thenReturn("english");
        when(templatesService.resolveScreenPrompt(eq(tenantId), eq("CHANNEL_SELECTION"), eq("english")))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findChannelSelectionPrompt(eq(tenantId), eq("english")))
                .thenReturn(Optional.of("Please select your preferred channel by typing the corresponding number:"));
        when(tenantConfigRepository.findConfigValue(eq(tenantId), eq("TENANT_SUPPORTED_CHANNELS")))
                .thenReturn(Optional.of("{\"channels\":[\"PDU\",\"IOT\",\"ELM\"]}"));

        IntroResponse response = service.channelSelectionMessage(
                IntroRequest.builder().contactId(contactId).build()
        );

        assertTrue(response.isSuccess());
        assertEquals(
                "Please select your preferred channel by typing the corresponding number:\n1. PDU\n2. IOT\n3. ELM",
                response.getMessage()
        );
        assertEquals("three", response.getCorrelationId());
        assertEquals("bfmOrElectricNotpresentandcorrelationIsThree", response.getIsBfmOrIsElectric());
        assertEquals(List.of("PDU", "IOT", "ELM"), response.getTenantSupportedChannels());
        assertNull(response.getSelected());
    }
}
