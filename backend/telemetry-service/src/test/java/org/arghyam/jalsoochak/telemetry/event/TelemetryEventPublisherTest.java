package org.arghyam.jalsoochak.telemetry.event;

import org.arghyam.jalsoochak.telemetry.dto.event.AnomalyEvent;
import org.arghyam.jalsoochak.telemetry.dto.event.EscalationEvent;
import org.arghyam.jalsoochak.telemetry.dto.event.MeterReadingEvent;
import org.arghyam.jalsoochak.telemetry.dto.event.SubmissionRejectedEvent;
import org.arghyam.jalsoochak.telemetry.dto.event.WaterQuantityEvent;
import org.arghyam.jalsoochak.telemetry.kafka.KafkaProducer;
import org.arghyam.jalsoochak.telemetry.service.AnomalyConstants;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Kafka events telemetry emits for analytics and anomaly consumers.
 *
 * <p>These payloads are a cross-service contract, so the tests pin the concrete field values —
 * particularly the {@code BigDecimal → Integer} narrowing and the confidence scale, where a silent
 * change would corrupt downstream dashboards rather than fail loudly.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TelemetryEventPublisher")
class TelemetryEventPublisherTest {

    private static final String TOPIC = "telemetry-service-topic";
    private static final String ANOMALY_TOPIC = "anomaly-service-topic";
    private static final LocalDate DATE = LocalDate.of(2026, 3, 1);

    @Mock
    private KafkaProducer kafkaProducer;

    @InjectMocks
    private TelemetryEventPublisher publisher;

    private <T> T publishedTo(String topic, Class<T> type) {
        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(kafkaProducer).publishJson(eq(topic), event.capture());
        assertThat(event.getValue()).isInstanceOf(type);
        return type.cast(event.getValue());
    }

    @Nested
    @DisplayName("water quantity")
    class WaterQuantity {

        @Test
        void publishesTheRecordedQuantityForTheDay() {
            publisher.publishWaterQuantityRecorded(17, 7L, 11L, DATE, new BigDecimal("150"), 1);

            WaterQuantityEvent event = publishedTo(TOPIC, WaterQuantityEvent.class);
            assertThat(event.getEventType()).isEqualTo("WATER_QUANTITY_RECORDED");
            assertThat(event.getTenantId()).isEqualTo(17);
            assertThat(event.getSchemeId()).isEqualTo(7);
            assertThat(event.getUserId()).isEqualTo(11);
            assertThat(event.getWaterQuantity()).isEqualTo(150);
            assertThat(event.getSubmissionStatus()).isEqualTo(1);
            assertThat(event.getDate()).isEqualTo("2026-03-01");
        }

        @Test
        void roundsAFractionalQuantityHalfUp() {
            publisher.publishWaterQuantityRecorded(17, 7L, 11L, DATE, new BigDecimal("150.5"), 1);

            assertThat(publishedTo(TOPIC, WaterQuantityEvent.class).getWaterQuantity()).isEqualTo(151);
        }

        @Test
        void defaultsToTodayWhenNoDateIsGiven() {
            publisher.publishWaterQuantityRecorded(17, 7L, 11L, null, BigDecimal.TEN, 1);

            assertThat(publishedTo(TOPIC, WaterQuantityEvent.class).getDate())
                    .isEqualTo(ReadingTime.today().toString());
        }

        @Test
        void carriesNullsThroughForAbsentIdentifiers() {
            publisher.publishWaterQuantityRecorded(null, null, null, DATE, null, null);

            WaterQuantityEvent event = publishedTo(TOPIC, WaterQuantityEvent.class);
            assertThat(event.getSchemeId()).isNull();
            assertThat(event.getUserId()).isNull();
            assertThat(event.getWaterQuantity()).isNull();
        }

        @Test
        void logsRatherThanThrowsWhenThePublishFails() {
            when(kafkaProducer.publishJson(anyString(), any())).thenReturn(false);

            publisher.publishWaterQuantityRecorded(17, 7L, 11L, DATE, BigDecimal.TEN, 1);

            verify(kafkaProducer).publishJson(eq(TOPIC), any());
        }
    }

    @Nested
    @DisplayName("outage and non-submission reasons")
    class Reasons {

        @ParameterizedTest(name = "type={0} default reason \"{1}\"")
        @CsvSource({
                "6,No Water Supply",   // AnomalyConstants.TYPE_NO_WATER_SUPPLY
                "7,Low Water Supply"   // AnomalyConstants.TYPE_LOW_WATER_SUPPLY
        })
        void mapsSupplyAnomaliesToAnOutageReason(int anomalyType, String defaultReason) {
            publisher.publishOutageOrNonSubmissionReason(17, 7L, 11L, DATE, anomalyType, null);

            WaterQuantityEvent event = publishedTo(TOPIC, WaterQuantityEvent.class);
            assertThat(event.getOutageReason()).isEqualTo(defaultReason);
            assertThat(event.getNonSubmissionReason()).isNull();
            assertThat(event.getWaterQuantity()).isZero();
            assertThat(event.getSubmissionStatus()).isEqualTo(TelemetryEventPublisher.NOT_SUBMITTED_STATUS);
        }

        @Test
        void mapsNoSubmissionToTheNonSubmissionReason() {
            publisher.publishOutageOrNonSubmissionReason(
                    17, 7L, 11L, DATE, AnomalyConstants.TYPE_NO_SUBMISSION, null);

            WaterQuantityEvent event = publishedTo(TOPIC, WaterQuantityEvent.class);
            assertThat(event.getNonSubmissionReason()).isEqualTo("No Submission");
            assertThat(event.getOutageReason()).isNull();
        }

        @Test
        void prefersTheOperatorSelectedReasonOverTheDefault() {
            publisher.publishOutageOrNonSubmissionReason(
                    17, 7L, 11L, DATE, AnomalyConstants.TYPE_NO_WATER_SUPPLY, "  Pump failure  ");

            assertThat(publishedTo(TOPIC, WaterQuantityEvent.class).getOutageReason())
                    .isEqualTo("Pump failure");
        }

        @Test
        void fallsBackToTheDefaultForABlankSelectedReason() {
            publisher.publishOutageOrNonSubmissionReason(
                    17, 7L, 11L, DATE, AnomalyConstants.TYPE_NO_WATER_SUPPLY, "   ");

            assertThat(publishedTo(TOPIC, WaterQuantityEvent.class).getOutageReason())
                    .isEqualTo("No Water Supply");
        }

        @Test
        void publishesNothingForAnUnmappedAnomalyType() {
            publisher.publishOutageOrNonSubmissionReason(17, 7L, 11L, DATE, 9999, "whatever");

            verify(kafkaProducer, never()).publishJson(anyString(), any());
        }
    }

    @Nested
    @DisplayName("meter change reason")
    class MeterChange {

        @Test
        void publishesTheReasonAsANonSubmission() {
            publisher.publishMeterChangeReason(17, 7L, 11L, DATE, "Meter replaced");

            WaterQuantityEvent event = publishedTo(TOPIC, WaterQuantityEvent.class);
            assertThat(event.getNonSubmissionReason()).isEqualTo("Meter replaced");
            assertThat(event.getWaterQuantity()).isZero();
        }

        @Test
        void publishesNothingForAMissingReason() {
            publisher.publishMeterChangeReason(17, 7L, 11L, DATE, null);
            publisher.publishMeterChangeReason(17, 7L, 11L, DATE, "  ");

            verify(kafkaProducer, never()).publishJson(anyString(), any());
        }

        @Test
        void defaultsToTodayWhenNoDateIsGiven() {
            publisher.publishMeterChangeReason(17, 7L, 11L, null, "Meter replaced");

            assertThat(publishedTo(TOPIC, WaterQuantityEvent.class).getDate())
                    .isEqualTo(ReadingTime.today().toString());
        }
    }

    @Nested
    @DisplayName("anomaly")
    class Anomaly {

        @Test
        void publishesEveryAnomalyField() {
            publisher.publishAnomalyRecorded(17, 3, 11L, 7L,
                    new BigDecimal("120.4"), new BigDecimal("0.85"), new BigDecimal("130"),
                    2, new BigDecimal("100"), LocalDateTime.of(2026, 2, 28, 6, 0),
                    4, "Low confidence", 0, "corr-1");

            AnomalyEvent event = publishedTo(TOPIC, AnomalyEvent.class);
            assertThat(event.getEventType()).isEqualTo("ANOMALY_RECORDED");
            assertThat(event.getTenantId()).isEqualTo(17);
            assertThat(event.getType()).isEqualTo(3);
            assertThat(event.getUserId()).isEqualTo(11);
            assertThat(event.getSchemeId()).isEqualTo(7);
            assertThat(event.getAiReading()).isEqualByComparingTo("120.4");
            assertThat(event.getRetries()).isEqualTo(2);
            assertThat(event.getPreviousReadingDate()).isEqualTo(LocalDate.of(2026, 2, 28));
            assertThat(event.getConsecutiveDaysMissed()).isEqualTo(4);
            assertThat(event.getReason()).isEqualTo("Low confidence");
            assertThat(event.getCorrelationId()).isEqualTo("corr-1");
        }

        @Test
        void derivesAStableUuidFromTheCorrelationIdAndUser() {
            publisher.publishAnomalyRecorded(17, 3, 11L, 7L, null, null, null, null, null, null,
                    null, null, 0, "corr-1");
            String first = publishedTo(TOPIC, AnomalyEvent.class).getUuid();

            org.mockito.Mockito.reset(kafkaProducer);
            publisher.publishAnomalyRecorded(17, 3, 11L, 7L, null, null, null, null, null, null,
                    null, null, 0, "corr-1");
            String second = publishedTo(TOPIC, AnomalyEvent.class).getUuid();

            // Deduplication downstream depends on the same submission producing the same uuid.
            assertThat(first).isEqualTo(second);
        }

        @Test
        void derivesDifferentUuidsForDifferentUsersOnTheSameCorrelationId() {
            publisher.publishAnomalyRecorded(17, 3, 11L, 7L, null, null, null, null, null, null,
                    null, null, 0, "corr-1");
            String first = publishedTo(TOPIC, AnomalyEvent.class).getUuid();

            org.mockito.Mockito.reset(kafkaProducer);
            publisher.publishAnomalyRecorded(17, 3, 22L, 7L, null, null, null, null, null, null,
                    null, null, 0, "corr-1");

            assertThat(publishedTo(TOPIC, AnomalyEvent.class).getUuid()).isNotEqualTo(first);
        }

        @Test
        void usesTheCorrelationIdVerbatimWhenThereIsNoUser() {
            publisher.publishAnomalyRecorded(17, 3, null, 7L, null, null, null, null, null, null,
                    null, null, 0, "corr-1");

            assertThat(publishedTo(TOPIC, AnomalyEvent.class).getUuid()).isEqualTo("corr-1");
        }

        @Test
        void generatesARandomUuidWhenThereIsNoCorrelationId() {
            publisher.publishAnomalyRecorded(17, 3, 11L, 7L, null, null, null, null, null, null,
                    null, null, 0, null);
            String first = publishedTo(TOPIC, AnomalyEvent.class).getUuid();

            org.mockito.Mockito.reset(kafkaProducer);
            publisher.publishAnomalyRecorded(17, 3, 11L, 7L, null, null, null, null, null, null,
                    null, null, 0, "  ");

            assertThat(first).isNotBlank();
            assertThat(publishedTo(TOPIC, AnomalyEvent.class).getUuid()).isNotEqualTo(first);
        }

        @Test
        void leavesThePreviousReadingDateNullWhenAbsent() {
            publisher.publishAnomalyRecorded(17, 3, 11L, 7L, null, null, null, null, null, null,
                    null, null, 0, "corr-1");

            assertThat(publishedTo(TOPIC, AnomalyEvent.class).getPreviousReadingDate()).isNull();
        }
    }

    @Nested
    @DisplayName("escalation")
    class Escalation {

        @Test
        void publishesToTheAnomalyServiceTopicRatherThanTheTelemetryTopic() {
            publisher.publishEscalationCreated(17, 7L, 11L, 2, "Escalated", "corr-1", 0, "remark");

            EscalationEvent event = publishedTo(ANOMALY_TOPIC, EscalationEvent.class);
            assertThat(event.getEventType()).isEqualTo("ESCALATION_CREATED");
            assertThat(event.getTenantId()).isEqualTo(17);
            assertThat(event.getSchemeId()).isEqualTo(7);
            assertThat(event.getUserId()).isEqualTo(11);
            assertThat(event.getEscalationType()).isEqualTo(2);
            assertThat(event.getMessage()).isEqualTo("Escalated");
            assertThat(event.getCorrelationId()).isEqualTo("corr-1");
            assertThat(event.getResolutionStatus()).isZero();
            assertThat(event.getRemark()).isEqualTo("remark");
        }

        @Test
        void logsRatherThanThrowsWhenThePublishFails() {
            when(kafkaProducer.publishJson(anyString(), any())).thenReturn(false);

            publisher.publishEscalationCreated(17, 7L, 11L, 2, "Escalated", "corr-1", 0, "remark");

            verify(kafkaProducer).publishJson(eq(ANOMALY_TOPIC), any());
        }
    }

    @Nested
    @DisplayName("meter reading")
    class MeterReading {

        @Test
        void publishesTheReadingWithItsDerivedDate() {
            publisher.publishMeterReadingRecorded(17, 7L, 11L,
                    new BigDecimal("1234"), new BigDecimal("1234"), new BigDecimal("0.92"),
                    "https://minio/img.jpg", LocalDateTime.of(2026, 3, 1, 6, 30), 1, DATE, 1, 0);

            MeterReadingEvent event = publishedTo(TOPIC, MeterReadingEvent.class);
            assertThat(event.getEventType()).isEqualTo("METER_READING_RECORDED");
            assertThat(event.getExtractedReading()).isEqualTo(1234);
            assertThat(event.getConfirmedReading()).isEqualTo(1234);
            assertThat(event.getImageUrl()).isEqualTo("https://minio/img.jpg");
            assertThat(event.getReadingAt()).isEqualTo("2026-03-01T06:30");
            assertThat(event.getChannel()).isEqualTo(1);
            assertThat(event.getReadingDate()).isEqualTo("2026-03-01");
        }

        @Test
        void fallsBackToTheReadingTimestampsDateWhenNoReadingDateIsGiven() {
            publisher.publishMeterReadingRecorded(17, 7L, 11L, BigDecimal.TEN, BigDecimal.TEN, null,
                    null, LocalDateTime.of(2026, 3, 1, 6, 30), 1, null, 1, 0);

            assertThat(publishedTo(TOPIC, MeterReadingEvent.class).getReadingDate()).isEqualTo("2026-03-01");
        }

        @Test
        void leavesTheDateNullWhenNeitherIsGiven() {
            publisher.publishMeterReadingRecorded(17, 7L, 11L, BigDecimal.TEN, BigDecimal.TEN, null,
                    null, null, 1, null, 1, 0);

            MeterReadingEvent event = publishedTo(TOPIC, MeterReadingEvent.class);
            assertThat(event.getReadingDate()).isNull();
            assertThat(event.getReadingAt()).isNull();
        }

        @ParameterizedTest(name = "confidence {0} -> {1}")
        @CsvSource({
                "0.92,92",     // fractional model confidence is scaled to a percentage
                "1,100",       // the 0..1 upper bound is still treated as a fraction
                "0,0",
                "85,85",       // an already-percentage value is passed through
                "84.6,85"      // and rounded half up
        })
        void normalisesModelConfidenceToAWholePercentage(String confidence, int expected) {
            publisher.publishMeterReadingRecorded(17, 7L, 11L, BigDecimal.TEN, BigDecimal.TEN,
                    new BigDecimal(confidence), null, null, 1, DATE, 1, 0);

            assertThat(publishedTo(TOPIC, MeterReadingEvent.class).getConfidence()).isEqualTo(expected);
        }

        @Test
        void treatsAMissingOrNegativeConfidenceAsUnknown() {
            publisher.publishMeterReadingRecorded(17, 7L, 11L, BigDecimal.TEN, BigDecimal.TEN,
                    null, null, null, 1, DATE, 1, 0);
            assertThat(publishedTo(TOPIC, MeterReadingEvent.class).getConfidence()).isNull();

            org.mockito.Mockito.reset(kafkaProducer);
            publisher.publishMeterReadingRecorded(17, 7L, 11L, BigDecimal.TEN, BigDecimal.TEN,
                    new BigDecimal("-1"), null, null, 1, DATE, 1, 0);
            assertThat(publishedTo(TOPIC, MeterReadingEvent.class).getConfidence()).isNull();
        }
    }

    @Nested
    @DisplayName("submission rejected")
    class SubmissionRejected {

        @Test
        void publishesTheRejectedSubmissionForReportedCounts() {
            publisher.publishSubmissionRejected(17, "S-1", "C-1", "hash", "invalid api key");

            SubmissionRejectedEvent event = publishedTo(TOPIC, SubmissionRejectedEvent.class);
            assertThat(event.getEventType()).isEqualTo("SUBMISSION_REJECTED");
            assertThat(event.getTenantId()).isEqualTo(17);
            assertThat(event.getSubmittedStateSchemeId()).isEqualTo("S-1");
            assertThat(event.getSubmittedCentreSchemeId()).isEqualTo("C-1");
            assertThat(event.getSubmittedPhoneHash()).isEqualTo("hash");
            assertThat(event.getReason()).isEqualTo("invalid api key");
        }

        @Test
        void stampsTheAttemptInUtcSoAnalyticsCanDeriveTheIstDay() {
            LocalDateTime before = LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1);
            publisher.publishSubmissionRejected(17, "S-1", "C-1", "hash", "invalid api key");
            LocalDateTime after = LocalDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(1);

            String attemptedAt = publishedTo(TOPIC, SubmissionRejectedEvent.class).getAttemptedAt();

            assertThat(attemptedAt).isNotNull();
            // A local-time stamp would drift by the +05:30 IST offset and land analytics on the wrong day.
            assertThat(LocalDateTime.parse(attemptedAt)).isBetween(before, after);
        }

        @Test
        void carriesANullHashWhenThePhoneCouldNotBeHashed() {
            publisher.publishSubmissionRejected(17, "S-1", "C-1", null, "invalid api key");

            assertThat(publishedTo(TOPIC, SubmissionRejectedEvent.class).getSubmittedPhoneHash()).isNull();
        }

        @Test
        void logsRatherThanThrowsWhenThePublishFails() {
            when(kafkaProducer.publishJson(anyString(), any())).thenReturn(false);

            publisher.publishSubmissionRejected(17, "S-1", "C-1", "hash", "invalid api key");

            verify(kafkaProducer).publishJson(eq(TOPIC), any());
        }
    }
}
