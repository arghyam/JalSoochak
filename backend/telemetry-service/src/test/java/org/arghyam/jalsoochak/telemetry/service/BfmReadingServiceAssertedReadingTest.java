package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannelResolver;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.FlowVisionResult;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * READING-PROVENANCE: a value supplied through {@code confirmed_reading} is stored with
 * {@code confirmed_reading_source = EXTERNALLY_ASSERTED} so it stays distinguishable from an
 * AI-extracted reading in the database.
 *
 * <p>Submitting a value instead of an image is supported behaviour and is deliberately <b>not</b>
 * restricted: these tests also pin that it is still accepted, still reported {@code CONFIRMED}, and
 * still written with the same reading values as before.
 */
@ExtendWith(MockitoExtension.class)
class BfmReadingServiceAssertedReadingTest {

    private static final String SCHEMA = "tenant_test";
    private static final long SCHEME_ID = 10L;
    private static final long OPERATOR_ID = 1L;
    private static final int TENANT_ID = 1;
    private static final String CONTACT = "919999999999";

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
            new TelemetryOperator(OPERATOR_ID, TENANT_ID, "op", "op@example.com", CONTACT, null);

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
        lenient().when(repo.existsSchemeById(SCHEMA, SCHEME_ID)).thenReturn(true);
        lenient().when(repo.findOperatorById(SCHEMA, OPERATOR_ID)).thenReturn(Optional.of(operator));
        lenient().when(repo.isOperatorMappedToScheme(SCHEMA, OPERATOR_ID, SCHEME_ID)).thenReturn(true);
        lenient().when(repo.findLatestConfirmedReadingSnapshot(SCHEMA, SCHEME_ID, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(
                        new BigDecimal("140"), LocalDateTime.now().minusDays(1))));
    }

    @Test
    @DisplayName("an API-supplied value is stored with EXTERNALLY_ASSERTED provenance")
    void suppliedValueIsMarkedAsExternallyAsserted() {
        stubPersistence();

        service.createReading(assertedRequest(new BigDecimal("150")), SCHEMA, operator, CONTACT, false);

        verify(repo).persistFlowReadingWithTracking(eq(SCHEMA), isNull(), eq(SCHEME_ID), eq(OPERATOR_ID),
                any(LocalDateTime.class), eq(new BigDecimal("150")), eq(new BigDecimal("150")), anyString(),
                isNull(), isNull(), isNull(), eq(IngestionSource.NORMAL), isNull(), isNull(), isNull(),
                eq(RolloverResolutionService.SOURCE_EXTERNALLY_ASSERTED));
    }

    @Test
    @DisplayName("an API-supplied value is still accepted and still reported CONFIRMED")
    void suppliedValueIsStillAcceptedUnchanged() {
        stubPersistence();

        CreateReadingResponse response =
                service.createReading(assertedRequest(new BigDecimal("150")), SCHEMA, operator, CONTACT, false);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getQualityStatus()).isEqualTo("CONFIRMED");
        assertThat(response.getMeterReading()).isEqualByComparingTo("150");
    }

    @Test
    @DisplayName("a value far above the scheme's history is still accepted — no plausibility band")
    void suppliedValueIsNotBounded() {
        stubPersistence();

        CreateReadingResponse response =
                service.createReading(assertedRequest(new BigDecimal("99999")), SCHEMA, operator, CONTACT, false);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMeterReading()).isEqualByComparingTo("99999");
    }

    @Test
    @DisplayName("an image-extracted reading keeps the untouched insert path and default provenance")
    void imagePathIsUnchanged() {
        when(flowVisionService.extractReading(anyString())).thenReturn(FlowVisionResult.builder()
                .adjustedReading(new BigDecimal("150"))
                .qualityConfidence(new BigDecimal("0.95"))
                .qualityStatus("CONFIRMED")
                .build());
        when(repo.findLatestPlaceholderFlowReadingIdForDate(eq(SCHEMA), eq(SCHEME_ID), eq(OPERATOR_ID),
                any(LocalDate.class))).thenReturn(Optional.empty());
        when(repo.createFlowReading(anyString(), anyLong(), anyLong(), any(LocalDateTime.class),
                any(BigDecimal.class), any(BigDecimal.class), anyString(), any(), any(), any()))
                .thenReturn(99L);

        CreateReadingResponse response = service.createReading(
                CreateReadingRequest.builder()
                        .schemeId(SCHEME_ID)
                        .operatorId(OPERATOR_ID)
                        .readingUrl("https://img.example.com/a.jpg")
                        .build(),
                SCHEMA, operator, CONTACT, false);

        assertThat(response.getQualityStatus()).isEqualTo("CONFIRMED");
        // The image path must not be routed through the tracking/provenance overload.
        verify(repo, org.mockito.Mockito.never()).persistFlowReadingWithTracking(anyString(), any(), anyLong(),
                anyLong(), any(), any(), any(), anyString(), any(), any(), any(), anyInt(), any(), any(),
                any(), any());
    }

    private static CreateReadingRequest assertedRequest(BigDecimal value) {
        return CreateReadingRequest.builder()
                .schemeId(SCHEME_ID)
                .operatorId(OPERATOR_ID)
                .readingValue(value)
                .externallyAsserted(true)
                .build();
    }

    private void stubPersistence() {
        when(repo.findLatestPlaceholderFlowReadingIdForDate(eq(SCHEMA), eq(SCHEME_ID), eq(OPERATOR_ID),
                any(LocalDate.class))).thenReturn(Optional.empty());
        when(repo.persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(LocalDateTime.class), any(BigDecimal.class), any(BigDecimal.class), anyString(), any(),
                any(), any(), anyInt(), any(), any(), any(), any()))
                .thenReturn(99L);
    }
}
