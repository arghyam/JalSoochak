package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannelResolver;
import org.arghyam.jalsoochak.telemetry.config.TenantContext;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.arghyam.jalsoochak.telemetry.dto.response.RolloverPosition;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.DailyConfirmedReading;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryLatestFlowReadingRecord;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration-style tests (Mockito, no Spring context) for the rollover resolver wired into
 * {@link BfmReadingService#createReading}. Uses a real, kill-switch-ON {@link RolloverResolutionService}
 * so the end-to-end resolve → persist → surface path is exercised.
 */
@ExtendWith(MockitoExtension.class)
class BfmReadingServiceRolloverTest {

    private static final String SCHEMA = "tenant_test";
    private static final String IMAGE_URL = "https://img.example.com/a.jpg";

    @Mock
    private TelemetryTenantRepository repo;
    @Mock
    private FlowVisionService flowVisionService;
    @Mock
    private TelemetryEventPublisher telemetryEventPublisher;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private GlificOperatorContextService glificOperatorContextService;
    @Mock
    private ReadingChannelResolver readingChannelResolver;

    private BfmReadingService service;
    private final TelemetryOperator operator =
            new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null);

    @BeforeEach
    void setUp() {
        service = new BfmReadingService(
                repo,
                flowVisionService,
                telemetryEventPublisher,
                tenantConfigRepository,
                new ObjectMapper(),
                glificOperatorContextService,
                null,
                readingChannelResolver,
                new RolloverResolutionService(true, new ObjectMapper()));
        lenient().when(readingChannelResolver.resolve(any(), any())).thenReturn(ReadingChannel.BFM);
    }

    @Test
    void rolloverWithFavourableHistoryResolvesConfirmedAndSurfacesResolvedValue() {
        // Model read "0250" (250, a +110 jump); the sibling hundreds digit gives "0150" (150, a normal
        // +10 against the ~10/day band anchored at 140) → resolver overrides.
        FlowVisionResult ocr = ocr("0250", "250",
                new RolloverPosition(2, 2, new BigDecimal("0.55"), 1, new BigDecimal("0.45")));

        stubCommon(ocr);
        when(repo.findRecentDailyConfirmedReadings(eq(SCHEMA), eq(10L), isNull(), anyInt()))
                .thenReturn(history(100, 110, 120, 130, 140));
        when(repo.createFlowReading(anyString(), anyLong(), anyLong(), any(LocalDateTime.class),
                any(BigDecimal.class), any(BigDecimal.class), anyString(), any(), any(), any()))
                .thenReturn(99L);

        CreateReadingResponse resp = service.createReading(request(), SCHEMA, operator, "919999999999", false);

        assertTrue(resp.isSuccess());
        // The value surfaced to the operator for confirmation is the resolved value, not the model pick.
        assertEquals(0, resp.getMeterReading().compareTo(new BigDecimal("150")));

        ArgumentCaptor<BigDecimal> extracted = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> confirmed = ArgumentCaptor.forClass(BigDecimal.class);
        verify(repo).createFlowReading(anyString(), anyLong(), anyLong(), any(LocalDateTime.class),
                extracted.capture(), confirmed.capture(), anyString(), any(), any(), any());
        assertEquals(0, extracted.getValue().compareTo(new BigDecimal("250")), "extracted_reading stays the model value");
        assertEquals(0, confirmed.getValue().compareTo(new BigDecimal("150")), "confirmed_reading seeded with the resolved value");

        verify(repo).applyConfirmedReadingSource(eq(SCHEMA), eq(99L),
                eq(RolloverResolutionService.SOURCE_ROLLOVER_RESOLVED), anyString());
    }

    @Test
    void noRolloverLeavesConfirmedEqualToExtractedAndSkipsProvenance() {
        FlowVisionResult ocr = FlowVisionResult.builder()
                .adjustedReading(new BigDecimal("250"))
                .rawMeterReading("0250")
                .redLastDigit(false)
                .hasRollover(false)
                .rolloverPositions(List.of())
                .requestId("req-1")
                .correlationId("corr-1")
                .qualityStatus("GOOD")
                .qualityConfidence(new BigDecimal("0.95"))
                .build();

        stubCommon(ocr);
        when(repo.createFlowReading(anyString(), anyLong(), anyLong(), any(LocalDateTime.class),
                any(BigDecimal.class), any(BigDecimal.class), anyString(), any(), any(), any()))
                .thenReturn(99L);

        CreateReadingResponse resp = service.createReading(request(), SCHEMA, operator, "919999999999", false);

        assertTrue(resp.isSuccess());
        assertEquals(0, resp.getMeterReading().compareTo(new BigDecimal("250")));

        ArgumentCaptor<BigDecimal> confirmed = ArgumentCaptor.forClass(BigDecimal.class);
        verify(repo).createFlowReading(anyString(), anyLong(), anyLong(), any(LocalDateTime.class),
                any(BigDecimal.class), confirmed.capture(), anyString(), any(), any(), any());
        assertEquals(0, confirmed.getValue().compareTo(new BigDecimal("250")));

        // Common path: no extra history round-trip and no provenance write.
        verify(repo, never()).findRecentDailyConfirmedReadings(any(), any(), any(), anyInt());
        verify(repo, never()).applyConfirmedReadingSource(any(), any(), anyInt(), any());
    }

    @Test
    void operatorManualOverrideWinsOverResolvedValue() {
        TenantContext.setSchema(SCHEMA);
        try {
            TelemetryLatestFlowReadingRecord reading = new TelemetryLatestFlowReadingRecord(
                    99L, 10L, 1L, "corr-1",
                    new BigDecimal("250"),  // extracted (model)
                    new BigDecimal("150"),  // confirmed (resolver's value)
                    IMAGE_URL, LocalDate.now(), LocalDateTime.now(), "BFM");
            when(repo.findFlowReadingDetailsByCorrelationId(SCHEMA, "corr-1"))
                    .thenReturn(Optional.of(reading));
            when(repo.findOperatorById(SCHEMA, 1L)).thenReturn(Optional.of(operator));

            CreateReadingResponse resp = service.updateConfirmedReading("corr-1", new BigDecimal("999"));

            // Reject-and-manual-entry: the human value overwrites confirmed_reading, resolver discarded.
            verify(repo).updateConfirmedReading(SCHEMA, 99L, new BigDecimal("999"), 1L);
            assertEquals(0, resp.getMeterReading().compareTo(new BigDecimal("999")));
            assertTrue(resp.isSuccess());
        } finally {
            TenantContext.clear();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────

    private void stubCommon(FlowVisionResult ocr) {
        when(repo.existsSchemeById(SCHEMA, 10L)).thenReturn(true);
        when(repo.findOperatorById(SCHEMA, 1L)).thenReturn(Optional.of(operator));
        when(repo.isOperatorMappedToScheme(SCHEMA, 1L, 10L)).thenReturn(true);
        when(flowVisionService.extractReading(IMAGE_URL)).thenReturn(ocr);
        when(repo.findLatestConfirmedReadingSnapshot(SCHEMA, 10L, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(new BigDecimal("140"),
                        LocalDateTime.now().minusDays(1))));
        when(repo.findLatestPlaceholderFlowReadingIdForDate(eq(SCHEMA), eq(10L), eq(1L), any(LocalDate.class)))
                .thenReturn(Optional.empty());
    }

    private static CreateReadingRequest request() {
        return CreateReadingRequest.builder()
                .schemeId(10L)
                .operatorId(1L)
                .readingUrl(IMAGE_URL)
                .build();
    }

    private static FlowVisionResult ocr(String rawReading, String adjusted, RolloverPosition... positions) {
        return FlowVisionResult.builder()
                .adjustedReading(new BigDecimal(adjusted))
                .rawMeterReading(rawReading)
                .redLastDigit(false)
                .hasRollover(true)
                .rolloverPositions(List.of(positions))
                .requestId("req-1")
                .correlationId("corr-1")
                .qualityStatus("GOOD")
                .qualityConfidence(new BigDecimal("0.95"))
                .build();
    }

    private static List<DailyConfirmedReading> history(long... readings) {
        List<DailyConfirmedReading> list = new ArrayList<>();
        LocalDate base = LocalDate.of(2026, 7, 1);
        for (int i = 0; i < readings.length; i++) {
            list.add(new DailyConfirmedReading(base.plusDays(i), BigDecimal.valueOf(readings[i])));
        }
        Collections.reverse(list);
        return list;
    }
}
