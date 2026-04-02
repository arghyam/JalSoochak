package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BfmReadingServicePlaceholderRowTest {

    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;

    @Mock
    private FlowVisionService flowVisionService;

    @Mock
    private TelemetryEventPublisher telemetryEventPublisher;

    @Mock
    private TenantConfigRepository tenantConfigRepository;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private BfmReadingService service;

    @Test
    void createReadingUpdatesPlaceholderRowInsteadOfInsertingNewRow() {
        String schemaName = "tenant_test";
        TelemetryOperator operator = new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null);

        CreateReadingRequest request = CreateReadingRequest.builder()
                .schemeId(10L)
                .operatorId(1L)
                .readingUrl("http://example.com/img.jpg")
                .build();

        when(telemetryTenantRepository.existsSchemeById(schemaName, 10L)).thenReturn(true);
        when(telemetryTenantRepository.findOperatorById(schemaName, 1L)).thenReturn(Optional.of(operator));
        when(telemetryTenantRepository.isOperatorMappedToScheme(schemaName, 1L, 10L)).thenReturn(true);

        when(flowVisionService.extractReading("http://example.com/img.jpg")).thenReturn(
                FlowVisionResult.builder()
                        .correlationId("corr-1")
                        .qualityStatus("GOOD")
                        .qualityConfidence(new BigDecimal("0.95"))
                        .adjustedReading(new BigDecimal("123"))
                        .build()
        );

        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot(schemaName, 10L, null))
                .thenReturn(Optional.empty());

        when(telemetryTenantRepository.findLatestPlaceholderFlowReadingIdForDate(
                schemaName,
                10L,
                1L,
                LocalDate.now()
        )).thenReturn(Optional.of(99L));

        CreateReadingResponse resp = service.createReading(request, schemaName, operator, "919999999999", false);

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals(new BigDecimal("123"), resp.getMeterReading());
        assertEquals("corr-1", resp.getCorrelationId());

        verify(telemetryTenantRepository).updateFlowReadingFromIngestion(
                anyString(),
                anyLong(),
                any(LocalDateTime.class),
                any(BigDecimal.class),
                any(BigDecimal.class),
                anyString(),
                anyString(),
                any(),
                anyLong()
        );
        verify(telemetryTenantRepository, never()).createFlowReading(
                anyString(), anyLong(), anyLong(), any(), any(), any(), anyString(), anyString(), any()
        );
    }
}
