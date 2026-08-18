package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannelResolver;
import org.arghyam.jalsoochak.telemetry.config.TenantContext;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryLatestFlowReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PHONE-OPTIONAL: a correction only needs one identifier. These cover the correlationId-only path,
 * where there is no operator to derive the tenant schema from and it has to come from the API key.
 */
@ExtendWith(MockitoExtension.class)
class BfmReadingServicePhoneOptionalUpdateTest {

    private static final int API_KEY_TENANT_ID = 22;
    private static final String API_KEY_SCHEMA = "tenant_as";
    private static final LocalDate READING_DATE = LocalDate.of(2026, 6, 22);
    private static final LocalDateTime READING_AT = LocalDateTime.of(2026, 6, 22, 9, 30);

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

    private TelemetryLatestFlowReadingRecord reading(Long createdBy) {
        return new TelemetryLatestFlowReadingRecord(
                99L,
                10L,
                createdBy,
                "corr-1",
                new BigDecimal("100"),
                new BigDecimal("100"),
                "http://example.com/img.jpg",
                READING_DATE,
                READING_AT,
                "BFM"
        );
    }

    @Test
    void updateWithCorrelationIdAndNoPhoneResolvesSchemaFromApiKeyTenant() {
        TelemetryOperator operator = new TelemetryOperator(1L, API_KEY_TENANT_ID, "op", "op@example.com", "919999999999", null);
        when(telemetryTenantRepository.findSchemaNameByTenantId(API_KEY_TENANT_ID))
                .thenReturn(Optional.of(API_KEY_SCHEMA));
        when(telemetryTenantRepository.findFlowReadingDetailsByCorrelationId(API_KEY_SCHEMA, "corr-1"))
                .thenReturn(Optional.of(reading(1L)));
        when(telemetryTenantRepository.findOperatorById(API_KEY_SCHEMA, 1L)).thenReturn(Optional.of(operator));

        CreateReadingResponse response = service.updateConfirmedReading(
                "corr-1", null, new BigDecimal("123"), API_KEY_TENANT_ID);

        assertNotNull(response);
        assertEquals(true, response.isSuccess());
        assertEquals("corr-1", response.getCorrelationId());
        verify(telemetryTenantRepository).updateConfirmedReading(
                API_KEY_SCHEMA, 99L, new BigDecimal("123"), 1L, RolloverResolutionService.SOURCE_MANUAL);
        verify(telemetryEventPublisher).publishMeterReadingRecorded(
                API_KEY_TENANT_ID,
                10L,
                1L,
                new BigDecimal("100"),
                new BigDecimal("123"),
                null,
                "http://example.com/img.jpg",
                READING_AT,
                ReadingChannel.BFM.getCode(),
                READING_DATE,
                1,
                0
        );
    }

    /**
     * X-Tenant-Code is an unauthenticated header, so it must not let a caller holding one tenant's API
     * key reach another tenant's schema.
     */
    @Test
    void updateWithCorrelationIdPrefersApiKeyTenantOverTenantCodeHeader() {
        TenantContext.setSchema("tenant_mp");
        TelemetryOperator operator = new TelemetryOperator(1L, API_KEY_TENANT_ID, "op", "op@example.com", "919999999999", null);
        when(telemetryTenantRepository.findSchemaNameByTenantId(API_KEY_TENANT_ID))
                .thenReturn(Optional.of(API_KEY_SCHEMA));
        when(telemetryTenantRepository.findFlowReadingDetailsByCorrelationId(API_KEY_SCHEMA, "corr-1"))
                .thenReturn(Optional.of(reading(1L)));
        when(telemetryTenantRepository.findOperatorById(API_KEY_SCHEMA, 1L)).thenReturn(Optional.of(operator));

        service.updateConfirmedReading("corr-1", null, new BigDecimal("123"), API_KEY_TENANT_ID);

        verify(telemetryTenantRepository).updateConfirmedReading(
                API_KEY_SCHEMA, 99L, new BigDecimal("123"), 1L, RolloverResolutionService.SOURCE_MANUAL);
    }

    /** Callers with no authenticated tenant (the correlationId-only overload) keep the header path. */
    @Test
    void updateWithoutApiKeyTenantFallsBackToTenantContext() {
        TenantContext.setSchema("tenant_test");
        TelemetryOperator operator = new TelemetryOperator(1L, 7, "op", "op@example.com", "919999999999", null);
        when(telemetryTenantRepository.findFlowReadingDetailsByCorrelationId("tenant_test", "corr-1"))
                .thenReturn(Optional.of(reading(1L)));
        when(telemetryTenantRepository.findOperatorById("tenant_test", 1L)).thenReturn(Optional.of(operator));

        service.updateConfirmedReading("corr-1", null, new BigDecimal("123"), null);

        verify(telemetryTenantRepository).updateConfirmedReading(
                "tenant_test", 99L, new BigDecimal("123"), 1L, RolloverResolutionService.SOURCE_MANUAL);
    }

    @Test
    void updateFallsBackToTenantContextWhenApiKeyTenantHasNoSchema() {
        TenantContext.setSchema("tenant_test");
        TelemetryOperator operator = new TelemetryOperator(1L, 7, "op", "op@example.com", "919999999999", null);
        when(telemetryTenantRepository.findSchemaNameByTenantId(404)).thenReturn(Optional.empty());
        when(telemetryTenantRepository.findFlowReadingDetailsByCorrelationId("tenant_test", "corr-1"))
                .thenReturn(Optional.of(reading(1L)));
        when(telemetryTenantRepository.findOperatorById("tenant_test", 1L)).thenReturn(Optional.of(operator));

        service.updateConfirmedReading("corr-1", null, new BigDecimal("123"), 404);

        verify(telemetryTenantRepository).updateConfirmedReading(
                "tenant_test", 99L, new BigDecimal("123"), 1L, RolloverResolutionService.SOURCE_MANUAL);
    }

    /**
     * analytics-service skips the attendance and water-quantity facts on a null tenantId, so an
     * unresolvable creator must not strip the tenant off the published correction.
     */
    @Test
    void updatePublishesApiKeyTenantWhenCreatorOperatorCannotBeResolved() {
        when(telemetryTenantRepository.findSchemaNameByTenantId(API_KEY_TENANT_ID))
                .thenReturn(Optional.of(API_KEY_SCHEMA));
        when(telemetryTenantRepository.findFlowReadingDetailsByCorrelationId(API_KEY_SCHEMA, "corr-1"))
                .thenReturn(Optional.of(reading(1L)));
        when(telemetryTenantRepository.findOperatorById(API_KEY_SCHEMA, 1L)).thenReturn(Optional.empty());

        service.updateConfirmedReading("corr-1", null, new BigDecimal("123"), API_KEY_TENANT_ID);

        verify(telemetryEventPublisher).publishMeterReadingRecorded(
                API_KEY_TENANT_ID,
                10L,
                1L,
                new BigDecimal("100"),
                new BigDecimal("123"),
                null,
                "http://example.com/img.jpg",
                READING_AT,
                ReadingChannel.BFM.getCode(),
                READING_DATE,
                1,
                0
        );
    }

    @Test
    void updateRejectsWhenNeitherCorrelationIdNorPhoneProvided() {
        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.updateConfirmedReading(null, null, new BigDecimal("123"), API_KEY_TENANT_ID)
        );

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        assertEquals("Either correlationId or phoneNumber must be provided", thrown.getReason());
    }

    @Test
    void updateRejectsWhenBlankCorrelationIdAndBlankPhoneProvided() {
        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.updateConfirmedReading("  ", "  ", new BigDecimal("123"), API_KEY_TENANT_ID)
        );

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        assertEquals("Either correlationId or phoneNumber must be provided", thrown.getReason());
    }

    @Test
    void updateRejectsWhenTenantCannotBeResolvedAtAll() {
        when(telemetryTenantRepository.findSchemaNameByTenantId(404)).thenReturn(Optional.empty());

        ResponseStatusException thrown = assertThrows(
                ResponseStatusException.class,
                () -> service.updateConfirmedReading("corr-1", null, new BigDecimal("123"), 404)
        );

        assertEquals(HttpStatus.BAD_REQUEST, thrown.getStatusCode());
        assertEquals("Tenant could not be resolved", thrown.getReason());
    }
}
