package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannelResolver;
import org.arghyam.jalsoochak.telemetry.config.SupplyPlausibilityProperties;
import org.arghyam.jalsoochak.telemetry.dto.event.CalculationParameters;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.FlowReadingVersion;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.service.water.SupplyPlausibilityFixtures;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import org.arghyam.jalsoochak.telemetry.service.capture.ManualReadingMaxValues;
import org.arghyam.jalsoochak.telemetry.service.capture.PduDayLimit;
import org.arghyam.jalsoochak.telemetry.service.capture.PduDayLimitFixtures;
import org.arghyam.jalsoochak.telemetry.service.capture.SubmittedValueCapture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
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
 * A channel declared on the submission wins over the operator's stored preference; no declaration
 * leaves the existing preference lookup exactly as it was.
 */
@ExtendWith(MockitoExtension.class)
class BfmReadingServiceDeclaredChannelTest {

    private static final String SCHEMA = "tenant_test";
    private static final long SCHEME_ID = 10L;
    private static final long OPERATOR_ID = 1L;
    private static final int TENANT_ID = 1;
    private static final String CONTACT = "919999999999";
    private static final long READING_ID = 99L;
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 6, 22, 9, 30, 1, 250_000_000);

    @Mock
    private PduDayLimit pduDayLimit;

    @Mock
    private CalculationParametersSnapshotter calculationParametersSnapshotter;

    @Mock
    private TelemetryTenantRepository repo;
    @Mock
    private TelemetryEventPublisher telemetryEventPublisher;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private OperatorContextService operatorContextService;
    @Mock
    private ReadingChannelResolver readingChannelResolver;

    private BfmReadingService service;
    private final TelemetryOperator operator =
            new TelemetryOperator(OPERATOR_ID, TENANT_ID, "op", "op@example.com", CONTACT, null);

    @BeforeEach
    void setUp() {
        PduDayLimitFixtures.allowsEveryRun(pduDayLimit);
        service = new BfmReadingService(
                repo,
                telemetryEventPublisher,
                null,
                tenantConfigRepository,
                new ObjectMapper(),
                operatorContextService,
                readingChannelResolver,
                new RolloverResolutionService(true, new ObjectMapper()),
                SupplyPlausibilityFixtures.guard(
                        SupplyPlausibilityProperties.Mode.AUDIT, repo, tenantConfigRepository),
                null,
                new SubmittedValueCapture(mock(ManualReadingMaxValues.class)),
                pduDayLimit,
                calculationParametersSnapshotter,
                null);
        lenient().when(repo.existsSchemeById(SCHEMA, SCHEME_ID)).thenReturn(true);
        lenient().when(repo.findOperatorById(SCHEMA, OPERATOR_ID)).thenReturn(Optional.of(operator));
        lenient().when(repo.isOperatorMappedToScheme(SCHEMA, OPERATOR_ID, SCHEME_ID)).thenReturn(true);
        lenient().when(repo.findLatestConfirmedReadingSnapshot(SCHEMA, SCHEME_ID, ReadingChannel.BFM, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(
                        new BigDecimal("140"), ReadingTime.now().minusDays(1))));
        lenient().when(repo.findLatestPlaceholderFlowReadingIdForDate(eq(SCHEMA), eq(SCHEME_ID), eq(OPERATOR_ID),
                any(LocalDate.class))).thenReturn(Optional.empty());
        lenient().when(repo.persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(LocalDateTime.class), any(BigDecimal.class), any(BigDecimal.class), anyString(), any(),
                any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new FlowReadingVersion(READING_ID, UPDATED_AT));
    }

    /** The insert writes the channel, and the value's unit, which is the channel's standard unit. */
    private void verifyStoredWith(ReadingChannel channel, String submittedUnit) {
        verify(repo).persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(LocalDateTime.class), any(BigDecimal.class), any(BigDecimal.class), anyString(), any(),
                any(), any(), anyInt(), any(), any(), any(), any(), any(), eq(channel), eq(submittedUnit));
        verify(repo, never()).updateFlowReadingChannel(any(), any(), any());
    }

    @Test
    @DisplayName("a declared channel is persisted on the reading and never looked up from the preference")
    void declaredChannelWinsOverTheStoredPreference() {
        service.createReading(requestWithChannel(ReadingChannel.PDU), SCHEMA, operator, CONTACT, false);

        verifyStoredWith(ReadingChannel.PDU, "min");
        verify(readingChannelResolver, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("a declared channel is published on the event as its numeric code")
    void declaredChannelIsPublishedOnTheEvent() {
        service.createReading(requestWithChannel(ReadingChannel.ELM), SCHEMA, operator, CONTACT, false);

        verify(telemetryEventPublisher).publishMeterReadingRecorded(eq(TENANT_ID), eq(SCHEME_ID),
                eq(OPERATOR_ID), isNull(), eq(new BigDecimal("150")), isNull(), isNull(),
                any(LocalDateTime.class), eq(ReadingChannel.ELM.getCode()), any(LocalDate.class),
                eq(1), eq(0), any(), any(), any(), any());
    }

    /**
     * The event names the row it came from and the version the insert gave it, so analytics can keep
     * one fact row per submission and ignore an older version that arrives late.
     */
    @Test
    @DisplayName("the event carries the stored row's id and the version its insert wrote")
    void eventCarriesTheRowsIdentityAndVersion() {
        service.createReading(requestWithChannel(ReadingChannel.BFM), SCHEMA, operator, CONTACT, false);

        verify(telemetryEventPublisher).publishMeterReadingRecorded(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), eq(READING_ID), eq(UPDATED_AT), any());
    }

    /** An ELM or PDU reading carries what its water quantity is calculated from, taken for its channel. */
    @Test
    @DisplayName("the event carries the calculation snapshot taken for the reading's channel")
    void eventCarriesTheSnapshotForTheResolvedChannel() {
        CalculationParameters snapshot = new CalculationParameters(CalculationParameters.VERSION, null, null, List.of());
        when(calculationParametersSnapshotter.snapshot(SCHEMA, TENANT_ID, SCHEME_ID, ReadingChannel.PDU))
                .thenReturn(snapshot);

        service.createReading(requestWithChannel(ReadingChannel.PDU), SCHEMA, operator, CONTACT, false);

        verify(telemetryEventPublisher).publishMeterReadingRecorded(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), eq(snapshot));
    }

    @Test
    @DisplayName("declaring BFM explicitly behaves exactly like resolving to BFM")
    void declaringBfmIsHonouredToo() {
        service.createReading(requestWithChannel(ReadingChannel.BFM), SCHEMA, operator, CONTACT, false);

        verifyStoredWith(ReadingChannel.BFM, "m3");
        verify(readingChannelResolver, never()).resolve(any(), any());
    }

    @Test
    @DisplayName("without a declared channel the operator's stored preference still decides")
    void noDeclaredChannelFallsBackToThePreference() {
        when(readingChannelResolver.resolve(SCHEMA, CONTACT)).thenReturn(ReadingChannel.MAN);

        service.createReading(requestWithChannel(null), SCHEMA, operator, CONTACT, false);

        verify(readingChannelResolver).resolve(SCHEMA, CONTACT);
        // MAN has no reading defined yet, so there is no unit to record.
        verifyStoredWith(ReadingChannel.MAN, null);
    }

    @Test
    @DisplayName("without a declared channel and with no preference on record the reading stays BFM")
    void noDeclaredChannelAndNoPreferenceStaysBfm() {
        when(readingChannelResolver.resolve(SCHEMA, CONTACT)).thenReturn(ReadingChannel.BFM);

        service.createReading(requestWithChannel(null), SCHEMA, operator, CONTACT, false);

        verifyStoredWith(ReadingChannel.BFM, "m3");
    }

    private static CreateReadingRequest requestWithChannel(ReadingChannel channel) {
        return CreateReadingRequest.builder()
                .schemeId(SCHEME_ID)
                .operatorId(OPERATOR_ID)
                .readingValue(new BigDecimal("150"))
                .externallyAsserted(true)
                .declaredChannel(channel)
                .build();
    }
}
