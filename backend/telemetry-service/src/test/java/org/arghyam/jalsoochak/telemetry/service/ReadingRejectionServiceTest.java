package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.channel.ReportingChannel;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryLatestFlowReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ReadingRejectionService — chatbot 'the reading is wrong' answer")
class ReadingRejectionServiceTest {

    private static final String SCHEMA = "tenant_test";
    private static final String PHONE = "919999999999";
    private static final Integer TENANT = 3;
    private static final Long OPERATOR = 11L;

    @Mock
    private OperatorContextService operatorContextService;
    @Mock
    private TelemetryTenantRepository telemetryTenantRepository;
    @Mock
    private BfmReadingService bfmReadingService;
    @Mock
    private ConversationLocalizationService localizationService;

    @InjectMocks
    private ReadingRejectionService service;

    @BeforeEach
    void setUp() {
        TelemetryOperatorWithSchema operatorWithSchema = new TelemetryOperatorWithSchema(
                SCHEMA, new TelemetryOperator(OPERATOR, TENANT, "op", "op@example.com", PHONE, null));
        when(operatorContextService.resolveOperatorWithSchema(PHONE)).thenReturn(operatorWithSchema);
        when(operatorContextService.resolveOperatorLanguage(operatorWithSchema, TENANT)).thenReturn("English");
        when(localizationService.normalizeLanguageKey(anyString())).thenReturn("english");
        when(localizationService.localizeMessage(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void latestReadingOn(LocalDate date) {
        when(telemetryTenantRepository.findLatestFlowReadingByOperator(SCHEMA, OPERATOR)).thenReturn(Optional.of(
                new TelemetryLatestFlowReadingRecord(501L, 7L, OPERATOR, "corr-1", new BigDecimal("1234"),
                        new BigDecimal("1234"), "https://img", date, null, ReadingChannel.BFM.getCode(), null, 0,
                        null)));
    }

    private IntroResponse reject() {
        return service.rejectTodaysLatestReading(IntroRequest.builder().contactId(PHONE).build());
    }

    @Test
    @DisplayName("resets today's reading within the operator's own tenant and asks for the correct value")
    void resetsTodaysReading() {
        latestReadingOn(ReadingTime.today());

        IntroResponse response = reject();

        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getMessage()).isEqualTo(ReadingRejectionService.REJECTED_MESSAGE);
        verify(bfmReadingService).resetLatestConfirmedReadingByPhone(PHONE, TENANT, ReportingChannel.WHATSAPP);
    }

    @Test
    @DisplayName("never zeroes an older reading when the operator has none today")
    void leavesOlderReadingsAlone() {
        latestReadingOn(ReadingTime.today().minusDays(1));

        IntroResponse response = reject();

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo(ReadingRejectionService.NOTHING_TO_REJECT_MESSAGE);
        verify(bfmReadingService, never()).resetLatestConfirmedReadingByPhone(anyString(), any(), any());
    }

    @Test
    @DisplayName("answers in the chatbot shape, not an exception, when the operator is unknown")
    void unknownOperatorIsAnAnswer() {
        when(operatorContextService.resolveOperatorWithSchema(PHONE))
                .thenThrow(new IllegalStateException("Operator not found"));

        IntroResponse response = reject();

        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getMessage()).isEqualTo(ReadingRejectionService.FAILURE_MESSAGE);
    }

    @Test
    @DisplayName("refuses a blank contact without looking anything up")
    void blankContact() {
        IntroResponse response = service.rejectTodaysLatestReading(IntroRequest.builder().contactId(" ").build());

        assertThat(response.isSuccess()).isFalse();
        verify(telemetryTenantRepository, never()).findLatestFlowReadingByOperator(anyString(), any());
    }
}
