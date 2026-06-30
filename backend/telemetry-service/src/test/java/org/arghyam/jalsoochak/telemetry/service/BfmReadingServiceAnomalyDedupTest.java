package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BfmReadingServiceAnomalyDedupTest {

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

    private BfmReadingService service;

    @BeforeEach
    void setUp() {
        service = new BfmReadingService(
                telemetryTenantRepository,
                flowVisionService,
                telemetryEventPublisher,
                tenantConfigRepository,
                new ObjectMapper(),
                glificOperatorContextService
        );
    }

    @Test
    void unreadableImageCreatesAnomalyForEachAttemptAndKeepsStableCorrelationId() {
        TelemetryOperator operator = new TelemetryOperator(11L, 7, "op", "op@example.com", "919999999999", null);
        when(telemetryTenantRepository.existsSchemeById("tenant_up", 100L)).thenReturn(true);
        when(telemetryTenantRepository.findOperatorById("tenant_up", 11L)).thenReturn(Optional.of(operator));
        when(telemetryTenantRepository.isOperatorMappedToScheme("tenant_up", 11L, 100L)).thenReturn(true);
        when(flowVisionService.extractReading("https://img.example.com/a.jpg")).thenReturn(null);

        CreateReadingRequest request = CreateReadingRequest.builder()
                .schemeId(100L)
                .operatorId(11L)
                .readingUrl("https://img.example.com/a.jpg")
                .build();

        service.createReading(request, "tenant_up", operator, "919999999999", false);
        service.createReading(request, "tenant_up", operator, "919999999999", false);

        ArgumentCaptor<String> correlationCaptor = ArgumentCaptor.forClass(String.class);
        verify(telemetryEventPublisher, times(2)).publishAnomalyRecorded(
                eq(7),
                eq(AnomalyConstants.TYPE_UNREADABLE_IMAGE),
                eq(11L),
                eq(100L),
                isNull(),
                isNull(),
                isNull(),
                anyInt(),
                isNull(),
                isNull(),
                eq(0),
                contains("Unreadable image"),
                eq(AnomalyConstants.STATUS_OPEN),
                correlationCaptor.capture()
        );
        assertEquals(2, correlationCaptor.getAllValues().size());
        assertFalse(correlationCaptor.getAllValues().get(0).isBlank());
        assertEquals(correlationCaptor.getAllValues().get(0), correlationCaptor.getAllValues().get(1));
        verify(telemetryTenantRepository, times(2)).createTenantAnomalyRecord(
                eq("tenant_up"),
                eq(11L),
                eq(100L),
                eq(AnomalyConstants.TYPE_UNREADABLE_IMAGE),
                contains("Unreadable image"),
                eq(AnomalyConstants.STATUS_OPEN)
        );
        verify(telemetryTenantRepository, never()).touchLatestAnomalyByTypeForToday(
                eq("tenant_up"),
                eq(11L),
                eq(100L),
                eq(AnomalyConstants.TYPE_UNREADABLE_IMAGE)
        );
    }

    @Test
    void duplicateImagePublishesStableCorrelationIdForSameImage() {
        TelemetryOperator operator = new TelemetryOperator(11L, 7, "op", "op@example.com", "919999999999", null);
        when(telemetryTenantRepository.existsSchemeById("tenant_up", 100L)).thenReturn(true);
        when(telemetryTenantRepository.findOperatorById("tenant_up", 11L)).thenReturn(Optional.of(operator));
        when(telemetryTenantRepository.isOperatorMappedToScheme("tenant_up", 11L, 100L)).thenReturn(true);
        when(flowVisionService.extractReading("https://img.example.com/dup.jpg")).thenReturn(
                FlowVisionResult.builder()
                        .adjustedReading(new BigDecimal("123"))
                        .qualityConfidence(new BigDecimal("0.95"))
                        .correlationId("ocr-correlation")
                        .build()
        );
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot("tenant_up", 100L, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(new BigDecimal("123"), LocalDateTime.now().minusDays(1))));
        when(tenantConfigRepository.findConfigValue(anyInt(), anyString())).thenReturn(Optional.empty());

        CreateReadingRequest request = CreateReadingRequest.builder()
                .schemeId(100L)
                .operatorId(11L)
                .readingUrl("https://img.example.com/dup.jpg")
                .build();

        service.createReading(request, "tenant_up", operator, "919999999999", false);
        service.createReading(request, "tenant_up", operator, "919999999999", false);

        ArgumentCaptor<String> correlationCaptor = ArgumentCaptor.forClass(String.class);
        verify(telemetryEventPublisher, times(2)).publishAnomalyRecorded(
                eq(7),
                eq(AnomalyConstants.TYPE_DUPLICATE_IMAGE_SUBMISSION),
                eq(11L),
                eq(100L),
                eq(new BigDecimal("123")),
                eq(new BigDecimal("0.95")),
                eq(new BigDecimal("123")),
                anyInt(),
                eq(new BigDecimal("123")),
                any(LocalDateTime.class),
                eq(0),
                contains("Duplicate image submission detected"),
                eq(AnomalyConstants.STATUS_OPEN),
                correlationCaptor.capture()
        );
        assertEquals(2, correlationCaptor.getAllValues().size());
        assertFalse(correlationCaptor.getAllValues().get(0).isBlank());
        verify(telemetryTenantRepository, times(2)).createTenantAnomalyRecord(
                eq("tenant_up"),
                eq(11L),
                eq(100L),
                eq(AnomalyConstants.TYPE_DUPLICATE_IMAGE_SUBMISSION),
                contains("Duplicate image submission detected"),
                eq(AnomalyConstants.STATUS_OPEN)
        );
        verify(telemetryTenantRepository, never()).touchLatestAnomalyByTypeForToday(
                eq("tenant_up"),
                eq(11L),
                eq(100L),
                eq(AnomalyConstants.TYPE_DUPLICATE_IMAGE_SUBMISSION)
        );
    }
}
