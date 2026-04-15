package org.arghyam.jalsoochak.telemetry.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.event.AnomalyEvent;
import org.arghyam.jalsoochak.telemetry.dto.event.EscalationEvent;
import org.arghyam.jalsoochak.telemetry.dto.event.MeterReadingEvent;
import org.arghyam.jalsoochak.telemetry.dto.event.WaterQuantityEvent;
import org.arghyam.jalsoochak.telemetry.kafka.KafkaProducer;
import org.arghyam.jalsoochak.telemetry.service.AnomalyConstants;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class TelemetryEventPublisher {

    public static final String TOPIC = "telemetry-service-topic";
    public static final String ANOMALY_SERVICE_TOPIC = "anomaly-service-topic";
    public static final String EVENT_WATER_QUANTITY_RECORDED = "WATER_QUANTITY_RECORDED";
    public static final String EVENT_METER_READING_RECORDED = "METER_READING_RECORDED";
    public static final String EVENT_ANOMALY_RECORDED = "ANOMALY_RECORDED";
    public static final String EVENT_ESCALATION_CREATED = "ESCALATION_CREATED";
    public static final int NOT_SUBMITTED_STATUS = 0;

    private final KafkaProducer kafkaProducer;

    @Async("kafkaPublisherExecutor")
    public void publishWaterQuantityRecorded(Integer tenantId,
                                             Long schemeId,
                                             Long userId,
                                             LocalDate date,
                                             BigDecimal waterQuantity,
                                             Integer submissionStatus) {
        WaterQuantityEvent event = WaterQuantityEvent.builder()
                .eventType(EVENT_WATER_QUANTITY_RECORDED)
                .tenantId(tenantId)
                .schemeId(toInt(schemeId))
                .userId(toInt(userId))
                .waterQuantity(toInt(waterQuantity))
                .submissionStatus(submissionStatus)
                .outageReason(null)
                .nonSubmissionReason(null)
                .date((date != null ? date : LocalDate.now()).toString())
                .build();

        boolean ok = kafkaProducer.publishJson(TOPIC, event);
        if (!ok) {
            log.warn("[telemetry-events] publish_failed water_quantity tenantId={} schemeId={} userId={} date={}",
                    tenantId, schemeId, userId, date);
        }
    }

    @Async("kafkaPublisherExecutor")
    public void publishOutageOrNonSubmissionReason(Integer tenantId,
                                                   Long schemeId,
                                                   Long userId,
                                                   LocalDate date,
                                                   int anomalyType) {
        ReasonPayload payload = mapReason(anomalyType);
        if (payload == null) {
            return;
        }

        WaterQuantityEvent event = WaterQuantityEvent.builder()
                .eventType(EVENT_WATER_QUANTITY_RECORDED)
                .tenantId(tenantId)
                .schemeId(toInt(schemeId))
                .userId(toInt(userId))
                .waterQuantity(0)
                .submissionStatus(NOT_SUBMITTED_STATUS)
                .outageReason(payload.outageReason)
                .nonSubmissionReason(payload.nonSubmissionReason)
                .date((date != null ? date : LocalDate.now()).toString())
                .build();

        boolean ok = kafkaProducer.publishJson(TOPIC, event);
        if (!ok) {
            log.warn("[telemetry-events] publish_failed type={} tenantId={} schemeId={} userId={}",
                    anomalyType, tenantId, schemeId, userId);
        }
    }

    @Async("kafkaPublisherExecutor")
    public void publishMeterChangeReason(Integer tenantId,
                                         Long schemeId,
                                         Long userId,
                                         LocalDate date,
                                         String meterChangeReason) {
        if (meterChangeReason == null || meterChangeReason.isBlank()) {
            return;
        }

        WaterQuantityEvent event = WaterQuantityEvent.builder()
                .eventType(EVENT_WATER_QUANTITY_RECORDED)
                .tenantId(tenantId)
                .schemeId(toInt(schemeId))
                .userId(toInt(userId))
                .waterQuantity(0)
                .submissionStatus(NOT_SUBMITTED_STATUS)
                .outageReason(null)
                .nonSubmissionReason(meterChangeReason)
                .date((date != null ? date : LocalDate.now()).toString())
                .build();

        boolean ok = kafkaProducer.publishJson(TOPIC, event);
        if (!ok) {
            log.warn("[telemetry-events] publish_failed meter_change tenantId={} schemeId={} userId={}",
                    tenantId, schemeId, userId);
        }
    }

    @Async("kafkaPublisherExecutor")
    public void publishAnomalyRecorded(Integer tenantId,
                                       Integer type,
                                       Long userId,
                                       Long schemeId,
                                       BigDecimal aiReading,
                                       BigDecimal aiConfidencePercentage,
                                       BigDecimal overriddenReading,
                                       Integer retries,
                                       BigDecimal previousReading,
                                       LocalDateTime previousReadingDate,
                                       Integer consecutiveDaysMissed,
                                       String reason,
                                       Integer status,
                                       String correlationId) {
        String eventUuid = resolveAnomalyEventUuid(correlationId, userId);
        AnomalyEvent event = AnomalyEvent.builder()
                .eventType(EVENT_ANOMALY_RECORDED)
                .uuid(eventUuid)
                .tenantId(tenantId)
                .type(type)
                .userId(toInt(userId))
                .schemeId(toInt(schemeId))
                .aiReading(aiReading)
                .aiConfidencePercentage(aiConfidencePercentage)
                .overriddenReading(overriddenReading)
                .retries(retries)
                .previousReading(previousReading)
                .previousReadingDate(previousReadingDate != null ? previousReadingDate.toLocalDate() : null)
                .consecutiveDaysMissed(consecutiveDaysMissed)
                .reason(reason)
                .status(status)
                .correlationId(correlationId)
                .build();

        boolean ok = kafkaProducer.publishJson(TOPIC, event);
        if (!ok) {
            log.warn("[telemetry-events] publish_failed type={} tenantId={} schemeId={} userId={}",
                    type, tenantId, schemeId, userId);
        }
    }

    @Async("kafkaPublisherExecutor")
    public void publishEscalationCreated(Integer tenantId,
                                         Long schemeId,
                                         Long userId,
                                         Integer escalationType,
                                         String message,
                                         String correlationId,
                                         Integer resolutionStatus,
                                         String remark) {
        EscalationEvent event = EscalationEvent.builder()
                .eventType(EVENT_ESCALATION_CREATED)
                .tenantId(tenantId)
                .schemeId(toInt(schemeId))
                .escalationType(escalationType)
                .message(message)
                .correlationId(correlationId)
                .userId(toInt(userId))
                .resolutionStatus(resolutionStatus)
                .remark(remark)
                .build();

        boolean ok = kafkaProducer.publishJson(ANOMALY_SERVICE_TOPIC, event);
        if (!ok) {
            log.warn("[telemetry-events] publish_failed escalation_created type={} tenantId={} schemeId={} userId={}",
                    escalationType, tenantId, schemeId, userId);
        }
    }

    @Async("kafkaPublisherExecutor")
    public void publishMeterReadingRecorded(Integer tenantId,
                                            Long schemeId,
                                            Long userId,
                                            BigDecimal extractedReading,
                                            BigDecimal confirmedReading,
                                            BigDecimal confidence,
                                            String imageUrl,
                                            LocalDateTime readingAt,
                                            Integer channel,
                                            LocalDate readingDate,
                                            Integer submissionStatus,
                                            Integer readingType) {
        LocalDate effectiveDate = readingDate != null ? readingDate : (readingAt != null ? readingAt.toLocalDate() : null);
        MeterReadingEvent event = MeterReadingEvent.builder()
                .eventType(EVENT_METER_READING_RECORDED)
                .tenantId(tenantId)
                .schemeId(toInt(schemeId))
                .userId(toInt(userId))
                .extractedReading(toInt(extractedReading))
                .confirmedReading(toInt(confirmedReading))
                .confidence(toConfidenceInt(confidence))
                .imageUrl(imageUrl)
                .readingAt(readingAt != null ? readingAt.toString() : null)
                .channel(channel)
                .readingDate(effectiveDate != null ? effectiveDate.toString() : null)
                .submissionStatus(submissionStatus)
                .readingType(readingType)
                .build();

        boolean ok = kafkaProducer.publishJson(TOPIC, event);
        if (!ok) {
            log.warn("[telemetry-events] publish_failed meter_reading tenantId={} schemeId={} userId={}",
                    tenantId, schemeId, userId);
        }
    }

    private static ReasonPayload mapReason(int anomalyType) {
        if (anomalyType == AnomalyConstants.TYPE_NO_WATER_SUPPLY) {
            return new ReasonPayload("No Water Supply", null);
        }
        if (anomalyType == AnomalyConstants.TYPE_LOW_WATER_SUPPLY) {
            return new ReasonPayload("Low Water Supply", null);
        }
        if (anomalyType == AnomalyConstants.TYPE_NO_SUBMISSION) {
            return new ReasonPayload(null, "No Submission");
        }
        return null;
    }

    private static Integer toInt(Long value) {
        return value == null ? null : value.intValue();
    }

    private static Integer toInt(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return value.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private static Integer toConfidenceInt(BigDecimal value) {
        if (value == null) {
            return null;
        }
        if (value.compareTo(BigDecimal.ZERO) < 0) {
            return null;
        }
        if (value.compareTo(BigDecimal.ONE) <= 0) {
            return value.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
        }
        return value.setScale(0, RoundingMode.HALF_UP).intValue();
    }

    private static String resolveAnomalyEventUuid(String correlationId, Long userId) {
        if (correlationId == null || correlationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        if (userId == null) {
            return correlationId;
        }
        String key = correlationId + ":" + userId;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private record ReasonPayload(String outageReason, String nonSubmissionReason) {
    }
}
