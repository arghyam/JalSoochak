package org.arghyam.jalsoochak.analytics.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.analytics.dto.DailyReportKpiDTO;
import org.arghyam.jalsoochak.analytics.dto.event.DailyReportKpisEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SubmissionRejectedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WeeklyReportKpisEvent;
import org.arghyam.jalsoochak.analytics.dto.WeeklyReportKpiDTO;
import org.arghyam.jalsoochak.analytics.service.DailySituationReportService;
import org.arghyam.jalsoochak.analytics.service.WeeklySituationReportService;
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
import java.time.LocalDateTime;
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
    private WeeklySituationReportService weeklySituationReportService;
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
                .cutoffIst("2026-03-01T16:00:00")
                .totalSchemes(12)
                .schemesSupplying(9)
                .schemesNotSupplying(3)
                .noSupplySchemeIds(List.of())
                .schemeAnomalies(List.of())
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
        void passesTheCutoffThroughSoAReplayReproducesTheNumbers() {
            when(dailySituationReportService.buildReport(anyInt(), anyLong(), any(LocalDate.class), any()))
                    .thenReturn(kpis());

            consumer.consumeCommonTopic(dailyReportRequest("""
                    "tenantId":17,"tenantSchema":"tenant_as","officerUserId":11,
                    "officerUserType":"SECTION_OFFICER","reportDate":"2026-03-01",
                    "cutoffIst":"2026-03-01T16:00:00","correlationId":"corr-1"
                    """));

            verify(dailySituationReportService).buildReport(
                    eq(17), eq(11L), eq(LocalDate.of(2026, 3, 1)),
                    eq(LocalDateTime.of(2026, 3, 1, 16, 0)));
        }

        @Test
        void coversTheWholeDayWhenTheCutoffIsMalformedRatherThanDroppingTheReport() {
            // Losing an hour's precision beats losing the officer's report entirely.
            when(dailySituationReportService.buildReport(anyInt(), anyLong(), any(LocalDate.class), any()))
                    .thenReturn(kpis());

            consumer.consumeCommonTopic(dailyReportRequest("""
                    "tenantId":17,"tenantSchema":"tenant_as","officerUserId":11,
                    "officerUserType":"SECTION_OFFICER","reportDate":"2026-03-01",
                    "cutoffIst":"not-a-time","correlationId":"corr-1"
                    """));

            verify(dailySituationReportService).buildReport(
                    eq(17), eq(11L), eq(LocalDate.of(2026, 3, 1)), eq((LocalDateTime) null));
            verify(kafkaProducer).publishJson(eq("common-topic"), any());
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
        void handlesAKpiPayloadCarryingEveryOptionalSection() {
            when(dailySituationReportService.buildReport(anyInt(), anyLong(), any(LocalDate.class), any()))
                    .thenReturn(DailyReportKpiDTO.builder()
                            .totalSchemes(12)
                            .schemesSupplying(9)
                            .noSupplySchemeIds(List.of(4, 5, 6))
                            .schemeAnomalies(List.of(DailyReportKpiDTO.SchemeAnomaly.builder()
                                    .schemeId(4).type("UNREADABLE_IMAGE").build()))
                            .anomalousCount(1)
                            .build());

            consumer.consumeCommonTopic(VALID_REQUEST);

            verify(kafkaProducer).publishJson(eq("common-topic"), any());
        }
    }

    @Nested
    @DisplayName("WEEKLY_REPORT_REQUEST")
    class WeeklyReportRequest {

        private static String weeklyReportRequest(String fields) {
            return "{\"eventType\":\"WEEKLY_REPORT_REQUEST\"," + fields + "}";
        }

        private static final String VALID_WEEKLY = weeklyReportRequest("""
                "tenantId":17,"tenantSchema":"tenant_as","officerUserId":11,
                "officerUserType":"SECTION_OFFICER","weekStart":"2026-02-23","weekEnd":"2026-03-01",
                "previousWeekStart":"2026-02-16","previousWeekEnd":"2026-02-22","correlationId":"corr-9"
                """);

        private WeeklyReportKpiDTO weeklyKpis() {
            return WeeklyReportKpiDTO.builder()
                    .weekStart("2026-02-23")
                    .weekEnd("2026-03-01")
                    .week(WeeklyReportKpiDTO.WeekKpis.builder().totalSchemes(12).schemesSupplying(10).build())
                    .previousWeek(WeeklyReportKpiDTO.WeekKpis.builder().totalSchemes(12).schemesSupplying(8).build())
                    .noSupplySchemeIds(List.of(4))
                    .lowSupplyDaysSchemeIds(List.of(5))
                    .lowLpcdSchemeIds(List.of(6))
                    .sectionOfficerSummaries(List.of())
                    .build();
        }

        @Test
        void computesTheKpisAndRepublishesThemForMessageService() {
            when(weeklySituationReportService.buildReport(anyInt(), anyLong(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(weeklyKpis());

            consumer.consumeCommonTopic(VALID_WEEKLY);

            verify(weeklySituationReportService).buildReport(
                    eq(17), eq(11L), eq("SECTION_OFFICER"),
                    eq(LocalDate.of(2026, 2, 23)), eq(LocalDate.of(2026, 3, 1)),
                    eq(LocalDate.of(2026, 2, 16)), eq(LocalDate.of(2026, 2, 22)), any());

            ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
            verify(kafkaProducer).publishJson(eq("common-topic"), published.capture());

            assertThat(published.getValue()).isInstanceOf(WeeklyReportKpisEvent.class);
            WeeklyReportKpisEvent event = (WeeklyReportKpisEvent) published.getValue();
            assertThat(event.getEventType()).isEqualTo("WEEKLY_REPORT_KPIS");
            assertThat(event.getTenantSchema()).isEqualTo("tenant_as");
            assertThat(event.getOfficerUserType()).isEqualTo("SECTION_OFFICER");
            assertThat(event.getCorrelationId()).isEqualTo("corr-9");
            assertThat(event.getKpis().getWeek().getTotalSchemes()).isEqualTo(12);
        }

        @Test
        void forwardsTheSubordinateOfficerListForAnSdoReport() {
            when(weeklySituationReportService.buildReport(anyInt(), anyLong(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(weeklyKpis());

            consumer.consumeCommonTopic(weeklyReportRequest("""
                    "tenantId":17,"tenantSchema":"tenant_as","officerUserId":11,
                    "officerUserType":"SUB_DIVISIONAL_OFFICER","weekStart":"2026-02-23","weekEnd":"2026-03-01",
                    "previousWeekStart":"2026-02-16","previousWeekEnd":"2026-02-22",
                    "subordinateOfficerUserIds":[21,22]
                    """));

            verify(weeklySituationReportService).buildReport(
                    eq(17), eq(11L), eq("SUB_DIVISIONAL_OFFICER"),
                    any(), any(), any(), any(), eq(List.of(21L, 22L)));
        }

        @Test
        void dropsAnEventWithAMalformedWeekRangeRatherThanGuessingIt() {
            // Unlike the daily cut-off there is no safe fallback: a guessed week would deliver a report
            // covering days nobody asked for, and the officer could not tell.
            consumer.consumeCommonTopic(weeklyReportRequest("""
                    "tenantId":17,"tenantSchema":"tenant_as","officerUserId":11,
                    "officerUserType":"SECTION_OFFICER","weekStart":"23-02-2026","weekEnd":"2026-03-01",
                    "previousWeekStart":"2026-02-16","previousWeekEnd":"2026-02-22"
                    """));

            verifyNoInteractions(weeklySituationReportService);
            verifyNoInteractions(kafkaProducer);
        }

        @Test
        void dropsAnEventWithAMissingWeekRange() {
            consumer.consumeCommonTopic(weeklyReportRequest("""
                    "tenantId":17,"tenantSchema":"tenant_as","officerUserId":11,
                    "officerUserType":"SECTION_OFFICER"
                    """));

            verifyNoInteractions(weeklySituationReportService);
            verifyNoInteractions(kafkaProducer);
        }

        @ParameterizedTest(name = "drops the event when {0} is missing")
        @ValueSource(strings = {
                "\"tenantSchema\":\"tenant_as\",\"officerUserId\":11,\"officerUserType\":\"SECTION_OFFICER\"",
                "\"tenantId\":17,\"officerUserId\":11,\"officerUserType\":\"SECTION_OFFICER\"",
                "\"tenantId\":17,\"tenantSchema\":\"tenant_as\",\"officerUserType\":\"SECTION_OFFICER\"",
                "\"tenantId\":17,\"tenantSchema\":\"tenant_as\",\"officerUserId\":11"
        })
        void dropsAnEventMissingARequiredField(String fields) {
            consumer.consumeCommonTopic(weeklyReportRequest(fields
                    + ",\"weekStart\":\"2026-02-23\",\"weekEnd\":\"2026-03-01\""
                    + ",\"previousWeekStart\":\"2026-02-16\",\"previousWeekEnd\":\"2026-02-22\""));

            verifyNoInteractions(weeklySituationReportService);
            verifyNoInteractions(kafkaProducer);
        }

        @Test
        void rethrowsWhenTheComputationFailsSoTheEventIsRedelivered() {
            when(weeklySituationReportService.buildReport(anyInt(), anyLong(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new IllegalStateException("query timed out"));

            assertThatThrownBy(() -> consumer.consumeCommonTopic(VALID_WEEKLY))
                    .isInstanceOf(RuntimeException.class);

            verify(kafkaProducer, never()).publishJson(any(), any());
        }

        @Test
        void handlesASparseKpiPayload() {
            // The completion log reads every optional section; a sparse report must not break it.
            when(weeklySituationReportService.buildReport(anyInt(), anyLong(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(WeeklyReportKpiDTO.builder().build());

            consumer.consumeCommonTopic(VALID_WEEKLY);

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
