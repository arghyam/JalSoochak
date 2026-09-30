package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReadingBackfillServiceTest {

    private static final String SCHEMA = "tenant_as";
    private static final int TENANT_ID = 22;
    private static final LocalDate FROM = LocalDate.of(2026, 9, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);
    private static final Set<ReadingChannel> ELM_AND_PDU = EnumSet.of(ReadingChannel.ELM, ReadingChannel.PDU);

    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;

    @Mock
    private ReadingRepublisher readingRepublisher;

    @InjectMocks
    private ReadingBackfillService readingBackfillService;

    private void tenantSchema() {
        when(telemetryTenantRepository.findSchemaNameByTenantId(TENANT_ID)).thenReturn(Optional.of(SCHEMA));
    }

    @Test
    void republishesEveryReadingInOrderAndCountsTheWithheldOnes() {
        tenantSchema();
        when(telemetryTenantRepository.findFlowReadingIdsForRepublish(SCHEMA, FROM, TO, null, ELM_AND_PDU))
                .thenReturn(List.of(5L, 3L, 9L));
        when(readingRepublisher.republish(SCHEMA, TENANT_ID, 5L)).thenReturn(true);
        when(readingRepublisher.republish(SCHEMA, TENANT_ID, 3L)).thenReturn(false);
        when(readingRepublisher.republish(SCHEMA, TENANT_ID, 9L)).thenReturn(true);

        ReadingBackfillService.Outcome outcome =
                readingBackfillService.republish(TENANT_ID, FROM, TO, null, null, null);

        assertEquals(new ReadingBackfillService.Outcome(2, 1), outcome);
        InOrder order = inOrder(readingRepublisher);
        order.verify(readingRepublisher).republish(SCHEMA, TENANT_ID, 5L);
        order.verify(readingRepublisher).republish(SCHEMA, TENANT_ID, 3L);
        order.verify(readingRepublisher).republish(SCHEMA, TENANT_ID, 9L);
    }

    @Test
    void coversEverySchemeWhenNoSchemeIdIsGiven() {
        tenantSchema();
        when(telemetryTenantRepository.findFlowReadingIdsForRepublish(SCHEMA, FROM, TO, null, ELM_AND_PDU))
                .thenReturn(List.of());

        assertEquals(new ReadingBackfillService.Outcome(0, 0),
                readingBackfillService.republish(TENANT_ID, FROM, TO, " ", "", null));

        verify(telemetryTenantRepository, never()).findSchemeIdByStateSchemeId(any(), any());
        verify(telemetryTenantRepository, never()).findSchemeIdByCentreSchemeId(any(), any());
        verifyNoInteractions(readingRepublisher);
    }

    @Test
    void narrowsToTheOneChannelAsked() {
        tenantSchema();
        when(telemetryTenantRepository.findFlowReadingIdsForRepublish(
                SCHEMA, FROM, TO, null, EnumSet.of(ReadingChannel.PDU)))
                .thenReturn(List.of());

        readingBackfillService.republish(TENANT_ID, FROM, TO, null, null, ReadingChannel.PDU);

        verify(telemetryTenantRepository).findFlowReadingIdsForRepublish(
                SCHEMA, FROM, TO, null, EnumSet.of(ReadingChannel.PDU));
    }

    @Test
    void looksTheStateSchemeIdUpFirst() {
        tenantSchema();
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId(SCHEMA, "S-1")).thenReturn(Optional.of(7L));
        when(telemetryTenantRepository.findFlowReadingIdsForRepublish(SCHEMA, FROM, TO, 7L, ELM_AND_PDU))
                .thenReturn(List.of());

        readingBackfillService.republish(TENANT_ID, FROM, TO, "S-1", "C-1", null);

        verify(telemetryTenantRepository, never()).findSchemeIdByCentreSchemeId(any(), any());
        verify(telemetryTenantRepository).findFlowReadingIdsForRepublish(SCHEMA, FROM, TO, 7L, ELM_AND_PDU);
    }

    @Test
    void fallsBackToTheCentreSchemeId() {
        tenantSchema();
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId(SCHEMA, "S-1")).thenReturn(Optional.empty());
        when(telemetryTenantRepository.findSchemeIdByCentreSchemeId(SCHEMA, "C-1")).thenReturn(Optional.of(8L));
        when(telemetryTenantRepository.findFlowReadingIdsForRepublish(SCHEMA, FROM, TO, 8L, ELM_AND_PDU))
                .thenReturn(List.of());

        readingBackfillService.republish(TENANT_ID, FROM, TO, "S-1", "C-1", null);

        verify(telemetryTenantRepository).findFlowReadingIdsForRepublish(SCHEMA, FROM, TO, 8L, ELM_AND_PDU);
    }

    @Test
    void refusesASchemeIdThatMatchesNoScheme() {
        tenantSchema();
        when(telemetryTenantRepository.findSchemeIdByStateSchemeId(SCHEMA, "S-404")).thenReturn(Optional.empty());
        when(telemetryTenantRepository.findSchemeIdByCentreSchemeId(SCHEMA, null)).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> readingBackfillService.republish(TENANT_ID, FROM, TO, "S-404", null, null));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
        verify(telemetryTenantRepository, never()).findFlowReadingIdsForRepublish(any(), any(), any(), any(), any());
        verifyNoInteractions(readingRepublisher);
    }

    /** A BFM reading's quantity is not calculated from configuration, and old ones have no identity. */
    @Test
    void refusesABfmBackfill() {
        assertThrows(IllegalArgumentException.class,
                () -> readingBackfillService.republish(TENANT_ID, FROM, TO, null, null, ReadingChannel.BFM));

        verifyNoInteractions(telemetryTenantRepository, readingRepublisher);
    }

    @Test
    void failsWhenTheTenantHasNoSchema() {
        when(telemetryTenantRepository.findSchemaNameByTenantId(TENANT_ID)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> readingBackfillService.republish(TENANT_ID, FROM, TO, null, null, null));

        verify(telemetryTenantRepository, never()).findSchemeIdByStateSchemeId(anyString(), any());
        verifyNoInteractions(readingRepublisher);
    }
}
