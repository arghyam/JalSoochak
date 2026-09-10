package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedChannelRequest;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlificSelectionServiceSelectedChannelTest {

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
    void selectedChannelMessageSavesResolvedChannelLabelToSchemePreference() {
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
        Integer tenantId = 218;
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, tenantId, "op", "op@example.com", contactId, null)
        );

        when(operatorContextService.resolveOperatorWithSchema(eq(contactId))).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(eq(operatorWithSchema), eq(tenantId))).thenReturn("English");
        when(localizationService.normalizeLanguageKey(eq("English"))).thenReturn("english");
        when(tenantConfigRepository.findConfigValue(eq(tenantId), eq("TENANT_SUPPORTED_CHANNELS")))
                .thenReturn(Optional.of("{\"channels\":[\"Bfm\",\"Iot\"]}"));
        when(telemetryTenantRepository.findFirstSchemeForUser(eq("tenant_test"), eq(1L))).thenReturn(Optional.of(99L));
        when(templatesService.resolveScreenConfirmationTemplate(eq(tenantId), eq("CHANNEL_SELECTION"), eq("english")))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findConfigValue(eq(tenantId), eq("channel_selection_confirmation_template_english")))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findConfigValue(eq(tenantId), eq("channel_selection_confirmation_template")))
                .thenReturn(Optional.of("Channel selected: {channel}"));

        IntroResponse response = service.selectedChannelMessage(
                SelectedChannelRequest.builder().contactId(contactId).channel("2").build()
        );

        assertTrue(response.isSuccess());
        assertEquals("Channel selected: Iot", response.getMessage());
        verify(telemetryTenantRepository).updateSchemeChannel("tenant_test", 99L, 2);
        verify(userChannelPreferenceRepository).upsert(tenantId, 99L, "Iot");
    }
}
