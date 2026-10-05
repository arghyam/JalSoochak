package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannelResolver;
import org.arghyam.jalsoochak.telemetry.config.SupplyPlausibilityProperties;
import org.arghyam.jalsoochak.telemetry.dto.requests.CreateReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.OcrReadingResult;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.FlowReadingVersion;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.service.capture.ImageReadingCapture;
import org.arghyam.jalsoochak.telemetry.service.capture.PduDayLimit;
import org.arghyam.jalsoochak.telemetry.service.capture.PduDayLimitFixtures;
import org.arghyam.jalsoochak.telemetry.service.capture.SubmittedValueCapture;
import org.arghyam.jalsoochak.telemetry.service.water.SupplyPlausibilityFixtures;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A submitted value is stored and published in its channel's standard unit, with the unit it arrived
 * in recorded beside it. A unit the channel doesn't accept is refused before anything is stored.
 */
@ExtendWith(MockitoExtension.class)
class BfmReadingServiceReadingUnitTest {

    private static final String SCHEMA = "tenant_test";
    private static final long SCHEME_ID = 10L;
    private static final long OPERATOR_ID = 1L;
    private static final int TENANT_ID = 1;
    private static final String CONTACT = "919999999999";
    private static final String IMAGE_URL = "https://img.example.com/meter.jpg";

    @Mock
    private PduDayLimit pduDayLimit;

    @Mock
    private CalculationParametersSnapshotter calculationParametersSnapshotter;

    @Mock
    private TelemetryTenantRepository repo;
    @Mock
    private MeterReadingExtractor defaultOcrExtractor;
    @Mock
    private OcrProviderResolver ocrProviderResolver;
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
                new RolloverResolutionService(false, new ObjectMapper()),
                SupplyPlausibilityFixtures.guard(
                        SupplyPlausibilityProperties.Mode.AUDIT, repo, tenantConfigRepository),
                new ImageReadingCapture(
                        repo,
                        telemetryEventPublisher,
                        null,
                        ocrProviderResolver,
                        OcrFixtures.registryWithBfmDefault(defaultOcrExtractor)),
                new SubmittedValueCapture(),
                pduDayLimit,
                calculationParametersSnapshotter,
                null);
        lenient().when(repo.existsSchemeById(SCHEMA, SCHEME_ID)).thenReturn(true);
        lenient().when(repo.findOperatorById(SCHEMA, OPERATOR_ID)).thenReturn(Optional.of(operator));
        lenient().when(repo.isOperatorMappedToScheme(SCHEMA, OPERATOR_ID, SCHEME_ID)).thenReturn(true);
        lenient().when(repo.findLatestConfirmedReadingSnapshot(SCHEMA, SCHEME_ID, ReadingChannel.BFM, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(
                        new BigDecimal("0.5"), ReadingTime.now().minusDays(1))));
        lenient().when(repo.findLatestPlaceholderFlowReadingIdForDate(eq(SCHEMA), eq(SCHEME_ID), eq(OPERATOR_ID),
                any(LocalDate.class))).thenReturn(Optional.empty());
        lenient().when(repo.persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(LocalDateTime.class), any(BigDecimal.class), any(BigDecimal.class), anyString(), any(),
                any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new FlowReadingVersion(99L, null));
        lenient().when(repo.createFlowReading(anyString(), anyLong(), anyLong(), any(LocalDateTime.class),
                any(BigDecimal.class), any(BigDecimal.class), anyString(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new FlowReadingVersion(99L, null));
    }

    @Test
    @DisplayName("litres are stored and published as cubic metres, with L recorded as the submitted unit")
    void litresAreStoredAsCubicMetres() {
        CreateReadingResponse response = service.createReading(
                assertedValue(ReadingChannel.BFM, "1500", "L"), SCHEMA, operator, CONTACT, false);

        assertThat(response.isSuccess()).isTrue();
        verifyStored("1.5", ReadingChannel.BFM, "L");
        verifyPublished("1.5");
    }

    @Test
    @DisplayName("hours of pump running are stored and published as minutes, with h recorded")
    void hoursAreStoredAsMinutes() {
        service.createReading(assertedValue(ReadingChannel.PDU, "1.5", "H"), SCHEMA, operator, CONTACT, false);

        verifyStored("90", ReadingChannel.PDU, "h");
        verifyPublished("90");
    }

    @Test
    @DisplayName("a PDU run of exactly a day is accepted")
    void pduRunOfADayIsAccepted() {
        CreateReadingResponse response = service.createReading(
                assertedValue(ReadingChannel.PDU, "24", "h"), SCHEMA, operator, CONTACT, false);

        assertThat(response.isSuccess()).isTrue();
        verifyStored("1440", ReadingChannel.PDU, "h");
    }

    @Test
    @DisplayName("a PDU run longer than a day is refused, and nothing is stored or published")
    void pduRunLongerThanADayIsRefused() {
        CreateReadingResponse response = service.createReading(
                assertedValue(ReadingChannel.PDU, "1441", null), SCHEMA, operator, CONTACT, false);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getQualityStatus()).isEqualTo("REJECTED");
        assertThat(response.getErrorCode()).isEqualTo(TelemetryErrorCode.ABNORMAL_READING);
        assertThat(response.getMessage()).isEqualTo(SubmittedValueCapture.PDU_RUN_TOO_LONG_MESSAGE);
        verifyNothingStoredOrPublished();
    }

    @Test
    @DisplayName("a PDU run that takes its day past 1,440 minutes is refused, and nothing is stored or published")
    void pduRunTakingItsDayPastTheLimitIsRefused() {
        CreateReadingRequest request = assertedValue(ReadingChannel.PDU, "2", "h");
        request.setReadingTime(LocalDateTime.of(2026, 9, 30, 18, 0));
        doReturn(Optional.empty()).when(pduDayLimit).writeWithinLimit(
                any(), any(), any(), any(), any(), any());

        CreateReadingResponse response = service.createReading(request, SCHEMA, operator, CONTACT, false);

        // Checked in minutes, against the day the run is recorded for, and a new run replaces no row.
        ArgumentCaptor<Supplier<Long>> replaced = replacedRowCaptor();
        verify(pduDayLimit).writeWithinLimit(eq(SCHEMA), eq(SCHEME_ID), eq(LocalDate.of(2026, 9, 30)),
                argThat(sameValue("120")), replaced.capture(), any());
        assertThat(replaced.getValue().get()).isNull();

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getQualityStatus()).isEqualTo("REJECTED");
        assertThat(response.getErrorCode()).isEqualTo(TelemetryErrorCode.ABNORMAL_READING);
        assertThat(response.getMessage()).isEqualTo(PduDayLimit.PDU_DAY_TOO_LONG_MESSAGE);
        verifyNothingStoredOrPublished();
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<Supplier<Long>> replacedRowCaptor() {
        return ArgumentCaptor.forClass(Supplier.class);
    }

    @Test
    @DisplayName("the day's limit is not looked up for a reading on another channel")
    void dayLimitIsNotCheckedOffPdu() {
        service.createReading(assertedValue(ReadingChannel.BFM, "5", "m3"), SCHEMA, operator, CONTACT, false);

        verifyNoInteractions(pduDayLimit);
    }

    @Test
    @DisplayName("a PDU response carries no last confirmed reading: a run has no earlier total")
    void pduResponseHasNoLastConfirmedReading() {
        CreateReadingResponse response = service.createReading(
                assertedValue(ReadingChannel.PDU, "90", null), SCHEMA, operator, CONTACT, false);

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getLastConfirmedReading()).isNull();
        verify(repo, never()).findLatestConfirmedReadingSnapshot(any(), any(), any(), any());
        verify(repo, never()).findLastConfirmedReading(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an ELM submission is compared with the scheme's ELM readings only")
    void elmSubmissionIsComparedWithElmReadingsOnly() {
        when(repo.findLatestConfirmedReadingSnapshot(SCHEMA, SCHEME_ID, ReadingChannel.ELM, null))
                .thenReturn(Optional.of(new TelemetryConfirmedReadingSnapshot(
                        new BigDecimal("4800"), ReadingTime.now().minusDays(1))));

        CreateReadingResponse response = service.createReading(
                assertedValue(ReadingChannel.ELM, "4821.5", null), SCHEMA, operator, CONTACT, false);

        assertThat(response.getLastConfirmedReading()).isEqualByComparingTo("4800");
        verify(repo, never()).findLatestConfirmedReadingSnapshot(any(), any(), eq(ReadingChannel.BFM), any());
    }

    @Test
    @DisplayName("the response reports the stored value, in the channel's standard unit")
    void responseReportsTheStandardUnitValue() {
        CreateReadingResponse response = service.createReading(
                assertedValue(ReadingChannel.BFM, "1500", "L"), SCHEMA, operator, CONTACT, false);

        assertThat(response.getMeterReading()).isEqualByComparingTo("1.5");
        assertThat(response.getLastConfirmedReading()).isEqualByComparingTo("0.5");
    }

    @Test
    @DisplayName("a unit the channel doesn't accept is refused, and nothing is stored or published")
    void unitOfAnotherChannelIsRefused() {
        CreateReadingResponse response = service.createReading(
                assertedValue(ReadingChannel.BFM, "45", "min"), SCHEMA, operator, CONTACT, false);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getQualityStatus()).isEqualTo("REJECTED");
        assertThat(response.getErrorCode()).isEqualTo(TelemetryErrorCode.READING_UNIT_NOT_SUPPORTED);
        assertThat(response.getMessage())
                .isEqualTo("Unsupported reading_unit for channel BFM. Allowed values are: m3, kL, L");
        verifyNothingStoredOrPublished();
    }

    @Test
    @DisplayName("without a declared channel the unit is checked against the operator's preferred channel")
    void unitIsCheckedAgainstThePreferredChannel() {
        when(readingChannelResolver.resolve(SCHEMA, CONTACT)).thenReturn(ReadingChannel.PDU);

        CreateReadingResponse response = service.createReading(
                assertedValue(null, "1500", "L"), SCHEMA, operator, CONTACT, false);

        assertThat(response.getErrorCode()).isEqualTo(TelemetryErrorCode.READING_UNIT_NOT_SUPPORTED);
        verifyNothingStoredOrPublished();
    }

    @Test
    @DisplayName("a photo sent with a unit other than the standard one is refused without being read")
    void photoWithANonStandardUnitIsRefused() {
        when(readingChannelResolver.resolve(SCHEMA, CONTACT)).thenReturn(ReadingChannel.BFM);

        CreateReadingResponse response = service.createReading(photo("L"), SCHEMA, operator, CONTACT, false);

        assertThat(response.getErrorCode()).isEqualTo(TelemetryErrorCode.READING_UNIT_NOT_SUPPORTED);
        verify(defaultOcrExtractor, never()).extractReading(anyString(), any());
        verifyNoInteractions(ocrProviderResolver);
        verifyNothingStoredOrPublished();
    }

    @Test
    @DisplayName("the channel is resolved before the photo is read")
    void channelIsResolvedBeforeThePhotoIsRead() {
        when(readingChannelResolver.resolve(SCHEMA, CONTACT)).thenReturn(ReadingChannel.BFM);
        when(defaultOcrExtractor.extractReading(IMAGE_URL, null)).thenReturn(OcrReadingResult.builder()
                .adjustedReading(new BigDecimal("0.75"))
                .qualityConfidence(new BigDecimal("0.95"))
                .build());

        service.createReading(photo(null), SCHEMA, operator, CONTACT, false);

        InOrder order = inOrder(readingChannelResolver, defaultOcrExtractor);
        order.verify(readingChannelResolver).resolve(SCHEMA, CONTACT);
        order.verify(defaultOcrExtractor).extractReading(IMAGE_URL, null);
    }

    private void verifyStored(String value, ReadingChannel channel, String submittedUnit) {
        verify(repo).persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(LocalDateTime.class), any(BigDecimal.class), argThat(sameValue(value)), anyString(), any(),
                any(), any(), anyInt(), any(), any(), any(), any(), any(), eq(channel), eq(submittedUnit), any());
    }

    private void verifyPublished(String value) {
        verify(telemetryEventPublisher).publishMeterReadingRecorded(any(), any(), any(), any(),
                argThat(sameValue(value)), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private void verifyNothingStoredOrPublished() {
        verify(repo, never()).persistFlowReadingWithTracking(anyString(), any(), anyLong(), anyLong(),
                any(LocalDateTime.class), any(BigDecimal.class), any(BigDecimal.class), anyString(), any(),
                any(), any(), anyInt(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(repo, never()).createFlowReading(anyString(), anyLong(), anyLong(), any(LocalDateTime.class),
                any(BigDecimal.class), any(BigDecimal.class), anyString(), any(), any(), any(), any(), any(), any());
        verifyNoInteractions(telemetryEventPublisher);
    }

    private static ArgumentMatcher<BigDecimal> sameValue(String expected) {
        return actual -> actual != null && actual.compareTo(new BigDecimal(expected)) == 0;
    }

    private static CreateReadingRequest assertedValue(ReadingChannel channel, String value, String unit) {
        return CreateReadingRequest.builder()
                .schemeId(SCHEME_ID)
                .operatorId(OPERATOR_ID)
                .readingValue(new BigDecimal(value))
                .readingUnit(unit)
                .externallyAsserted(true)
                .declaredChannel(channel)
                .build();
    }

    private static CreateReadingRequest photo(String unit) {
        return CreateReadingRequest.builder()
                .schemeId(SCHEME_ID)
                .operatorId(OPERATOR_ID)
                .readingUrl(IMAGE_URL)
                .readingUnit(unit)
                .build();
    }
}
