package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedItemRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.SelectionResponse;
import org.arghyam.jalsoochak.telemetry.provider.whatsapp.WhatsAppContactDirectory;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationSelectionServiceSelectedItemTest {

    @Mock
    private OperatorContextService operatorContextService;
    @Mock
    private ConversationLocalizationService localizationService;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private ConversationTemplateService templatesService;
    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;
    @Mock
    private UserChannelPreferenceRepository userChannelPreferenceRepository;
    @Mock
    private UserLanguagePreferenceRepository userLanguagePreferenceRepository;
    @Mock
    private WhatsAppContactDirectory whatsAppContactDirectory;

    @Test
    void selectedItemReadingSubmissionReturnsNormalCodeOnlyWhenLocationCheckIsYesAndSchemeHasCoordinates() {
        ConversationSelectionService service = new ConversationSelectionService(
                operatorContextService,
                localizationService,
                tenantConfigRepository,
                templatesService,
                telemetryTenantRepository,
                userChannelPreferenceRepository,
                userLanguagePreferenceRepository,
                whatsAppContactDirectory,
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
        ConversationSelectionService service = new ConversationSelectionService(
                operatorContextService,
                localizationService,
                tenantConfigRepository,
                templatesService,
                telemetryTenantRepository,
                userChannelPreferenceRepository,
                userLanguagePreferenceRepository,
                whatsAppContactDirectory,
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

    /**
     * NUDGE-ENTRY: the nudge flow jumps into v6 at the reading path by posting the item code, since it
     * cannot know the tenant's menu numbering or its localized labels.
     */
    @Test
    void selectedItemAcceptsTheItemCode_andAppliesTheSameLocationCheckAsTheMenuNumber() {
        ConversationSelectionService service = stubbedServiceWithMenu(true);

        SelectionResponse resp = service.selectedItemMessage(
                SelectedItemRequest.builder().contactId("919999999999").channel("readingSubmission").build()
        );

        assertTrue(resp.isSuccess());
        assertEquals("readingSubmission", resp.getSelected());
    }

    @Test
    void selectedItemAcceptsTheItemCode_caseInsensitively_forOtherItems() {
        ConversationSelectionService service = stubbedServiceWithMenu(true);

        SelectionResponse resp = service.selectedItemMessage(
                SelectedItemRequest.builder().contactId("919999999999").channel("REPORTISSUE").build()
        );

        assertTrue(resp.isSuccess());
        assertEquals("reportIssue", resp.getSelected());
    }

    @Test
    void selectedItemStillRejectsAnUnknownSelection() {
        ConversationSelectionService service = stubbedServiceWithMenu(false);
        org.mockito.Mockito.lenient().when(localizationService.resolveUserFacingErrorMessage(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any()))
                .thenReturn("Item selection could not be saved.");

        SelectionResponse resp = service.selectedItemMessage(
                SelectedItemRequest.builder().contactId("919999999999").channel("notAnItem").build()
        );

        assertEquals(false, resp.isSuccess());
    }

    private ConversationSelectionService stubbedServiceWithMenu(boolean confirmationStubs) {
        ConversationSelectionService service = new ConversationSelectionService(
                operatorContextService, localizationService, tenantConfigRepository, templatesService,
                telemetryTenantRepository, userChannelPreferenceRepository, userLanguagePreferenceRepository,
                whatsAppContactDirectory, new ObjectMapper()
        );
        Integer tenantId = 1;
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_x", new TelemetryOperator(1L, tenantId, null, null, null, null));
        when(operatorContextService.resolveOperatorWithSchema(eq("919999999999"))).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(eq(operatorWithSchema), eq(tenantId))).thenReturn("English");
        when(localizationService.normalizeLanguageKey(eq("English"))).thenReturn("english");
        when(templatesService.resolveScreenOptions(eq(tenantId), eq("ITEM_SELECTION"))).thenReturn(List.of());
        when(tenantConfigRepository.findItemOptions(eq(tenantId), eq("english")))
                .thenReturn(List.of("Submit Reading", "Report Issue"));
        org.mockito.Mockito.lenient().when(tenantConfigRepository.findChannelOptions(eq(tenantId), eq("english"))).thenReturn(List.of("OnlyChannel"));
        org.mockito.Mockito.lenient().when(tenantConfigRepository.findLanguageOptions(eq(tenantId))).thenReturn(List.of("English"));
        org.mockito.Mockito.lenient().when(tenantConfigRepository.findConfigValue(eq(tenantId), eq("TENANT_SUPPORTED_CHANNELS")))
                .thenReturn(Optional.empty());
        if (confirmationStubs) {
            org.mockito.Mockito.lenient().when(templatesService.resolveScreenConfirmationTemplate(eq(tenantId), eq("ITEM_SELECTION"), eq("english")))
                    .thenReturn(Optional.empty());
            org.mockito.Mockito.lenient().when(tenantConfigRepository.findConfigValue(eq(tenantId), eq("item_selection_confirmation_template_english")))
                    .thenReturn(Optional.empty());
            org.mockito.Mockito.lenient().when(tenantConfigRepository.findConfigValue(eq(tenantId), eq("item_selection_confirmation_template")))
                    .thenReturn(Optional.of("{item} selected"));
            org.mockito.Mockito.lenient().when(tenantConfigRepository.findConfigValue(eq(tenantId), eq("LOCATION_CHECK_REQUIRED")))
                    .thenReturn(Optional.of("{\"value\":\"YES\"}"));
            org.mockito.Mockito.lenient().when(telemetryTenantRepository.findFirstSchemeForUser(eq("tenant_x"), eq(1L))).thenReturn(Optional.of(11L));
            org.mockito.Mockito.lenient().when(telemetryTenantRepository.schemeHasLatitudeAndLongitude(eq("tenant_x"), eq(11L))).thenReturn(true);
        }
        return service;
    }
}
