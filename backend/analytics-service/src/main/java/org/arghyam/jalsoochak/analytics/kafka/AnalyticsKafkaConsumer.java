package org.arghyam.jalsoochak.analytics.kafka;

import org.arghyam.jalsoochak.analytics.dto.event.DepartmentLocationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.EscalationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.IncludedWorkStatusesUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.LgdLocationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.MeterReadingEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SchemeEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SchemePerformanceEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SubmissionRejectedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.TenantEscalationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.TenantEvent;
import org.arghyam.jalsoochak.analytics.dto.event.TenantLocationHierarchyUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.UserEvent;
import org.arghyam.jalsoochak.analytics.dto.event.UserSchemeMappingsReplacedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterNormUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterQuantityEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterSupplyThresholdUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.AnomalyEvent;
import org.arghyam.jalsoochak.analytics.dto.event.DailyReportRequestEvent;
import org.arghyam.jalsoochak.analytics.dto.event.DailyReportKpisEvent;
import org.arghyam.jalsoochak.analytics.dto.DailyReportKpiDTO;
import org.arghyam.jalsoochak.analytics.service.DimensionService;
import org.arghyam.jalsoochak.analytics.service.DailySituationReportService;
import org.arghyam.jalsoochak.analytics.service.FactService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@Component
@RequiredArgsConstructor
@Slf4j
public class AnalyticsKafkaConsumer {

    private static final String COMMON_TOPIC = "common-topic";

    private final ObjectMapper objectMapper;
    private final DimensionService dimensionService;
    private final FactService factService;
    private final DailySituationReportService dailySituationReportService;
    private final KafkaProducer kafkaProducer;

    @KafkaListener(topics = "tenant-service-topic", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeTenantEvents(String message) {
        log.info("[analytics] Received from tenant-service-topic");
        try {
            String eventType = extractEventType(message);
            switch (eventType) {
                case "TENANT_CREATED", "TENANT_UPDATED" -> {
                    TenantEvent event = objectMapper.readValue(message, TenantEvent.class);
                    dimensionService.upsertTenant(event);
                }
                case "WATER_NORM_UPDATED" -> {
                    WaterNormUpdatedEvent event = objectMapper.readValue(message, WaterNormUpdatedEvent.class);
                    dimensionService.updateWaterNorm(event);
                }
                case "INCLUDED_WORK_STATUSES_UPDATED" -> {
                    IncludedWorkStatusesUpdatedEvent event =
                            objectMapper.readValue(message, IncludedWorkStatusesUpdatedEvent.class);
                    dimensionService.updateIncludedWorkStatuses(event);
                }
                case "TENANT_LOCATION_HIERARCHY_UPDATED" -> {
                    TenantLocationHierarchyUpdatedEvent event = objectMapper.readValue(message,
                            TenantLocationHierarchyUpdatedEvent.class);
                    dimensionService.updateLocationHierarchyNames(event);
                }
                case "WATER_SUPPLY_THRESHOLD_UPDATED" -> {
                    WaterSupplyThresholdUpdatedEvent event = objectMapper.readValue(message,
                            WaterSupplyThresholdUpdatedEvent.class);
                    dimensionService.updateWaterSupplyThreshold(event);
                }
                default -> log.debug("Ignoring tenant event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process tenant event: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "user-service-topic", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeUserEvents(String message) {
        log.info("[analytics] Received from user-service-topic");
        try {
            String eventType = extractEventType(message);
            switch (eventType) {
                case "USER_CREATED", "USER_UPDATED" -> {
                    UserEvent event = objectMapper.readValue(message, UserEvent.class);
                    dimensionService.upsertUser(event);
                }
                case "USER_SCHEME_MAPPINGS_REPLACED" -> {
                    UserSchemeMappingsReplacedEvent event =
                            objectMapper.readValue(message, UserSchemeMappingsReplacedEvent.class);
                    dimensionService.replaceUserSchemeMappings(event);
                }
                default -> log.debug("Ignoring user event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process user event: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "scheme-service-topic", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeSchemeEvents(String message) {
        log.info("[analytics] Received from scheme-service-topic");
        try {
            String eventType = extractEventType(message);
            switch (eventType) {
                case "SCHEME_CREATED", "SCHEME_UPDATED" -> {
                    SchemeEvent event = objectMapper.readValue(message, SchemeEvent.class);
                    dimensionService.upsertScheme(event);
                }
                case "LGD_LOCATION_CREATED", "LGD_LOCATION_UPDATED" -> {
                    LgdLocationEvent event = objectMapper.readValue(message, LgdLocationEvent.class);
                    dimensionService.upsertLgdLocation(event);
                }
                case "DEPARTMENT_LOCATION_CREATED", "DEPARTMENT_LOCATION_UPDATED" -> {
                    DepartmentLocationEvent event = objectMapper.readValue(message, DepartmentLocationEvent.class);
                    dimensionService.upsertDepartmentLocation(event);
                }
                default -> log.debug("Ignoring scheme event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process scheme event: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "telemetry-service-topic", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeTelemetryEvents(String message) {
        log.info("[analytics] Received from telemetry-service-topic");
        try {
            String eventType = extractEventType(message);
            switch (eventType) {
                case "METER_READING_RECORDED" -> {
                    MeterReadingEvent event = objectMapper.readValue(message, MeterReadingEvent.class);
                    factService.ingestMeterReading(event);
                }
                case "WATER_QUANTITY_RECORDED" -> {
                    WaterQuantityEvent event = objectMapper.readValue(message, WaterQuantityEvent.class);
                    factService.ingestWaterQuantity(event);
                }
                case "SCHEME_PERFORMANCE_RECORDED" -> {
                    SchemePerformanceEvent event = objectMapper.readValue(message, SchemePerformanceEvent.class);
                    factService.ingestSchemePerformance(event);
                }
                case "ANOMALY_RECORDED" -> {
                    AnomalyEvent event = objectMapper.readValue(message, AnomalyEvent.class);
                    factService.ingestAnomalyRecorded(event);
                }
                case "SUBMISSION_REJECTED" -> {   // REPORTED-METRIC
                    SubmissionRejectedEvent event = objectMapper.readValue(message, SubmissionRejectedEvent.class);
                    factService.ingestSubmissionRejected(event);
                }
                default -> log.debug("Ignoring telemetry event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process telemetry event: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "anomaly-service-topic", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeAnomalyEvents(String message) {
        log.info("[analytics] Received from anomaly-service-topic");
        try {
            String eventType = extractEventType(message);
            switch (eventType) {
                case "ESCALATION_CREATED", "ESCALATION_UPDATED" -> {
                    EscalationEvent event = objectMapper.readValue(message, EscalationEvent.class);
                    factService.ingestEscalation(event);
                }
                default -> log.debug("Ignoring anomaly event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process anomaly event: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @KafkaListener(topics = "common-topic", groupId = "${spring.kafka.consumer.group-id}")
    public void consumeCommonTopic(String message) {
        log.info("[analytics] Received from common-topic");
        try {
            String eventType = extractEventType(message);
            switch (eventType) {
                case "ESCALATION" -> {
                    TenantEscalationEvent event = objectMapper.readValue(message, TenantEscalationEvent.class);
                    factService.ingestTenantEscalation(event);
                }
                case "DAILY_REPORT_REQUEST" -> handleDailyReportRequest(message);
                default -> log.debug("[analytics] Ignoring common-topic event type: {}", eventType);
            }
        } catch (Exception e) {
            log.error("Failed to process common-topic event: {}", e.getMessage(), e);
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Computes the Daily Water Service Situation Report KPIs for one officer and publishes a
     * {@code DAILY_REPORT_KPIS} event back to {@code common-topic} for message-service to render.
     * No PII is read or emitted here — identity ({@code officerUserId}, {@code tenantSchema}) is
     * forwarded so message-service can resolve the officer's contact from the operational schema.
     */
    private void handleDailyReportRequest(String message) throws Exception {
        DailyReportRequestEvent request = objectMapper.readValue(message, DailyReportRequestEvent.class);
        if (request.getTenantId() == null || request.getOfficerUserId() == null
                || request.getReportDate() == null || request.getReportDate().isBlank()
                || request.getTenantSchema() == null || request.getTenantSchema().isBlank()
                || request.getOfficerUserType() == null || request.getOfficerUserType().isBlank()) {
            log.warn("[analytics/DAILY_REPORT_REQUEST] Missing required field "
                    + "(tenantId/officerUserId/reportDate/tenantSchema/officerUserType), skipping");
            return;
        }

        LocalDate reportDate;
        try {
            reportDate = LocalDate.parse(request.getReportDate());
        } catch (DateTimeParseException e) {
            log.warn("[analytics/DAILY_REPORT_REQUEST] Malformed reportDate '{}', skipping (non-retryable)",
                    request.getReportDate());
            return;
        }

        String corr = request.getCorrelationId();
        long startNanos = System.nanoTime();
        log.info("[analytics/DAILY_REPORT_REQUEST] corr={} received: tenant={} officer={} role={} date={}",
                corr, request.getTenantId(), request.getOfficerUserId(), request.getOfficerUserType(), reportDate);

        DailyReportKpiDTO kpis = dailySituationReportService.buildReport(
                request.getTenantId(), request.getOfficerUserId(), reportDate,
                request.getSubordinateOfficerUserIds());

        DailyReportKpisEvent kpisEvent = DailyReportKpisEvent.builder()
                .eventType("DAILY_REPORT_KPIS")
                .tenantId(request.getTenantId())
                .tenantSchema(request.getTenantSchema())
                .officerUserId(request.getOfficerUserId())
                .officerUserType(request.getOfficerUserType())
                .correlationId(corr)
                .kpis(kpis)
                .build();

        kafkaProducer.publishJson(COMMON_TOPIC, kpisEvent);

        long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info("[analytics/DAILY_REPORT_REQUEST] corr={} computed+published: tenant={} officer={} date={} "
                        + "totalSchemes={} supplyingY={} reasons={} anomalies={} priorityActions={} tookMs={}",
                corr, request.getTenantId(), request.getOfficerUserId(), reportDate,
                kpis.getTotalSchemes(),
                kpis.getYesterday() != null ? kpis.getYesterday().getSchemesSupplying() : 0,
                kpis.getReasonsForNoSupply() != null ? kpis.getReasonsForNoSupply().size() : 0,
                kpis.getAnomaliesByType() != null ? kpis.getAnomaliesByType().size() : 0,
                kpis.getPriorityActions() != null ? kpis.getPriorityActions().size() : 0,
                tookMs);
    }

    private String extractEventType(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode eventTypeNode = node.get("eventType");
            return eventTypeNode != null ? eventTypeNode.asText() : "UNKNOWN";
        } catch (Exception e) {
            log.warn("Could not extract eventType from message, treating as UNKNOWN");
            return "UNKNOWN";
        }
    }
}
