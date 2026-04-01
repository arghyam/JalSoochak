package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedItemRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.SelectionResponse;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlificSelectionServiceSelectedItemTest {

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
    private UserLanguagePreferenceRepository userLanguagePreferenceRepository;
    @Mock
    private GlificContactSyncService glificContactSyncService;

    @Test
    void selectedItemReadingSubmissionReturnsNormalCodeOnlyWhenLocationCheckIsYesAndSchemeHasCoordinates() {
        GlificSelectionService service = new GlificSelectionService(
                operatorContextService,
                localizationService,
                tenantConfigRepository,
                templatesService,
                telemetryTenantRepository,
                userLanguagePreferenceRepository,
                glificContactSyncService,
                new ObjectMapper()
        );

        String contactId = "919999999999";
        Integer tenantId = 1;
        Long schemeId = 11L;
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_x",
                new TelemetryOperator(1L, tenantId, null, null, null, null)
        );

        when(operatorContextService.resolveOperatorWithSchema(eq(contactId))).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(eq(operatorWithSchema), eq(tenantId))).thenReturn("English");
        when(localizationService.normalizeLanguageKey(eq("English"))).thenReturn("english");

        when(templatesService.resolveScreenOptions(eq(tenantId), eq("ITEM_SELECTION"))).thenReturn(List.of());
        when(tenantConfigRepository.findItemOptions(eq(tenantId), eq("english")))
                .thenReturn(List.of("Submit Reading", "Report Issue"));

        when(tenantConfigRepository.findChannelOptions(eq(tenantId), eq("english"))).thenReturn(List.of("OnlyChannel"));
        when(tenantConfigRepository.findLanguageOptions(eq(tenantId))).thenReturn(List.of("English"));
        when(tenantConfigRepository.findConfigValue(eq(tenantId), eq("TENANT_SUPPORTED_CHANNELS")))
                .thenReturn(Optional.empty());

        when(templatesService.resolveScreenConfirmationTemplate(eq(tenantId), eq("ITEM_SELECTION"), eq("english")))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findConfigValue(eq(tenantId), eq("item_selection_confirmation_template_english")))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findConfigValue(eq(tenantId), eq("item_selection_confirmation_template")))
                .thenReturn(Optional.of("{item} selected"));

        when(tenantConfigRepository.findConfigValue(eq(tenantId), eq("LOCATION_CHECK_REQUIRED")))
                .thenReturn(Optional.of("{\"value\":\"YES\"}"));

        when(telemetryTenantRepository.findFirstSchemeForUser(eq("tenant_x"), eq(1L))).thenReturn(Optional.of(schemeId));
        when(telemetryTenantRepository.schemeHasLatitudeAndLongitude(eq("tenant_x"), eq(schemeId))).thenReturn(true);

        SelectionResponse resp = service.selectedItemMessage(
                SelectedItemRequest.builder().contactId(contactId).channel("1").build()
        );

        assertTrue(resp.isSuccess());
        assertEquals("readingSubmission", resp.getSelected());
        assertEquals("Submit Reading selected", resp.getMessage());
    }

    @Test
    void selectedItemReadingSubmissionReturnsLocationNotSelectedWhenSchemeIsMissingCoordinates() {
        GlificSelectionService service = new GlificSelectionService(
                operatorContextService,
                localizationService,
                tenantConfigRepository,
                templatesService,
                telemetryTenantRepository,
                userLanguagePreferenceRepository,
                glificContactSyncService,
                new ObjectMapper()
        );

        String contactId = "919999999999";
        Integer tenantId = 1;
        Long schemeId = 11L;
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_x",
                new TelemetryOperator(1L, tenantId, null, null, null, null)
        );

        when(operatorContextService.resolveOperatorWithSchema(eq(contactId))).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(eq(operatorWithSchema), eq(tenantId))).thenReturn("English");
        when(localizationService.normalizeLanguageKey(eq("English"))).thenReturn("english");

        when(templatesService.resolveScreenOptions(eq(tenantId), eq("ITEM_SELECTION"))).thenReturn(List.of());
        when(tenantConfigRepository.findItemOptions(eq(tenantId), eq("english")))
                .thenReturn(List.of("Submit Reading", "Report Issue"));

        when(tenantConfigRepository.findChannelOptions(eq(tenantId), eq("english"))).thenReturn(List.of("OnlyChannel"));
        when(tenantConfigRepository.findLanguageOptions(eq(tenantId))).thenReturn(List.of("English"));
        when(tenantConfigRepository.findConfigValue(eq(tenantId), eq("TENANT_SUPPORTED_CHANNELS")))
                .thenReturn(Optional.empty());

        when(templatesService.resolveScreenConfirmationTemplate(eq(tenantId), eq("ITEM_SELECTION"), eq("english")))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findConfigValue(eq(tenantId), eq("item_selection_confirmation_template_english")))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findConfigValue(eq(tenantId), eq("item_selection_confirmation_template")))
                .thenReturn(Optional.of("{item} selected"));

        when(tenantConfigRepository.findConfigValue(eq(tenantId), eq("LOCATION_CHECK_REQUIRED")))
                .thenReturn(Optional.of("{\"value\":\"YES\"}"));

        when(telemetryTenantRepository.findFirstSchemeForUser(eq("tenant_x"), eq(1L))).thenReturn(Optional.of(schemeId));
        when(telemetryTenantRepository.schemeHasLatitudeAndLongitude(eq("tenant_x"), eq(schemeId))).thenReturn(false);

        SelectionResponse resp = service.selectedItemMessage(
                SelectedItemRequest.builder().contactId(contactId).channel("1").build()
        );

        assertTrue(resp.isSuccess());
        assertEquals("readingSubmissionLocationNotSelected", resp.getSelected());
        assertEquals("Submit Reading selected", resp.getMessage());
    }
}
