package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdatedPreviousReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryCompletedFlowReading;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlificMeterWorkflowServiceUpdatePreviousReadingTest {

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
    private TelemetryEventPublisher telemetryEventPublisher;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private GlificMeterWorkflowService service;

    @Test
    void updatePreviousReadingUpdatesWhenNoThresholdsConfigured() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");

        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));

        LocalDate today = LocalDate.now();
        LocalDate targetDate = today.minusDays(2);
        LocalDate previousDate = today.minusDays(3);
        LocalDate nextDate = today.minusDays(1);
        when(telemetryTenantRepository.findLatestCompletedFlowReadingBeforeDate("tenant_test", 10L, 1L, today))
                .thenReturn(Optional.of(new TelemetryCompletedFlowReading(22L, "corr-2", 1L, targetDate, new BigDecimal("1100"))));
        when(telemetryTenantRepository.findLatestCompletedFlowReadingBeforeDate("tenant_test", 10L, 1L, targetDate))
                .thenReturn(Optional.of(new TelemetryCompletedFlowReading(11L, "corr-3", 1L, previousDate, new BigDecimal("1000"))));
        when(telemetryTenantRepository.findEarliestCompletedFlowReadingAfterDate("tenant_test", 10L, 1L, targetDate))
                .thenReturn(Optional.of(new TelemetryCompletedFlowReading(33L, "corr-1", 1L, nextDate, new BigDecimal("1200"))));

        CreateReadingResponse resp = service.updatePreviousReadingMessage(UpdatedPreviousReadingRequest.builder()
                .contactId("919999999999")
                .reading("900")
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("CONFIRMED", resp.getQualityStatus());
        verify(telemetryTenantRepository).updateReadingValues("tenant_test", 22L, new BigDecimal("900"), 1L);
        verify(telemetryTenantRepository).upsertAnalyticsWaterQuantity(
                1,
                10L,
                1L,
                targetDate,
                new BigDecimal("-100"),
                1
        );
        verify(telemetryTenantRepository).upsertAnalyticsWaterQuantity(
                1,
                10L,
                1L,
                nextDate,
                new BigDecimal("300"),
                1
        );
    }

    @Test
    void updatePreviousReadingRejectsWhenImpliedQuantityOutsideConfiguredThresholds() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");

        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));

        LocalDate today = LocalDate.now();
        LocalDate targetDate = today.minusDays(2);
        LocalDate previousDate = today.minusDays(3);
        LocalDate nextDate = today.minusDays(1);
        when(telemetryTenantRepository.findLatestCompletedFlowReadingBeforeDate("tenant_test", 10L, 1L, today))
                .thenReturn(Optional.of(new TelemetryCompletedFlowReading(22L, "corr-2", 1L, targetDate, new BigDecimal("1100"))));
        when(telemetryTenantRepository.findLatestCompletedFlowReadingBeforeDate("tenant_test", 10L, 1L, targetDate))
                .thenReturn(Optional.of(new TelemetryCompletedFlowReading(11L, "corr-3", 1L, previousDate, new BigDecimal("1000"))));
        when(telemetryTenantRepository.findEarliestCompletedFlowReadingAfterDate("tenant_test", 10L, 1L, targetDate))
                .thenReturn(Optional.of(new TelemetryCompletedFlowReading(33L, "corr-1", 1L, nextDate, new BigDecimal("1200"))));

        // Config: norm=100; under=20% => min=80; over=30% => max=130
        when(tenantConfigRepository.findConfigValue(1, "WATER_NORM"))
                .thenReturn(Optional.of("{\"value\":\"100\"}"));
        when(tenantConfigRepository.findConfigValue(1, "TENANT_WATER_QUANTITY_SUPPLY_THRESHOLD"))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findConfigValue(1, "WATER_QUANTITY_SUPPLY_THRESHOLD"))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findConfigValue(0, "WATER_QUANTITY_SUPPLY_THRESHOLD"))
                .thenReturn(Optional.of("{\"undersupplyThresholdPercent\":20.0,\"oversupplyThresholdPercent\":30.0}"));

        // Update target day (today-2) to 1050 => qtyTarget=50, below min(80) => reject
        CreateReadingResponse resp = service.updatePreviousReadingMessage(UpdatedPreviousReadingRequest.builder()
                .contactId("919999999999")
                .reading("1050")
                .build());

        assertNotNull(resp);
        assertEquals(false, resp.isSuccess());
        assertEquals("REJECTED", resp.getQualityStatus());
        verify(telemetryTenantRepository, never()).updateReadingValues(anyString(), anyLong(), any(), anyLong());
        verify(telemetryTenantRepository, never()).upsertAnalyticsWaterQuantity(any(), anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void updatePreviousReadingUpdatesWhenWithinBoundsAndThresholds() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );

        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");

        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));

        LocalDate today = LocalDate.now();
        LocalDate targetDate = today.minusDays(2);
        LocalDate previousDate = today.minusDays(3);
        LocalDate nextDate = today.minusDays(1);
        when(telemetryTenantRepository.findLatestCompletedFlowReadingBeforeDate("tenant_test", 10L, 1L, today))
                .thenReturn(Optional.of(new TelemetryCompletedFlowReading(22L, "corr-2", 1L, targetDate, new BigDecimal("1100"))));
        when(telemetryTenantRepository.findLatestCompletedFlowReadingBeforeDate("tenant_test", 10L, 1L, targetDate))
                .thenReturn(Optional.of(new TelemetryCompletedFlowReading(11L, "corr-3", 1L, previousDate, new BigDecimal("1000"))));
        when(telemetryTenantRepository.findEarliestCompletedFlowReadingAfterDate("tenant_test", 10L, 1L, targetDate))
                .thenReturn(Optional.of(new TelemetryCompletedFlowReading(33L, "corr-1", 1L, nextDate, new BigDecimal("1200"))));

        when(tenantConfigRepository.findConfigValue(1, "WATER_NORM"))
                .thenReturn(Optional.of("{\"value\":\"100\"}"));
        when(tenantConfigRepository.findConfigValue(1, "TENANT_WATER_QUANTITY_SUPPLY_THRESHOLD"))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findConfigValue(1, "WATER_QUANTITY_SUPPLY_THRESHOLD"))
                .thenReturn(Optional.empty());
        when(tenantConfigRepository.findConfigValue(0, "WATER_QUANTITY_SUPPLY_THRESHOLD"))
                .thenReturn(Optional.of("{\"undersupplyThresholdPercent\":20.0,\"oversupplyThresholdPercent\":30.0}"));

        // Update target day (today-2) to 1110 => qtyTarget=110 (OK), qtyNext=90 (OK)
        CreateReadingResponse resp = service.updatePreviousReadingMessage(UpdatedPreviousReadingRequest.builder()
                .contactId("919999999999")
                .reading("1110")
                .build());

        assertNotNull(resp);
        assertEquals(true, resp.isSuccess());
        assertEquals("CONFIRMED", resp.getQualityStatus());
        assertEquals(new BigDecimal("1110"), resp.getMeterReading());
        assertEquals("corr-2", resp.getCorrelationId());
        verify(telemetryTenantRepository).updateReadingValues("tenant_test", 22L, new BigDecimal("1110"), 1L);
        verify(telemetryTenantRepository).upsertAnalyticsWaterQuantity(
                1,
                10L,
                1L,
                targetDate,
                new BigDecimal("110"),
                1
        );
        verify(telemetryTenantRepository).upsertAnalyticsWaterQuantity(
                1,
                10L,
                1L,
                nextDate,
                new BigDecimal("90"),
                1
        );
    }
}
