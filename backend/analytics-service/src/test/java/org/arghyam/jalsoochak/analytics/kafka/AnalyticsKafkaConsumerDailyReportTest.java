package org.arghyam.jalsoochak.analytics.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.analytics.dto.DailyReportKpiDTO;
import org.arghyam.jalsoochak.analytics.dto.event.DailyReportKpisEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SubmissionRejectedEvent;
import org.arghyam.jalsoochak.analytics.service.DailySituationReportService;
import org.arghyam.jalsoochak.analytics.service.DimensionService;
import org.arghyam.jalsoochak.analytics.service.FactService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The two consumer paths that reach beyond the fact/dimension services: the daily situation report
 * request (which computes KPIs and republishes them for message-service) and the reported-metric
 * submission reject.
 *
 * <p>An invalid daily-report event is non-retryable, so it is dropped rather than rethrown — a
 * rethrow would make the listener redeliver it forever.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AnalyticsKafkaConsumer — daily report and reported-metric")
class AnalyticsKafkaConsumerDailyReportTest {

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private DimensionService dimensionService;
    @Mock
    private FactService factService;
    @Mock
    private DailySituationReportService dailySituationReportService;
    @Mock
    private KafkaProducer kafkaProducer;

    @InjectMocks
    private AnalyticsKafkaConsumer consumer;

    private static String dailyReportRequest(String fields) {
        return "{\"eventType\":\"DAILY_REPORT_REQUEST\"," + fields + "}";
    }

    private static final String VALID_REQUEST = dailyReportRequest("""
            "tenantId":17,"tenantSchema":"tenant_as","officerUserId":11,
            "officerUserType":"SECTION_OFFICER","reportDate":"2026-03-01","correlationId":"corr-1"
            """);

    private DailyReportKpiDTO kpis() {
        return DailyReportKpiDTO.builder()
                .reportDate("2026-03-01")
                .previousDate("2026-02-28")
                .totalSchemes(12)
                .reasonsForNoSupply(List.of())
                .anomaliesByType(List.of())
                .priorityActions(List.of())
                .build();
    }

    @Nested
    @DisplayName("DAILY_REPORT_REQUEST")
    class DailyReportRequest {

        @Test
        void computesTheKpisAndRepublishesThemForMessageService() {
            when(dailySituationReportService.buildReport(anyInt(), anyLong(), any(LocalDate.class), any()))
                    .thenReturn(kpis());

            consumer.consumeCommonTopic(VALID_REQUEST);

            verify(dailySituationReportService).buildReport(
                    eq(17), eq(11L), eq(LocalDate.of(2026, 3, 1)), any());

            ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
            verify(kafkaProducer).publishJson(eq("common-topic"), published.capture());

            assertThat(published.getValue()).isInstanceOf(DailyReportKpisEvent.class);
            DailyReportKpisEvent event = (DailyReportKpisEvent) published.getValue();
            assertThat(event.getEventType()).isEqualTo("DAILY_REPORT_KPIS");
            assertThat(event.getTenantId()).isEqualTo(17);
            assertThat(event.getTenantSchema()).isEqualTo("tenant_as");
            assertThat(event.getOfficerUserId()).isEqualTo(11L);
            assertThat(event.getOfficerUserType()).isEqualTo("SECTION_OFFICER");
            assertThat(event.getCorrelationId()).isEqualTo("corr-1");
            assertThat(event.getKpis().getTotalSchemes()).isEqualTo(12);
        }

        @Test
        void forwardsTheSubordinateOfficerListForARollUpReport() {
            when(dailySituationReportService.buildReport(anyInt(), anyLong(), any(LocalDate.class), any()))
                    .thenReturn(kpis());

            consumer.consumeCommonTopic(dailyReportRequest("""
                    "tenantId":17,"tenantSchema":"tenant_as","officerUserId":11,
                    "officerUserType":"SUB_DIVISIONAL_OFFICER","reportDate":"2026-03-01",
                    "correlationId":"corr-1","subordinateOfficerUserIds":[21,22]
                    """));

            verify(dailySituationReportService).buildReport(
                    eq(17), eq(11L), eq(LocalDate.of(2026, 3, 1)), eq(List.of(21L, 22L)));
        }

        @Test
        void trimsTheOfficerRoleBeforePublishingIt() {
            when(dailySituationReportService.buildReport(anyInt(), anyLong(), any(LocalDate.class), any()))
                    .thenReturn(kpis());

            consumer.consumeCommonTopic(dailyReportRequest("""
                    "tenantId":17,"tenantSchema":"tenant_as","officerUserId":11,
                    "officerUserType":"  SECTION_OFFICER  ","reportDate":"2026-03-01"
                    """));

            ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
            verify(kafkaProducer).publishJson(eq("common-topic"), published.capture());
            assertThat(((DailyReportKpisEvent) published.getValue()).getOfficerUserType())
                    .isEqualTo("SECTION_OFFICER");
        }

        @ParameterizedTest(name = "drops the event when {0} is missing")
        @ValueSource(strings = {
                "\"tenantSchema\":\"tenant_as\",\"officerUserId\":11,\"officerUserType\":\"SECTION_OFFICER\",\"reportDate\":\"2026-03-01\"",
                "\"tenantId\":17,\"officerUserId\":11,\"officerUserType\":\"SECTION_OFFICER\",\"reportDate\":\"2026-03-01\"",
                "\"tenantId\":17,\"tenantSchema\":\"tenant_as\",\"officerUserType\":\"SECTION_OFFICER\",\"reportDate\":\"2026-03-01\"",
                "\"tenantId\":17,\"tenantSchema\":\"tenant_as\",\"officerUserId\":11,\"reportDate\":\"2026-03-01\"",
                "\"tenantId\":17,\"tenantSchema\":\"tenant_as\",\"officerUserId\":11,\"officerUserType\":\"SECTION_OFFICER\""
        })
        void dropsAnEventMissingARequiredField(String fields) {
            consumer.consumeCommonTopic(dailyReportRequest(fields));

            verifyNoInteractions(dailySituationReportService);
            verifyNoInteractions(kafkaProducer);
        }

        @ParameterizedTest(name = "drops the event when the blank field is {0}")
        @ValueSource(strings = {"\"\"", "\"   \""})
        void dropsAnEventWithABlankSchemaOrRoleOrDate(String blank) {
            consumer.consumeCommonTopic(dailyReportRequest(
                    "\"tenantId\":17,\"tenantSchema\":" + blank + ",\"officerUserId\":11,"
                            + "\"officerUserType\":\"SECTION_OFFICER\",\"reportDate\":\"2026-03-01\""));
            consumer.consumeCommonTopic(dailyReportRequest(
                    "\"tenantId\":17,\"tenantSchema\":\"tenant_as\",\"officerUserId\":11,"
                            + "\"officerUserType\":" + blank + ",\"reportDate\":\"2026-03-01\""));
            consumer.consumeCommonTopic(dailyReportRequest(
                    "\"tenantId\":17,\"tenantSchema\":\"tenant_as\",\"officerUserId\":11,"
                            + "\"officerUserType\":\"SECTION_OFFICER\",\"reportDate\":" + blank));

            verifyNoInteractions(dailySituationReportService);
            verifyNoInteractions(kafkaProducer);
        }

        @Test
        void dropsAnEventWhoseReportDateIsMalformedRatherThanRedeliveringItForever() {
            consumer.consumeCommonTopic(dailyReportRequest("""
                    "tenantId":17,"tenantSchema":"tenant_as","officerUserId":11,
                    "officerUserType":"SECTION_OFFICER","reportDate":"01-03-2026"
                    """));

            verifyNoInteractions(dailySituationReportService);
            verifyNoInteractions(kafkaProducer);
        }

        @Test
        void rethrowsWhenTheReportComputationFailsSoTheEventIsRedelivered() {
            when(dailySituationReportService.buildReport(anyInt(), anyLong(), any(LocalDate.class), any()))
                    .thenThrow(new IllegalStateException("query timed out"));

            assertThatThrownBy(() -> consumer.consumeCommonTopic(VALID_REQUEST))
                    .isInstanceOf(RuntimeException.class);

            verify(kafkaProducer, never()).publishJson(any(), any());
        }

        @Test
        void rethrowsWhenRepublishingTheKpisFails() {
            when(dailySituationReportService.buildReport(anyInt(), anyLong(), any(LocalDate.class), any()))
                    .thenReturn(kpis());
            org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                    .when(kafkaProducer).publishJson(any(), any());

            assertThatThrownBy(() -> consumer.consumeCommonTopic(VALID_REQUEST))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        void handlesAKpiPayloadWhoseOptionalSectionsAreAbsent() {
            // The completion log reads every optional section; a sparse report must not break it.
            when(dailySituationReportService.buildReport(anyInt(), anyLong(), any(LocalDate.class), any()))
                    .thenReturn(DailyReportKpiDTO.builder().totalSchemes(0).build());

            consumer.consumeCommonTopic(VALID_REQUEST);

            verify(kafkaProducer).publishJson(eq("common-topic"), any());
        }

        @Test
        void handlesAKpiPayloadCarryingAYesterdaySection() {
            when(dailySituationReportService.buildReport(anyInt(), anyLong(), any(LocalDate.class), any()))
                    .thenReturn(DailyReportKpiDTO.builder()
                            .totalSchemes(12)
                            .yesterday(DailyReportKpiDTO.DayKpis.builder().schemesSupplying(9).build())
                            .reasonsForNoSupply(List.of(
                                    DailyReportKpiDTO.ReasonCount.builder().reason("Pump failure").count(2).build()))
                            .anomaliesByType(List.of(
                                    DailyReportKpiDTO.TypeCount.builder().type("LOW_SUPPLY").count(1).build()))
                            .build());

            consumer.consumeCommonTopic(VALID_REQUEST);

            verify(kafkaProducer).publishJson(eq("common-topic"), any());
        }
    }

    @Nested
    @DisplayName("SUBMISSION_REJECTED")
    class SubmissionRejected {

        @Test
        void routesTheRejectedSubmissionToTheFactService() {
            consumer.consumeTelemetryEvents("""
                    {"eventType":"SUBMISSION_REJECTED","tenantId":17,
                     "submittedStateSchemeId":"5001","submittedCentreSchemeId":"6001",
                     "submittedPhoneHash":"hash","reason":"validation failed",
                     "attemptedAt":"2026-03-01T06:30:00"}
                    """);

            ArgumentCaptor<SubmissionRejectedEvent> event =
                    ArgumentCaptor.forClass(SubmissionRejectedEvent.class);
            verify(factService).ingestSubmissionRejected(event.capture());

            assertThat(event.getValue().getTenantId()).isEqualTo(17);
            assertThat(event.getValue().getSubmittedStateSchemeId()).isEqualTo("5001");
            assertThat(event.getValue().getSubmittedCentreSchemeId()).isEqualTo("6001");
            assertThat(event.getValue().getReason()).isEqualTo("validation failed");
        }

        @Test
        void rethrowsWhenIngestionFails() {
            org.mockito.Mockito.doThrow(new IllegalStateException("insert failed"))
                    .when(factService).ingestSubmissionRejected(any());

            assertThatThrownBy(() -> consumer.consumeTelemetryEvents(
                    "{\"eventType\":\"SUBMISSION_REJECTED\",\"tenantId\":17}"))
                    .isInstanceOf(RuntimeException.class);
        }
    }
}
