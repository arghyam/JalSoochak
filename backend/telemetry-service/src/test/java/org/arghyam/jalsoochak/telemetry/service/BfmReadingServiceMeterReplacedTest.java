package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannelResolver;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BfmReadingServiceMeterReplacedTest {

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

    @InjectMocks
    private BfmReadingService service;

    @BeforeEach
    void stubChannelResolver() {
        // resolve() is non-null in production; stub leniently as some tests reject before reaching it.
        lenient().when(readingChannelResolver.resolve(any(), any())).thenReturn(ReadingChannel.BFM);
    }

    @Test
    void createReadingAcceptsWhenLowerThanPreviousAndMeterNotReplaced() {
        String schemaName = "tenant_test";
        TelemetryOperator operator = new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null);

        CreateReadingRequest request = CreateReadingRequest.builder()
                .schemeId(10L)
                .operatorId(1L)
                .readingValue(new BigDecimal("100"))
                .build();

        when(telemetryTenantRepository.existsSchemeById(schemaName, 10L)).thenReturn(true);
        when(telemetryTenantRepository.findOperatorById(schemaName, 1L)).thenReturn(Optional.of(operator));
        when(telemetryTenantRepository.isOperatorMappedToScheme(schemaName, 1L, 10L)).thenReturn(true);
        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot(schemaName, 10L, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(new BigDecimal("200"), LocalDateTime.now().minusDays(1))));
        when(telemetryTenantRepository.findLatestPlaceholderFlowReadingIdForDate(schemaName, 10L, 1L, LocalDate.now()))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.createFlowReading(
                anyString(),
                anyLong(),
                anyLong(),
                any(LocalDateTime.class),
                any(BigDecimal.class),
                any(BigDecimal.class),
                anyString(),
                any(),
                any(),
                any()
        )).thenReturn(99L);
        CreateReadingResponse resp = service.createReading(request, schemaName, operator, "919999999999", false);

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("CONFIRMED", resp.getQualityStatus());
        assertEquals(new BigDecimal("100"), resp.getMeterReading());
        assertTrue(resp.getMessage().contains("Reading captured successfully"));

        verify(telemetryTenantRepository).createFlowReading(
                anyString(),
                anyLong(),
                anyLong(),
                any(LocalDateTime.class),
                any(BigDecimal.class),
                any(BigDecimal.class),
                anyString(),
                any(),
                any(),
                any()
        );
        verify(telemetryTenantRepository, never()).createTenantAnomalyRecord(
                anyString(),
                anyLong(),
                anyLong(),
                org.mockito.ArgumentMatchers.eq(AnomalyConstants.TYPE_READING_LESS_THAN_PREVIOUS),
                anyString(),
                org.mockito.ArgumentMatchers.eq(AnomalyConstants.STATUS_OPEN)
        );
        verify(telemetryEventPublisher, never()).publishAnomalyRecorded(
                org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(AnomalyConstants.TYPE_READING_LESS_THAN_PREVIOUS),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(10L),
                any(),
                any(),
                any(),
                org.mockito.ArgumentMatchers.eq(0),
                any(),
                any(),
                org.mockito.ArgumentMatchers.eq(0),
                anyString(),
                org.mockito.ArgumentMatchers.eq(AnomalyConstants.STATUS_OPEN),
                anyString()
        );
    }

    @Test
    void createReadingAcceptsWhenBelowWaterThreshold() {
        String schemaName = "tenant_test";
        TelemetryOperator operator = new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null);

        CreateReadingRequest request = CreateReadingRequest.builder()
                .schemeId(10L)
                .operatorId(1L)
                .readingValue(new BigDecimal("850"))
                .build();

        when(telemetryTenantRepository.existsSchemeById(schemaName, 10L)).thenReturn(true);
        when(telemetryTenantRepository.findOperatorById(schemaName, 1L)).thenReturn(Optional.of(operator));
        when(telemetryTenantRepository.isOperatorMappedToScheme(schemaName, 1L, 10L)).thenReturn(true);

        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot(schemaName, 10L, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(new BigDecimal("800"), LocalDateTime.now().minusDays(1))));

        when(tenantConfigRepository.findConfigValue(1, "TENANT_WATER_QUANTITY_SUPPLY_THRESHOLD"))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findConfigValue(1, "WATER_QUANTITY_SUPPLY_THRESHOLD"))
                .thenReturn(Optional.of("{\"undersupplyThresholdPercent\":10,\"oversupplyThresholdPercent\":20}"));
        when(tenantConfigRepository.findConfigValue(1, "WATER_NORM"))
                .thenReturn(Optional.of("{\"value\":\"1000\"}"));
        when(telemetryTenantRepository.findLatestPlaceholderFlowReadingIdForDate(schemaName, 10L, 1L, LocalDate.now()))
                .thenReturn(Optional.empty());
        when(telemetryTenantRepository.createFlowReading(
                anyString(),
                anyLong(),
                anyLong(),
                any(LocalDateTime.class),
                any(BigDecimal.class),
                any(BigDecimal.class),
                anyString(),
                any(),
                any(),
                any()
        )).thenReturn(101L);

        CreateReadingResponse resp = service.createReading(request, schemaName, operator, "919999999999", false);

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("CONFIRMED", resp.getQualityStatus());
        assertTrue(resp.getMessage().contains("Reading captured successfully"));

        verify(telemetryTenantRepository).createFlowReading(
                anyString(),
                anyLong(),
                anyLong(),
                any(),
                any(),
                any(),
                anyString(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void createReadingAcceptsLowerReadingWhenMeterReplacedAndRecordsReason() {
        String schemaName = "tenant_test";
        TelemetryOperator operator = new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null);

        LocalDateTime readingAt = LocalDateTime.now();
        CreateReadingRequest request = CreateReadingRequest.builder()
                .schemeId(10L)
                .operatorId(1L)
                .readingValue(new BigDecimal("100"))
                .meterChangeReason("METER_REPLACED")
                .readingTime(readingAt)
                .build();

        when(telemetryTenantRepository.existsSchemeById(schemaName, 10L)).thenReturn(true);
        when(telemetryTenantRepository.findOperatorById(schemaName, 1L)).thenReturn(Optional.of(operator));
        when(telemetryTenantRepository.isOperatorMappedToScheme(schemaName, 1L, 10L)).thenReturn(true);

        when(telemetryTenantRepository.findLatestConfirmedReadingSnapshot(schemaName, 10L, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(new BigDecimal("200"), LocalDateTime.now().minusDays(1))));

        when(telemetryTenantRepository.findLatestPlaceholderFlowReadingIdForDate(schemaName, 10L, 1L, LocalDate.from(readingAt)))
                .thenReturn(Optional.empty());

        when(telemetryTenantRepository.createFlowReading(
                anyString(),
                anyLong(),
                anyLong(),
                any(LocalDateTime.class),
                any(BigDecimal.class),
                any(BigDecimal.class),
                anyString(),
                any(),
                any(),
                any()
        )).thenReturn(99L);

        CreateReadingResponse resp = service.createReading(request, schemaName, operator, "919999999999", true);

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals(new BigDecimal("100"), resp.getMeterReading());
        assertEquals("CONFIRMED", resp.getQualityStatus());

        verify(telemetryTenantRepository).createFlowReading(
                anyString(),
                anyLong(),
                anyLong(),
                any(LocalDateTime.class),
                any(BigDecimal.class),
                any(BigDecimal.class),
                anyString(),
                any(),
                any(),
                any()
        );
    }
}
