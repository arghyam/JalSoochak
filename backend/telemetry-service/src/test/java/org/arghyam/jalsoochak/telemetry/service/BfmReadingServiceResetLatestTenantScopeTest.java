package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannelResolver;
import org.arghyam.jalsoochak.telemetry.config.TenantContext;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The reset is destructive and cross-tenant by construction: operators are looked up by phone across
 * every tenant schema, so authenticating the caller is not enough on its own — the tenant the key
 * belongs to has to bound which operator the caller may reach.
 */
@ExtendWith(MockitoExtension.class)
class BfmReadingServiceResetLatestTenantScopeTest {

    private static final int CALLER_TENANT_ID = 22;
    private static final int OTHER_TENANT_ID = 77;
    private static final String CALLER_SCHEMA = "tenant_as";
    private static final String OTHER_SCHEMA = "tenant_mp";
    private static final String PHONE = "919999999999";
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

    private TelemetryLatestFlowReadingRecord reading() {
        return new TelemetryLatestFlowReadingRecord(
                99L,
                10L,
                1L,
                "corr-1",
                new BigDecimal("1500"),
                new BigDecimal("1450"),
                "http://example.com/img.jpg",
                READING_DATE,
                READING_AT,
                "BFM"
        );
    }

    private TelemetryOperator operator(Integer tenantId) {
        return new TelemetryOperator(1L, tenantId, "op", "op@example.com", PHONE, null);
    }

    @Test
    void resetsTheReadingWhenTheOperatorBelongsToTheAuthenticatedTenant() {
        when(glificOperatorContextService.resolveOperatorWithSchema(PHONE, CALLER_TENANT_ID))
                .thenReturn(new TelemetryOperatorWithSchema(CALLER_SCHEMA, operator(CALLER_TENANT_ID)));
        when(telemetryTenantRepository.findLatestFlowReadingByOperator(CALLER_SCHEMA, 1L))
                .thenReturn(Optional.of(reading()));

        CreateReadingResponse response = service.resetLatestConfirmedReadingByPhone(PHONE, CALLER_TENANT_ID);

        assertTrue(response.isSuccess());
        assertEquals(BigDecimal.ZERO, response.getMeterReading());
        verify(telemetryTenantRepository).updateConfirmedReading(CALLER_SCHEMA, 99L, BigDecimal.ZERO, 1L);
    }

    @Test
    void returnsTheDestroyedValueSoTheResetCanBeAudited() {
        when(glificOperatorContextService.resolveOperatorWithSchema(PHONE, CALLER_TENANT_ID))
                .thenReturn(new TelemetryOperatorWithSchema(CALLER_SCHEMA, operator(CALLER_TENANT_ID)));
        when(telemetryTenantRepository.findLatestFlowReadingByOperator(CALLER_SCHEMA, 1L))
                .thenReturn(Optional.of(reading()));

        CreateReadingResponse response = service.resetLatestConfirmedReadingByPhone(PHONE, CALLER_TENANT_ID);

        // The reset overwrites the only copy of the confirmed reading; without the prior value on the
        // response there is nothing to write to the audit log and nothing to restore from.
        assertEquals(new BigDecimal("1450"), response.getLastConfirmedReading());
    }

    @Test
    void refusesToResetAnOperatorBelongingToAnotherTenant() {
        // The phone lookup falls back to other tenants when the key's tenant has no match, so a valid
        // tenant-A key must not be able to reach a tenant-B operator's reading.
        when(glificOperatorContextService.resolveOperatorWithSchema(PHONE, CALLER_TENANT_ID))
                .thenReturn(new TelemetryOperatorWithSchema(OTHER_SCHEMA, operator(OTHER_TENANT_ID)));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.resetLatestConfirmedReadingByPhone(PHONE, CALLER_TENANT_ID));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(telemetryTenantRepository, never()).updateConfirmedReading(anyString(), anyLong(), any(), anyLong());
        verifyNoInteractions(telemetryEventPublisher);
    }

    @Test
    void crossTenantRefusalIsIndistinguishableFromAnUnknownContact() {
        // Distinct messages would turn the endpoint into an oracle for "does this phone exist in some
        // other tenant", so both misses answer identically.
        when(glificOperatorContextService.resolveOperatorWithSchema(PHONE, CALLER_TENANT_ID))
                .thenReturn(new TelemetryOperatorWithSchema(OTHER_SCHEMA, operator(OTHER_TENANT_ID)));
        ResponseStatusException crossTenant = assertThrows(ResponseStatusException.class,
                () -> service.resetLatestConfirmedReadingByPhone(PHONE, CALLER_TENANT_ID));

        when(glificOperatorContextService.resolveOperatorWithSchema("918888888888", CALLER_TENANT_ID))
                .thenThrow(new IllegalStateException("No operator found for contactId 918888888888"));
        ResponseStatusException unknown = assertThrows(ResponseStatusException.class,
                () -> service.resetLatestConfirmedReadingByPhone("918888888888", CALLER_TENANT_ID));

        assertEquals(crossTenant.getStatusCode(), unknown.getStatusCode());
        assertEquals(crossTenant.getReason(), unknown.getReason());
    }

    @Test
    void unknownContactIsNotFoundRatherThanAServerError() {
        when(glificOperatorContextService.resolveOperatorWithSchema(PHONE, CALLER_TENANT_ID))
                .thenThrow(new IllegalStateException("No operator found for contactId"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.resetLatestConfirmedReadingByPhone(PHONE, CALLER_TENANT_ID));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
    }

    @Test
    void refusesToResetWithoutAnAuthenticatedTenant() {
        // No caller should reach the destructive path unscoped; an absent tenant is a 401, not a
        // silent cross-tenant search.
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.resetLatestConfirmedReadingByPhone(PHONE, null));

        assertEquals(HttpStatus.UNAUTHORIZED, ex.getStatusCode());
        verifyNoInteractions(glificOperatorContextService);
        verify(telemetryTenantRepository, never()).updateConfirmedReading(anyString(), anyLong(), any(), anyLong());
    }

    @Test
    void blankContactIdIsRejectedBeforeAnyLookup() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.resetLatestConfirmedReadingByPhone("  ", CALLER_TENANT_ID));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verifyNoInteractions(glificOperatorContextService);
    }

    @Test
    void missingReadingIsNotFound() {
        when(glificOperatorContextService.resolveOperatorWithSchema(PHONE, CALLER_TENANT_ID))
                .thenReturn(new TelemetryOperatorWithSchema(CALLER_SCHEMA, operator(CALLER_TENANT_ID)));
        when(telemetryTenantRepository.findLatestFlowReadingByOperator(CALLER_SCHEMA, 1L))
                .thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.resetLatestConfirmedReadingByPhone(PHONE, CALLER_TENANT_ID));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verifyNoInteractions(telemetryEventPublisher);
    }
}
