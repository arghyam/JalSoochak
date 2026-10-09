package org.arghyam.jalsoochak.telemetry.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.event.AnomalyEvent;
import org.arghyam.jalsoochak.telemetry.dto.event.CalculationParameters;
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
import java.util.List;

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
                    4, "Low confidence", 0, "corr-1", "submission-corr-1");

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
            assertThat(event.getSubmissionCorrelationId()).isEqualTo("submission-corr-1");
        }

        @Test
        @DisplayName("ANOMALY-SUBMISSION-LINK: the submission link is carried without touching the dedup key")
        void carriesTheSubmissionLinkSeparatelyFromTheDedupKey() {
            publisher.publishAnomalyRecorded(17, 10, 11L, 7L, null, null, null, null, null, null,
                    null, "Implausible supply", 1, "dedup-key", "flow-corr-9");
            AnomalyEvent event = publishedTo(TOPIC, AnomalyEvent.class);

            assertThat(event.getSubmissionCorrelationId()).isEqualTo("flow-corr-9");
            // The uuid analytics dedups on must still come from correlationId alone: if the
            // submission link fed it, two submissions on one day would stop collapsing.
            assertThat(event.getCorrelationId()).isEqualTo("dedup-key");
            org.mockito.Mockito.reset(kafkaProducer);
            publisher.publishAnomalyRecorded(17, 10, 11L, 7L, null, null, null, null, null, null,
                    null, "Implausible supply", 1, "dedup-key", "a-different-submission");
            assertThat(publishedTo(TOPIC, AnomalyEvent.class).getUuid()).isEqualTo(event.getUuid());
        }

        @Test
        @DisplayName("ANOMALY-SUBMISSION-LINK: a type with no submission leaves the link null")
        void leavesTheSubmissionLinkNullWhenThereIsNoSubmission() {
            publisher.publishAnomalyRecorded(17, 9, 11L, 7L, null, null, null, null, null, null,
                    null, "Meter not working", 1, "corr-1", null);

            assertThat(publishedTo(TOPIC, AnomalyEvent.class).getSubmissionCorrelationId()).isNull();
        }

        @Test
        void derivesAStableUuidFromTheCorrelationIdAndUser() {
            publisher.publishAnomalyRecorded(17, 3, 11L, 7L, null, null, null, null, null, null,
                    null, null, 0, "corr-1", null);
            String first = publishedTo(TOPIC, AnomalyEvent.class).getUuid();

            org.mockito.Mockito.reset(kafkaProducer);
            publisher.publishAnomalyRecorded(17, 3, 11L, 7L, null, null, null, null, null, null,
                    null, null, 0, "corr-1", null);
            String second = publishedTo(TOPIC, AnomalyEvent.class).getUuid();

            // Deduplication downstream depends on the same submission producing the same uuid.
            assertThat(first).isEqualTo(second);
        }

        @Test
        void derivesDifferentUuidsForDifferentUsersOnTheSameCorrelationId() {
            publisher.publishAnomalyRecorded(17, 3, 11L, 7L, null, null, null, null, null, null,
                    null, null, 0, "corr-1", null);
            String first = publishedTo(TOPIC, AnomalyEvent.class).getUuid();

            org.mockito.Mockito.reset(kafkaProducer);
            publisher.publishAnomalyRecorded(17, 3, 22L, 7L, null, null, null, null, null, null,
                    null, null, 0, "corr-1", null);

            assertThat(publishedTo(TOPIC, AnomalyEvent.class).getUuid()).isNotEqualTo(first);
        }

        @Test
        void usesTheCorrelationIdVerbatimWhenThereIsNoUser() {
            publisher.publishAnomalyRecorded(17, 3, null, 7L, null, null, null, null, null, null,
                    null, null, 0, "corr-1", null);

            assertThat(publishedTo(TOPIC, AnomalyEvent.class).getUuid()).isEqualTo("corr-1");
        }

        @Test
        void generatesARandomUuidWhenThereIsNoCorrelationId() {
            publisher.publishAnomalyRecorded(17, 3, 11L, 7L, null, null, null, null, null, null,
                    null, null, 0, null, null);
            String first = publishedTo(TOPIC, AnomalyEvent.class).getUuid();

            org.mockito.Mockito.reset(kafkaProducer);
            publisher.publishAnomalyRecorded(17, 3, 11L, 7L, null, null, null, null, null, null,
                    null, null, 0, "  ", null);

            assertThat(first).isNotBlank();
            assertThat(publishedTo(TOPIC, AnomalyEvent.class).getUuid()).isNotEqualTo(first);
        }

        @Test
        void leavesThePreviousReadingDateNullWhenAbsent() {
            publisher.publishAnomalyRecorded(17, 3, 11L, 7L, null, null, null, null, null, null,
                    null, null, 0, "corr-1", null);

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
                    "https://storage.example.org/img.jpg", LocalDateTime.of(2026, 3, 1, 6, 30), 1, DATE, 1, 0, "flow-corr-1",
                    99L, LocalDateTime.of(2026, 3, 1, 6, 31, 5, 123_456_000), null);

            MeterReadingEvent event = publishedTo(TOPIC, MeterReadingEvent.class);
            assertThat(event.getEventType()).isEqualTo("METER_READING_RECORDED");
            assertThat(event.getExtractedReading()).isEqualByComparingTo("1234");
            assertThat(event.getConfirmedReading()).isEqualByComparingTo("1234");
            assertThat(event.getImageUrl()).isEqualTo("https://storage.example.org/img.jpg");
            assertThat(event.getReadingAt()).isEqualTo("2026-03-01T06:30");
            assertThat(event.getChannel()).isEqualTo(1);
            assertThat(event.getReadingDate()).isEqualTo("2026-03-01");
            // ANOMALY-SUBMISSION-LINK: the fact row's join counterpart for an anomaly over the same
            // submission. Previously the event carried no correlation id at all, so the warehouse
            // had nothing to match an anomaly against.
            assertThat(event.getCorrelationId()).isEqualTo("flow-corr-1");
            // The submission's identity and version, which analytics keys its one fact row on. The
            // version keeps the database's microseconds, so two writes in one second still order.
            assertThat(event.getSourceReadingId()).isEqualTo(99L);
            assertThat(event.getSourceUpdatedAt()).isEqualTo("2026-03-01T06:31:05.123456");
        }

        /**
         * Analytics reads the snapshot into its own copy of the record, so the field names are the
         * contract. The values go through exactly as stored.
         */
        @Test
        void carriesTheCalculationParametersUnderTheContractsNames() throws Exception {
            CalculationParameters snapshot = new CalculationParameters(1, "F2", new BigDecimal("0.95"), List.of(
                    new CalculationParameters.Pump(12L, new BigDecimal("500"), new BigDecimal("0.7"),
                            new BigDecimal("40"), new BigDecimal("7.5"), "HP", new BigDecimal("0.85"),
                            new BigDecimal("5"), new BigDecimal("0.9"))));

            publisher.publishMeterReadingRecorded(17, 7L, 11L, BigDecimal.TEN, BigDecimal.TEN, null,
                    null, LocalDateTime.of(2026, 3, 1, 6, 30), 2, DATE, 1, 0, null, 99L, null, snapshot);

            MeterReadingEvent event = publishedTo(TOPIC, MeterReadingEvent.class);
            assertThat(event.getCalculationParameters()).isEqualTo(snapshot);
            JsonNode json = new ObjectMapper().valueToTree(event).get("calculationParameters");
            assertThat(json.get("version").asInt()).isEqualTo(1);
            assertThat(json.get("elmFormula").asText()).isEqualTo("F2");
            assertThat(json.get("kFactor").decimalValue()).isEqualByComparingTo("0.95");
            JsonNode pump = json.get("pumps").get(0);
            assertThat(pump.get("pumpId").asLong()).isEqualTo(12L);
            assertThat(pump.get("pumpDischargeCapacityLpm").decimalValue()).isEqualByComparingTo("500");
            assertThat(pump.get("pumpEfficiency").decimalValue()).isEqualByComparingTo("0.7");
            assertThat(pump.get("pumpHeadM").decimalValue()).isEqualByComparingTo("40");
            assertThat(pump.get("motorPower").decimalValue()).isEqualByComparingTo("7.5");
            assertThat(pump.get("motorPowerUnit").asText()).isEqualTo("HP");
            assertThat(pump.get("motorEfficiency").decimalValue()).isEqualByComparingTo("0.85");
            assertThat(pump.get("unitsConsumedPerHour").decimalValue()).isEqualByComparingTo("5");
            assertThat(pump.get("powerFactor").decimalValue()).isEqualByComparingTo("0.9");
        }

        @Test
        void leavesTheVersionNullWhenItIsNotKnown() {
            publisher.publishMeterReadingRecorded(17, 7L, 11L, BigDecimal.TEN, BigDecimal.TEN, null,
                    null, LocalDateTime.of(2026, 3, 1, 6, 30), 1, DATE, 1, 0, null, 99L, null, null);

            assertThat(publishedTo(TOPIC, MeterReadingEvent.class).getSourceUpdatedAt()).isNull();
        }

        @Test
        void fallsBackToTheReadingTimestampsDateWhenNoReadingDateIsGiven() {
            publisher.publishMeterReadingRecorded(17, 7L, 11L, BigDecimal.TEN, BigDecimal.TEN, null,
                    null, LocalDateTime.of(2026, 3, 1, 6, 30), 1, null, 1, 0, null, null, null, null);

            assertThat(publishedTo(TOPIC, MeterReadingEvent.class).getReadingDate()).isEqualTo("2026-03-01");
        }

        @Test
        void publishesTheMetersDecimalDigitRatherThanRoundingItAway() {
            // flow_reading_table holds these as NUMERIC and the meters genuinely read to a tenth of a
            // m3. Rounding here cost up to 0.5 m3 per reading, i.e. up to 1000 L on the daily delta
            // analytics derives from two of them.
            publisher.publishMeterReadingRecorded(17, 7L, 11L,
                    new BigDecimal("1247.8"), new BigDecimal("1235.55"), null,
                    null, LocalDateTime.of(2026, 3, 1, 6, 30), 1, DATE, 1, 0, null, null, null, null);

            MeterReadingEvent event = publishedTo(TOPIC, MeterReadingEvent.class);
            assertThat(event.getExtractedReading()).isEqualByComparingTo("1247.8");
            assertThat(event.getConfirmedReading()).isEqualByComparingTo("1235.55");
        }

        @Test
        void carriesNullReadingsThrough() {
            publisher.publishMeterReadingRecorded(17, 7L, 11L, null, null, null,
                    null, LocalDateTime.of(2026, 3, 1, 6, 30), 1, DATE, 1, 0, null, null, null, null);

            MeterReadingEvent event = publishedTo(TOPIC, MeterReadingEvent.class);
            assertThat(event.getExtractedReading()).isNull();
            assertThat(event.getConfirmedReading()).isNull();
        }

        @Test
        void leavesTheDateNullWhenNeitherIsGiven() {
            publisher.publishMeterReadingRecorded(17, 7L, 11L, BigDecimal.TEN, BigDecimal.TEN, null,
                    null, null, 1, null, 1, 0, null, null, null, null);

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
                    new BigDecimal(confidence), null, null, 1, DATE, 1, 0, null, null, null, null);

            assertThat(publishedTo(TOPIC, MeterReadingEvent.class).getConfidence()).isEqualTo(expected);
        }

        @Test
        void treatsAMissingOrNegativeConfidenceAsUnknown() {
            publisher.publishMeterReadingRecorded(17, 7L, 11L, BigDecimal.TEN, BigDecimal.TEN,
                    null, null, null, 1, DATE, 1, 0, null, null, null, null);
            assertThat(publishedTo(TOPIC, MeterReadingEvent.class).getConfidence()).isNull();

            org.mockito.Mockito.reset(kafkaProducer);
            publisher.publishMeterReadingRecorded(17, 7L, 11L, BigDecimal.TEN, BigDecimal.TEN,
                    new BigDecimal("-1"), null, null, 1, DATE, 1, 0, null, null, null, null);
            assertThat(publishedTo(TOPIC, MeterReadingEvent.class).getConfidence()).isNull();
        }

        @Test
        void publishesAPrebuiltEventAsItIs() {
            MeterReadingEvent event = TelemetryEventPublisher.meterReadingRecordedEvent(17, 7L, 11L,
                    BigDecimal.TEN, BigDecimal.TEN, null, null, LocalDateTime.of(2026, 3, 1, 6, 30), 2, DATE,
                    1, 0, null, 99L, null, null);

            publisher.publishMeterReadingRecorded(event);

            assertThat(publishedTo(TOPIC, MeterReadingEvent.class)).isSameAs(event);
        }

        /** A run of publishes waits for each acknowledgement instead of queuing on the shared executor. */
        @Test
        void waitsForTheAcknowledgementAndReportsIt() {
            MeterReadingEvent event = TelemetryEventPublisher.meterReadingRecordedEvent(17, 7L, 11L,
                    BigDecimal.TEN, BigDecimal.TEN, null, null, LocalDateTime.of(2026, 3, 1, 6, 30), 2, DATE,
                    1, 0, null, 99L, null, null);
            when(kafkaProducer.publishJsonAndAwait(TOPIC, event, TelemetryEventPublisher.ACKNOWLEDGEMENT_TIMEOUT))
                    .thenReturn(true, false);

            assertThat(publisher.publishMeterReadingRecordedAndAwait(event)).isTrue();
            assertThat(publisher.publishMeterReadingRecordedAndAwait(event)).isFalse();
            verify(kafkaProducer, never()).publishJson(anyString(), any());
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
