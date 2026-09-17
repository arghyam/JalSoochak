package org.arghyam.jalsoochak.analytics.kafka;

import org.arghyam.jalsoochak.analytics.dto.event.DepartmentLocationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.EscalationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.IncludedWorkStatusesUpdatedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.RegularityThresholdUpdatedEvent;
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
import org.arghyam.jalsoochak.analytics.dto.event.WeeklyReportRequestEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WeeklyReportKpisEvent;
import org.arghyam.jalsoochak.analytics.dto.DailyReportKpiDTO;
import org.arghyam.jalsoochak.analytics.dto.WeeklyReportKpiDTO;
import org.arghyam.jalsoochak.analytics.service.DimensionService;
import org.arghyam.jalsoochak.analytics.service.DailySituationReportService;
import org.arghyam.jalsoochak.analytics.service.WeeklySituationReportService;
import org.arghyam.jalsoochak.analytics.service.FactService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
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
    private final WeeklySituationReportService weeklySituationReportService;
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
                case "REGULARITY_THRESHOLD_UPDATED" -> {
                    RegularityThresholdUpdatedEvent event =
                            objectMapper.readValue(message, RegularityThresholdUpdatedEvent.class);
                    dimensionService.updateRegularityThreshold(event);
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
                case "WEEKLY_REPORT_REQUEST" -> handleWeeklyReportRequest(message);
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
        // Canonical role for this event: trimmed once here and then used for the role= log field, the
        // validation below, and the published KPIs event, so every downstream stage gates on the same
        // token. Falls back to a countable placeholder so role= is never empty on a malformed event.
        String officerUserType = request.getOfficerUserType() == null
                ? null : request.getOfficerUserType().trim();
        String role = (officerUserType == null || officerUserType.isEmpty()) ? "UNKNOWN" : officerUserType;
        if (request.getTenantId() == null || request.getOfficerUserId() == null
                || request.getReportDate() == null || request.getReportDate().isBlank()
                || request.getTenantSchema() == null || request.getTenantSchema().isBlank()
                || officerUserType == null || officerUserType.isEmpty()) {
            log.warn("[analytics/DAILY_REPORT_REQUEST] corr={} result=SKIPPED_INVALID_EVENT role={} — missing required"
                            + " field (tenantId/officerUserId/reportDate/tenantSchema/officerUserType)",
                    request.getCorrelationId(), role);
            return;
        }

        LocalDate reportDate;
        try {
            reportDate = LocalDate.parse(request.getReportDate());
        } catch (DateTimeParseException e) {
            log.warn("[analytics/DAILY_REPORT_REQUEST] corr={} result=SKIPPED_INVALID_EVENT role={} — malformed"
                            + " reportDate '{}' (non-retryable)",
                    request.getCorrelationId(), role, request.getReportDate());
            return;
        }

        LocalDateTime cutoffIst = parseCutoff(request.getCutoffIst(), request.getCorrelationId());

        String corr = request.getCorrelationId();
        long startNanos = System.nanoTime();
        log.info("[analytics/DAILY_REPORT_REQUEST] corr={} received: tenant={} officer={} role={} date={} cutoff={}",
                corr, request.getTenantId(), request.getOfficerUserId(), role, reportDate, cutoffIst);

        DailyReportKpiDTO kpis = dailySituationReportService.buildReport(
                request.getTenantId(), request.getOfficerUserId(), reportDate, cutoffIst);

        DailyReportKpisEvent kpisEvent = DailyReportKpisEvent.builder()
                .eventType("DAILY_REPORT_KPIS")
                .tenantId(request.getTenantId())
                .tenantSchema(request.getTenantSchema())
                .officerUserId(request.getOfficerUserId())
                .officerUserType(officerUserType)
                .correlationId(corr)
                .kpis(kpis)
                .build();

        kafkaProducer.publishJson(COMMON_TOPIC, kpisEvent);

        long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info("[analytics/DAILY_REPORT_REQUEST] corr={} result=COMPUTED role={} tenant={} officer={} date={} "
                        + "totalSchemes={} supplying={} noSupply={} anomalies={} tookMs={}",
                corr, role, request.getTenantId(), request.getOfficerUserId(), reportDate,
                kpis.getTotalSchemes(),
                kpis.getSchemesSupplying(),
                kpis.getNoSupplySchemeIds() != null ? kpis.getNoSupplySchemeIds().size() : 0,
                kpis.getAnomalousCount(),
                tookMs);
    }

    /**
     * Parses the report's data-window cut-off. A malformed or absent value is not fatal: the report
     * still covers the whole day, which is what a rebuild of a past day wants anyway. Failing the
     * whole report over it would cost the officer their report to save an hour's precision.
     */
    private LocalDateTime parseCutoff(String cutoffIst, String corr) {
        if (cutoffIst == null || cutoffIst.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(cutoffIst);
        } catch (DateTimeParseException e) {
            log.warn("[analytics/DAILY_REPORT_REQUEST] corr={} malformed cutoffIst '{}' — covering the whole day",
                    corr, cutoffIst);
            return null;
        }
    }

    /**
     * Computes the Weekly Water Service Situation Report KPIs for one officer and publishes a
     * {@code WEEKLY_REPORT_KPIS} event back to {@code common-topic} for message-service to render.
     * Both Section Officers and Sub-Divisional Officers receive this report; the role travels on the
     * event and selects the layout downstream and the supply-day bar here.
     */
    private void handleWeeklyReportRequest(String message) throws Exception {
        WeeklyReportRequestEvent request = objectMapper.readValue(message, WeeklyReportRequestEvent.class);
        String officerUserType = request.getOfficerUserType() == null
                ? null : request.getOfficerUserType().trim();
        String role = (officerUserType == null || officerUserType.isEmpty()) ? "UNKNOWN" : officerUserType;
        if (request.getTenantId() == null || request.getOfficerUserId() == null
                || request.getTenantSchema() == null || request.getTenantSchema().isBlank()
                || officerUserType == null || officerUserType.isEmpty()) {
            log.warn("[analytics/WEEKLY_REPORT_REQUEST] corr={} result=SKIPPED_INVALID_EVENT role={} — missing"
                            + " required field (tenantId/officerUserId/tenantSchema/officerUserType)",
                    request.getCorrelationId(), role);
            return;
        }

        LocalDate weekStart;
        LocalDate weekEnd;
        LocalDate previousWeekStart;
        LocalDate previousWeekEnd;
        try {
            weekStart = LocalDate.parse(request.getWeekStart());
            weekEnd = LocalDate.parse(request.getWeekEnd());
            previousWeekStart = LocalDate.parse(request.getPreviousWeekStart());
            previousWeekEnd = LocalDate.parse(request.getPreviousWeekEnd());
        } catch (NullPointerException | DateTimeParseException e) {
            // Unlike the daily cut-off, the week bounds have no safe fallback: guessing them would
            // deliver a report covering days the officer was never told about.
            log.warn("[analytics/WEEKLY_REPORT_REQUEST] corr={} result=SKIPPED_INVALID_EVENT role={} — malformed"
                            + " week range '{}'..'{}' / '{}'..'{}' (non-retryable)",
                    request.getCorrelationId(), role, request.getWeekStart(), request.getWeekEnd(),
                    request.getPreviousWeekStart(), request.getPreviousWeekEnd());
            return;
        }

        String corr = request.getCorrelationId();
        long startNanos = System.nanoTime();
        log.info("[analytics/WEEKLY_REPORT_REQUEST] corr={} received: tenant={} officer={} role={} week={}..{}",
                corr, request.getTenantId(), request.getOfficerUserId(), role, weekStart, weekEnd);

        WeeklyReportKpiDTO kpis = weeklySituationReportService.buildReport(
                request.getTenantId(), request.getOfficerUserId(), officerUserType,
                weekStart, weekEnd, previousWeekStart, previousWeekEnd,
                request.getSubordinateOfficerUserIds());

        WeeklyReportKpisEvent kpisEvent = WeeklyReportKpisEvent.builder()
                .eventType("WEEKLY_REPORT_KPIS")
                .tenantId(request.getTenantId())
                .tenantSchema(request.getTenantSchema())
                .officerUserId(request.getOfficerUserId())
                .officerUserType(officerUserType)
                .correlationId(corr)
                .kpis(kpis)
                .build();

        kafkaProducer.publishJson(COMMON_TOPIC, kpisEvent);

        long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info("[analytics/WEEKLY_REPORT_REQUEST] corr={} result=COMPUTED role={} tenant={} officer={} "
                        + "week={}..{} totalSchemes={} supplying={} noSupply={} lowLpcd={} officers={} tookMs={}",
                corr, role, request.getTenantId(), request.getOfficerUserId(), weekStart, weekEnd,
                kpis.getWeek() != null ? kpis.getWeek().getTotalSchemes() : 0,
                kpis.getWeek() != null ? kpis.getWeek().getSchemesSupplying() : 0,
                kpis.getNoSupplySchemeIds() != null ? kpis.getNoSupplySchemeIds().size() : 0,
                kpis.getLowLpcdSchemeIds() != null ? kpis.getLowLpcdSchemeIds().size() : 0,
                kpis.getSectionOfficerSummaries() != null ? kpis.getSectionOfficerSummaries().size() : 0,
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
