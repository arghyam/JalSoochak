package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannelResolver;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.TelemetryErrorCode;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryLatestFlowReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.service.capture.ManualReadingMaxValues;
import org.arghyam.jalsoochak.telemetry.service.capture.PduDayLimit;
import org.arghyam.jalsoochak.telemetry.service.capture.PduDayLimitFixtures;
import org.arghyam.jalsoochak.telemetry.service.capture.SubmittedValueCapture;
import org.arghyam.jalsoochak.telemetry.service.water.SupplyPlausibilityGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A State-IT correction ({@code PUT /readings}, and the reset) follows the submission rules of the
 * corrected row's channel: the declared unit is checked and converted, a PDU run, or its day's runs
 * together, can't be longer than a day, and the unit the value arrived in is written with it.
 */
@ExtendWith(MockitoExtension.class)
class BfmReadingServiceCorrectionChannelTest {

    private static final String SCHEMA = "tenant_test";
    private static final int TENANT_ID = 22;
    private static final long READING_ID = 99L;
    private static final long OPERATOR_ID = 1L;
    private static final String CONTACT = "919999999999";
    private static final LocalDate READING_DATE = LocalDate.of(2026, 6, 22);

    @Mock
    private PduDayLimit pduDayLimit;

    @Mock
    private ManualReadingMaxValues manualReadingMaxValues;

    @Mock
    private CalculationParametersSnapshotter calculationParametersSnapshotter;

    @Mock
    private TelemetryTenantRepository repo;
    @Mock
    private TelemetryEventPublisher telemetryEventPublisher;
    @Mock
    private ReadingRepublisher readingRepublisher;
    @Mock
    private TenantConfigRepository tenantConfigRepository;
    @Mock
    private OperatorContextService operatorContextService;
    @Mock
    private ReadingChannelResolver readingChannelResolver;
    @Mock
    private SupplyPlausibilityGuard supplyPlausibilityGuard;

    private BfmReadingService service;
    private final TelemetryOperator operator =
            new TelemetryOperator(OPERATOR_ID, TENANT_ID, "op", "op@example.com", CONTACT, null);

    @BeforeEach
    void setUp() {
        PduDayLimitFixtures.allowsEveryRun(pduDayLimit);
        service = new BfmReadingService(
                repo,
                telemetryEventPublisher,
                readingRepublisher,
                tenantConfigRepository,
                new ObjectMapper(),
                operatorContextService,
                readingChannelResolver,
                new RolloverResolutionService(false, new ObjectMapper()),
                supplyPlausibilityGuard,
                null,
                new SubmittedValueCapture(manualReadingMaxValues),
                pduDayLimit,
                calculationParametersSnapshotter,
                null);
        lenient().when(repo.findSchemaNameByTenantId(TENANT_ID)).thenReturn(Optional.of(SCHEMA));
        lenient().when(repo.findOperatorById(SCHEMA, OPERATOR_ID)).thenReturn(Optional.of(operator));
    }

    private void correcting(ReadingChannel channel, String storedValue) {
        when(repo.findFlowReadingDetailsByCorrelationId(SCHEMA, "corr-1"))
                .thenReturn(Optional.of(row(channel, storedValue)));
    }

    private static TelemetryLatestFlowReadingRecord row(ReadingChannel channel, String storedValue) {
        return new TelemetryLatestFlowReadingRecord(READING_ID, 10L, OPERATOR_ID, "corr-1", BigDecimal.ZERO,
                new BigDecimal(storedValue), "", READING_DATE, READING_DATE.atTime(9, 30), channel == null ? null : channel.getCode(), 0, null);
    }

    private CreateReadingResponse correct(String value, String unit) {
        return service.updateConfirmedReading("corr-1", null, new BigDecimal(value), unit, TENANT_ID);
    }

    private void verifyNothingWritten() {
        verify(repo, never()).updateConfirmedReading(anyString(), anyLong(), any(), anyLong(), any(), any());
        verify(repo, never()).applyQuarantineReason(anyString(), anyLong(), anyInt());
        verifyNoInteractions(readingRepublisher);
    }

    @Test
    @DisplayName("a BFM correction in litres is stored in cubic metres, with L recorded")
    void bfmCorrectionInLitresIsStoredInCubicMetres() {
        correcting(ReadingChannel.BFM, "1.2");

        CreateReadingResponse response = correct("1500", "L");

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMeterReading()).isEqualByComparingTo("1.5");
        verify(repo).updateConfirmedReading(SCHEMA, READING_ID, new BigDecimal("1.500"), OPERATOR_ID,
                RolloverResolutionService.SOURCE_MANUAL, "L");
        verify(readingRepublisher).republish(SCHEMA, TENANT_ID, READING_ID);
    }

    @Test
    @DisplayName("a legacy row with no channel is corrected as BFM, in its standard unit")
    void legacyRowIsCorrectedAsBfm() {
        correcting(null, "1.2");

        correct("1.5", null);

        verify(repo).updateConfirmedReading(SCHEMA, READING_ID, new BigDecimal("1.5"), OPERATOR_ID,
                RolloverResolutionService.SOURCE_MANUAL, "m3");
    }

    @Test
    @DisplayName("a unit the corrected row's channel doesn't accept is refused, and nothing is written")
    void unitOfAnotherChannelIsRefused() {
        correcting(ReadingChannel.PDU, "90");

        CreateReadingResponse response = correct("1.5", "m3");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getQualityStatus()).isEqualTo("REJECTED");
        assertThat(response.getErrorCode()).isEqualTo(TelemetryErrorCode.READING_UNIT_NOT_SUPPORTED);
        assertThat(response.getCorrelationId()).isEqualTo("corr-1");
        verifyNothingWritten();
    }

    @Test
    @DisplayName("a PDU correction in hours is stored in minutes, without the BFM-only supply check")
    void pduCorrectionInHoursIsStoredInMinutes() {
        correcting(ReadingChannel.PDU, "90");

        CreateReadingResponse response = correct("2", "h");

        assertThat(response.isSuccess()).isTrue();
        verify(repo).updateConfirmedReading(SCHEMA, READING_ID, new BigDecimal("120"), OPERATOR_ID,
                RolloverResolutionService.SOURCE_MANUAL, "h");
        verify(supplyPlausibilityGuard, never()).assess(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a PDU correction longer than a day is refused, and nothing is written")
    void pduCorrectionLongerThanADayIsRefused() {
        correcting(ReadingChannel.PDU, "90");

        CreateReadingResponse response = correct("25", "h");

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo(TelemetryErrorCode.ABNORMAL_READING);
        assertThat(response.getMessage()).isEqualTo(SubmittedValueCapture.PDU_RUN_TOO_LONG_MESSAGE);
        verifyNothingWritten();
    }

    @Test
    @DisplayName("a correction above the channel's configured maximum is refused, and nothing is written")
    void correctionAboveTheConfiguredMaximumIsRefused() {
        correcting(ReadingChannel.ELM, "4000");
        when(manualReadingMaxValues.maxFor(TENANT_ID, ReadingChannel.ELM)).thenReturn(Optional.of(new BigDecimal("5000")));

        CreateReadingResponse response = correct("5000.5", null);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo(TelemetryErrorCode.ABNORMAL_READING);
        assertThat(response.getMessage()).isEqualTo("Reading can't be more than 5000 kWh.");
        assertThat(response.getCorrelationId()).isEqualTo("corr-1");
        verifyNothingWritten();
    }

    @Test
    @DisplayName("a PDU correction that takes its day past 1,440 minutes is refused, and nothing is written")
    void pduCorrectionTakingItsDayPastTheLimitIsRefused() {
        correcting(ReadingChannel.PDU, "90");
        doReturn(Optional.empty()).when(pduDayLimit).writeWithinLimit(any(), any(), any(), any(), any(), any());

        CreateReadingResponse response = correct("2", "h");

        // In minutes, on the row's own day, with the row's old minutes left out.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Supplier<Long>> replaced = ArgumentCaptor.forClass(Supplier.class);
        verify(pduDayLimit).writeWithinLimit(eq(SCHEMA), eq(10L), eq(READING_DATE), eq(new BigDecimal("120")),
                replaced.capture(), any());
        assertThat(replaced.getValue().get()).isEqualTo(READING_ID);

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getErrorCode()).isEqualTo(TelemetryErrorCode.ABNORMAL_READING);
        assertThat(response.getMessage()).isEqualTo(PduDayLimit.PDU_DAY_TOO_LONG_MESSAGE);
        assertThat(response.getCorrelationId()).isEqualTo("corr-1");
        verifyNothingWritten();
    }

    @Test
    @DisplayName("the reset's 0 is written in the standard unit of the row's channel")
    void resetWritesTheStandardUnitOfTheRowsChannel() {
        when(operatorContextService.resolveOperatorWithSchema(CONTACT, TENANT_ID))
                .thenReturn(new TelemetryOperatorWithSchema(SCHEMA, operator));
        when(repo.findLatestFlowReadingByOperator(SCHEMA, OPERATOR_ID)).thenReturn(Optional.of(row(ReadingChannel.PDU, "90")));

        service.resetLatestConfirmedReadingByPhone(CONTACT, TENANT_ID);

        verify(repo).updateConfirmedReading(SCHEMA, READING_ID, BigDecimal.ZERO, OPERATOR_ID, null, "min");
    }
}
