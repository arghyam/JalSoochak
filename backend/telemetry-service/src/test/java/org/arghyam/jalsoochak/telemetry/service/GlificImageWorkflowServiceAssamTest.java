package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.dto.requests.AssamReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlificImageWorkflowServiceAssamTest {

    @Mock
    private GlificMediaService glificMediaService;

    @Mock
    private BfmReadingService bfmReadingService;

    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;

    @Mock
    private GlificOperatorContextService operatorContextService;

    @Mock
    private GlificLocalizationService localizationService;

    @InjectMocks
    private GlificImageWorkflowService service;

    @Test
    void processAssamReadingSkipsLocationUpdateWhenGeolocationMissing() {
        AssamReadingRequest request = AssamReadingRequest.builder()
                .readingUrl("https://example.com/meter.jpg")
                .confirmedReading(new BigDecimal("123.4"))
                .stateSchemeId("30178236")
                .centreSchemeId("30244993")
                .phoneNumber("919876543210")
                .readingDateTime(OffsetDateTime.parse("2026-04-23T07:38:22.031Z"))
                .build();

        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_assam",
                new TelemetryOperator(11L, 22, "name", "name@example.com", "919876543210", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919876543210", 22)).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 22)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(localizationService.localizeMessage("Reading created successfully", "english"))
                .thenReturn("Reading created successfully");
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId("tenant_assam", "30178236"))
                .thenReturn(Optional.of(30178236L));
        when(telemetryTenantRepository.isOperatorMappedToScheme("tenant_assam", 11L, 30178236L)).thenReturn(true);
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(CreateReadingResponse.builder()
                        .success(true)
                        .message("Reading created successfully")
                        .correlationId("corr-1")
                        .qualityStatus("CONFIRMED")
                        .build());

        CreateReadingResponse response = service.processAssamReading(request, 22);

        assertNotNull(response);
        assertEquals(true, response.isSuccess());
        verify(telemetryTenantRepository, never()).updateReadingLocation(anyString(), any(), any(), any(), any());
    }

    @Test
    void processAssamReadingUpdatesLocationWhenGeolocationPresent() {
        AssamReadingRequest request = AssamReadingRequest.builder()
                .readingUrl("https://example.com/meter.jpg")
                .confirmedReading(new BigDecimal("123.4"))
                .stateSchemeId("30178236")
                .centreSchemeId("30244993")
                .phoneNumber("919876543210")
                .readingDateTime(OffsetDateTime.parse("2026-04-23T07:38:22.031Z"))
                .geolocation(AssamReadingRequest.Geolocation.builder()
                        .type("Point")
                        .coordinates(List.of(new BigDecimal("56.78"), new BigDecimal("12.34")))
                        .build())
                .build();

        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_assam",
                new TelemetryOperator(11L, 22, "name", "name@example.com", "919876543210", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919876543210", 22)).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 22)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(localizationService.localizeMessage("Reading created successfully", "english"))
                .thenReturn("Reading created successfully");
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId("tenant_assam", "30178236"))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.findSchemeIdByCentreSchemeId("tenant_assam", "30244993"))
                .thenReturn(Optional.of(30244993L));
        when(telemetryTenantRepository.isOperatorMappedToScheme("tenant_assam", 11L, 30244993L)).thenReturn(true);
        when(bfmReadingService.createReading(any(CreateReadingRequest.class), anyString(), any(), anyString(), anyBoolean()))
                .thenReturn(CreateReadingResponse.builder()
                        .success(true)
                        .message("Reading created successfully")
                        .correlationId("corr-1")
                        .qualityStatus("CONFIRMED")
                        .build());
        when(telemetryTenantRepository.findReadingByCorrelationId("tenant_assam", "corr-1"))
                .thenReturn(Optional.of(new TelemetryReadingRecord(100L, "corr-1", 11L)));

        CreateReadingResponse response = service.processAssamReading(request, 22);

        assertNotNull(response);
        assertEquals(true, response.isSuccess());

        ArgumentCaptor<CreateReadingRequest> requestCaptor = ArgumentCaptor.forClass(CreateReadingRequest.class);
        verify(bfmReadingService).createReading(requestCaptor.capture(), anyString(), any(), anyString(), anyBoolean());
        assertEquals(30244993L, requestCaptor.getValue().getSchemeId());
        assertEquals(new BigDecimal("123.4"), requestCaptor.getValue().getReadingValue());

        verify(telemetryTenantRepository).updateReadingLocation(
                "tenant_assam",
                100L,
                new BigDecimal("12.34"),
                new BigDecimal("56.78"),
                11L
        );
    }
}
