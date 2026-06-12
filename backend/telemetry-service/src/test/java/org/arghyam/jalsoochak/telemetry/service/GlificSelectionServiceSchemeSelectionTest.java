package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetrySchemeOption;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserChannelPreferenceRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserLanguagePreferenceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlificSelectionServiceSchemeSelectionTest {

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
    void schemeSelectionMessageReturnsFalseWhenSingleSchemeExists() {
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

        String contactId = "919999999999";
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_x",
                new TelemetryOperator(1L, 1, null, null, null, null)
        );
        when(operatorContextService.resolveOperatorWithSchema(eq(contactId))).thenReturn(operatorWithSchema);
        when(telemetryTenantRepository.findSchemesForUser(eq("tenant_x"), eq(1L)))
                .thenReturn(List.of(new TelemetrySchemeOption(11L, "S2604141906")));

        IntroResponse response = service.schemeSelectionMessage(IntroRequest.builder().contactId(contactId).build());

        assertTrue(response.isSuccess());
        assertFalse(response.getIsSchemeGreaterThanOne());
    }

    @Test
    void schemeSelectionMessageReturnsTrueWhenMoreThanOneSchemeExists() {
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

        String contactId = "919999999999";
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_x",
                new TelemetryOperator(1L, 1, null, null, null, null)
        );
        when(operatorContextService.resolveOperatorWithSchema(eq(contactId))).thenReturn(operatorWithSchema);
        when(telemetryTenantRepository.findSchemesForUser(eq("tenant_x"), eq(1L)))
                .thenReturn(List.of(
                        new TelemetrySchemeOption(11L, "Scheme 1"),
                        new TelemetrySchemeOption(12L, "Scheme 2")
                ));

        IntroResponse response = service.schemeSelectionMessage(IntroRequest.builder().contactId(contactId).build());

        assertTrue(response.isSuccess());
        assertTrue(response.getIsSchemeGreaterThanOne());
    }
}
