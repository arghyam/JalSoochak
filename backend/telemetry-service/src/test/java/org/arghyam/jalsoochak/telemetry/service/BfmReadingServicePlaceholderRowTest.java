package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannelResolver;
import org.arghyam.jalsoochak.telemetry.config.TenantContext;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryLatestFlowReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private GlificOperatorContextService glificOperatorContextService;

    @Mock
    private FlowVisionReadingsRetryService flowVisionReadingsRetryService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ReadingChannelResolver readingChannelResolver;

    @Mock
    private RolloverResolutionService rolloverResolutionService;

    @InjectMocks
    private BfmReadingService service;

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

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
                        .requestId("request-1")
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
        when(readingChannelResolver.resolve(1, "919999999999")).thenReturn(ReadingChannel.BFM);

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
                eq("corr-1"),
                anyString(),
                any(),
                anyLong()
        );
        // The resolved channel is persisted onto the reading row by its short code.
        verify(telemetryTenantRepository).updateFlowReadingChannel(schemaName, 99L, ReadingChannel.BFM.name());
        verify(telemetryTenantRepository, never()).createFlowReading(
                anyString(), anyLong(), anyLong(), any(), any(), any(), anyString(), anyString(), any()
        );
    }

    @Test
    void readingsApiOutageDoesNotRecordUnreadableImageAnomaly() {
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
        when(flowVisionReadingsRetryService.extractReading("http://example.com/img.jpg"))
                .thenThrow(new FlowVisionReadingsUnavailableException("temporarily unavailable", new RuntimeException("timeout")));

        CreateReadingResponse resp = service.createReading(
                request,
                schemaName,
                operator,
                "919999999999",
                false,
                FlowVisionRetryMode.RESILIENT
        );

        assertNotNull(resp);
        assertEquals(false, resp.isSuccess());
        assertEquals("Meter reading service is temporarily unavailable. Please try again shortly.", resp.getMessage());
        assertEquals("RETRY", resp.getQualityStatus());
        verify(telemetryTenantRepository, never()).createTenantAnomalyRecord(
                anyString(), anyLong(), anyLong(), anyInt(), anyString(), anyInt()
        );
        verify(telemetryEventPublisher, never()).publishAnomalyRecorded(
                any(),
                anyInt(),
                anyLong(),
                anyLong(),
                any(),
                any(),
                any(),
                anyInt(),
                any(),
                any(),
                anyInt(),
                anyString(),
                anyInt(),
                anyString()
        );
    }

    @Test
    void updateConfirmedReadingWithoutCorrelationIdUpdatesLatestReadingAndPublishesEvent() {
        String schemaName = "tenant_test";
        TelemetryOperator operator = new TelemetryOperator(1L, 22, "op", "op@example.com", "919999999999", null);
        LocalDate readingDate = LocalDate.of(2026, 6, 22);
        LocalDateTime readingAt = LocalDateTime.of(2026, 6, 22, 9, 30);
        TelemetryLatestFlowReadingRecord latestReading = new TelemetryLatestFlowReadingRecord(
                99L,
                10L,
                1L,
                "corr-1",
                new BigDecimal("100"),
                new BigDecimal("100"),
                "http://example.com/img.jpg",
                readingDate,
                readingAt,
                "BFM"
        );

        when(glificOperatorContextService.resolveOperatorWithSchema("919999999999"))
                .thenReturn(new TelemetryOperatorWithSchema(schemaName, operator));
        when(telemetryTenantRepository.findLatestFlowReadingByOperator(schemaName, 1L))
                .thenReturn(Optional.of(latestReading));

        CreateReadingResponse resp = service.updateConfirmedReading(null, "919999999999", new BigDecimal("123"));

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("corr-1", resp.getCorrelationId());
        assertEquals(new BigDecimal("123"), resp.getMeterReading());
        verify(telemetryTenantRepository).updateConfirmedReading(schemaName, 99L, new BigDecimal("123"), 1L,
                RolloverResolutionService.SOURCE_MANUAL);
        verify(telemetryEventPublisher).publishMeterReadingRecorded(
                22,
                10L,
                1L,
                new BigDecimal("100"),
                new BigDecimal("123"),
                null,
                "http://example.com/img.jpg",
                readingAt,
                ReadingChannel.BFM.getCode(),
                readingDate,
                1,
                0
        );
    }

    @Test
    void updateConfirmedReadingWithCorrelationIdUpdatesReadingAndPublishesEvent() {
        String schemaName = "tenant_test";
        TenantContext.setSchema(schemaName);
        TelemetryOperator operator = new TelemetryOperator(1L, 22, "op", "op@example.com", "919999999999", null);
        LocalDate readingDate = LocalDate.of(2026, 6, 22);
        LocalDateTime readingAt = LocalDateTime.of(2026, 6, 22, 9, 30);
        TelemetryLatestFlowReadingRecord reading = new TelemetryLatestFlowReadingRecord(
                99L,
                10L,
                1L,
                "corr-1",
                new BigDecimal("100"),
                new BigDecimal("100"),
                "http://example.com/img.jpg",
                readingDate,
                readingAt,
                "BFM"
        );

        when(telemetryTenantRepository.findFlowReadingDetailsByCorrelationId(schemaName, "corr-1"))
                .thenReturn(Optional.of(reading));
        when(telemetryTenantRepository.findOperatorById(schemaName, 1L)).thenReturn(Optional.of(operator));

        CreateReadingResponse resp = service.updateConfirmedReading("corr-1", "919999999999", new BigDecimal("123"));

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("corr-1", resp.getCorrelationId());
        assertEquals(new BigDecimal("123"), resp.getMeterReading());
        verify(telemetryTenantRepository).updateConfirmedReading(schemaName, 99L, new BigDecimal("123"), 1L,
                RolloverResolutionService.SOURCE_MANUAL);
        verify(telemetryEventPublisher).publishMeterReadingRecorded(
                22,
                10L,
                1L,
                new BigDecimal("100"),
                new BigDecimal("123"),
                null,
                "http://example.com/img.jpg",
                readingAt,
                ReadingChannel.BFM.getCode(),
                readingDate,
                1,
                0
        );
    }
}
