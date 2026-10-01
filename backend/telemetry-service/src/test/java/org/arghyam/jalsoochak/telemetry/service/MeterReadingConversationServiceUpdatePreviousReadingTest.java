package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdatedPreviousReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryLatestFlowReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.service.capture.PduDayLimit;
import org.arghyam.jalsoochak.telemetry.service.capture.PduDayLimitFixtures;
import org.arghyam.jalsoochak.telemetry.service.capture.SubmittedValueCapture;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The operator's "update previous day's reading" flow is a manual correction: it must move
 * {@code confirmed_reading} only. It used to go through {@code updateReadingValues}, which also
 * overwrote {@code extracted_reading} with the hand-typed number, destroying the only record of what
 * the OCR provider actually read off that day's photo.
 *
 * <p>The corrected row is published again, and analytics recalculates the day and the day after it.
 * The correction follows the rules of the target row's channel, as a submission on it would.
 */
@ExtendWith(MockitoExtension.class)
class MeterReadingConversationServiceUpdatePreviousReadingTest {

    private static final LocalDate TARGET_DATE = ReadingTime.today().minusDays(2);

    @Mock
    private OperatorContextService operatorContextService;

    @Mock
    private ConversationLocalizationService localizationService;

    @Mock
    private ConversationTemplateService templatesService;

    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;

    @Mock
    private TelemetryEventPublisher telemetryEventPublisher;

    @Mock
    private ReadingRepublisher readingRepublisher;

    @Spy
    private SubmittedValueCapture submittedValueCapture = new SubmittedValueCapture();

    @Mock
    private PduDayLimit pduDayLimit;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private MeterReadingConversationService service;

    @BeforeEach
    void operator() {
        PduDayLimitFixtures.allowsEveryRun(pduDayLimit);
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                "tenant_test",
                new TelemetryOperator(1L, 1, "op", "op@example.com", "919999999999", null)
        );
        when(operatorContextService.resolveOperatorWithSchema("919999999999")).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, 1)).thenReturn("en");
        when(localizationService.normalizeLanguageKey("en")).thenReturn("english");
        when(telemetryTenantRepository.findFirstSchemeForUser("tenant_test", 1L)).thenReturn(Optional.of(10L));
    }

    @Test
    void correctionPublishesTheCorrectedRowAgainInsteadOfWaterQuantities() {
        targetRow(ReadingChannel.BFM, "1100");

        CreateReadingResponse resp = update("1000");

        assertEquals(true, resp.isSuccess());
        assertEquals("CONFIRMED", resp.getQualityStatus());
        assertEquals(new BigDecimal("1000"), resp.getMeterReading());
        assertEquals("corr-2", resp.getCorrelationId());
        InOrder order = inOrder(telemetryTenantRepository, readingRepublisher);
        order.verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 22L, new BigDecimal("1000"), 1L,
                RolloverResolutionService.SOURCE_MANUAL, "m3");
        order.verify(readingRepublisher).republish("tenant_test", 1, 22L);
        verify(telemetryTenantRepository, never()).updateReadingValues(anyString(), anyLong(), any(), anyLong());
        verifyNoInteractions(telemetryEventPublisher);
    }

    @Test
    void restatingTheStoredValueKeepsItsProvenance() {
        targetRow(ReadingChannel.BFM, "1100");

        CreateReadingResponse resp = update("1100");

        assertEquals(true, resp.isSuccess());
        verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 22L, new BigDecimal("1100"), 1L, null, "m3");
    }

    @Test
    void aLegacyRowWithNoChannelIsCorrectedAsBfm() {
        targetRow(null, "1100");

        update("1110");

        verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 22L, new BigDecimal("1110"), 1L,
                RolloverResolutionService.SOURCE_MANUAL, "m3");
    }

    @Test
    void aPduRowIsCorrectedInMinutes() {
        targetRow(ReadingChannel.PDU, "90");

        CreateReadingResponse resp = update("1440");

        assertEquals(true, resp.isSuccess());
        verify(telemetryTenantRepository).updateConfirmedReading("tenant_test", 22L, new BigDecimal("1440"), 1L,
                RolloverResolutionService.SOURCE_MANUAL, "min");
        verify(readingRepublisher).republish("tenant_test", 1, 22L);
    }

    @Test
    void aPduRunLongerThanADayIsRejectedWithoutWritingAnything() {
        targetRow(ReadingChannel.PDU, "90");
        when(localizationService.localizeMessage(SubmittedValueCapture.PDU_RUN_TOO_LONG_MESSAGE, "english"))
                .thenReturn(SubmittedValueCapture.PDU_RUN_TOO_LONG_MESSAGE);

        CreateReadingResponse resp = update("1441");

        assertEquals(false, resp.isSuccess());
        assertEquals("REJECTED", resp.getQualityStatus());
        assertEquals(SubmittedValueCapture.PDU_RUN_TOO_LONG_MESSAGE, resp.getMessage());
        verify(telemetryTenantRepository, never()).updateConfirmedReading(anyString(), anyLong(), any(), anyLong(), any(), any());
        verify(readingRepublisher, never()).republish(anyString(), any(), anyLong());
    }

    @Test
    void aPduCorrectionTakingItsDayPastTheLimitIsRejectedWithoutWritingAnything() {
        targetRow(ReadingChannel.PDU, "90");
        doReturn(Optional.empty()).when(pduDayLimit).writeWithinLimit(any(), any(), any(), any(), any(), any());
        when(localizationService.localizeMessage(PduDayLimit.PDU_DAY_TOO_LONG_MESSAGE, "english"))
                .thenReturn(PduDayLimit.PDU_DAY_TOO_LONG_MESSAGE);

        CreateReadingResponse resp = update("600");

        assertEquals(false, resp.isSuccess());
        assertEquals("REJECTED", resp.getQualityStatus());
        assertEquals(PduDayLimit.PDU_DAY_TOO_LONG_MESSAGE, resp.getMessage());
        // On the corrected row's own day, with its old minutes left out.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Supplier<Long>> replaced = ArgumentCaptor.forClass(Supplier.class);
        verify(pduDayLimit).writeWithinLimit(eq("tenant_test"), eq(10L), eq(TARGET_DATE), eq(new BigDecimal("600")),
                replaced.capture(), any());
        assertEquals(22L, replaced.getValue().get());
        verify(telemetryTenantRepository, never()).updateConfirmedReading(anyString(), anyLong(), any(), anyLong(), any(), any());
        verify(readingRepublisher, never()).republish(anyString(), any(), anyLong());
    }

    private void targetRow(ReadingChannel channel, String confirmedReading) {
        when(telemetryTenantRepository.findLatestCompletedFlowReadingBeforeDate("tenant_test", 10L, 1L, ReadingTime.today()))
                .thenReturn(Optional.of(new TelemetryLatestFlowReadingRecord(
                        22L, 10L, 1L, "corr-2", BigDecimal.ZERO, new BigDecimal(confirmedReading), "",
                        TARGET_DATE, TARGET_DATE.atTime(7, 0), channel == null ? null : channel.getCode(), 0, TARGET_DATE.atTime(7, 0))));
    }

    private CreateReadingResponse update(String reading) {
        return service.updatePreviousReadingMessage(UpdatedPreviousReadingRequest.builder()
                .contactId("919999999999")
                .reading(reading)
                .build());
    }
}
