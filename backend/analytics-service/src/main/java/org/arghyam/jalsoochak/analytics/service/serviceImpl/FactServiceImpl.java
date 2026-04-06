package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import org.arghyam.jalsoochak.analytics.constant.EscalationType;
import org.arghyam.jalsoochak.analytics.dto.event.AnomalyEvent;
import org.arghyam.jalsoochak.analytics.dto.event.EscalationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.MeterReadingEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SchemePerformanceEvent;
import org.arghyam.jalsoochak.analytics.dto.event.TenantEscalationEvent;
import org.arghyam.jalsoochak.analytics.dto.event.WaterQuantityEvent;
import org.arghyam.jalsoochak.analytics.enums.SubmissionStatus;
import org.arghyam.jalsoochak.analytics.entity.Anomaly;
import org.arghyam.jalsoochak.analytics.entity.DimDate;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.entity.FactEscalation;
import org.arghyam.jalsoochak.analytics.entity.FactMeterReading;
import org.arghyam.jalsoochak.analytics.entity.FactSchemePerformance;
import org.arghyam.jalsoochak.analytics.entity.FactWaterQuantity;
import org.arghyam.jalsoochak.analytics.repository.AnomalyRepository;
import org.arghyam.jalsoochak.analytics.repository.DimDateRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.repository.FactEscalationRepository;
import org.arghyam.jalsoochak.analytics.repository.FactMeterReadingRepository;
import org.arghyam.jalsoochak.analytics.repository.FactSchemePerformanceRepository;
import org.arghyam.jalsoochak.analytics.repository.FactWaterQuantityRepository;
import org.arghyam.jalsoochak.analytics.service.FactService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FactServiceImpl implements FactService {

    /**
     * Sentinel value used when an operator has never uploaded a reading.
     * Must match the value produced by EscalationSchedulerService (tenant-service).
     */
    public static final String LAST_RECORDED_BFM_DATE_NEVER = "Never";

    private final FactMeterReadingRepository meterReadingRepository;
    private final FactWaterQuantityRepository waterQuantityRepository;
    private final FactEscalationRepository escalationRepository;
    private final FactSchemePerformanceRepository schemePerformanceRepository;
    private final AnomalyRepository anomalyRepository;
    private final DimTenantRepository dimTenantRepository;
    private final DimDateRepository dimDateRepository;

    @Override
    @Transactional
    public void ingestMeterReading(MeterReadingEvent event) {
        LocalDateTime readingAt = parseTimestamp(event.getReadingAt());
        LocalDate readingDate = parseDate(event.getReadingDate());
        Integer submissionStatus = event.getSubmissionStatus() != null
                ? event.getSubmissionStatus()
                : SubmissionStatus.SUBMITTED.getCode();
        Integer readingType = event.getReadingType() != null ? event.getReadingType() : 0;

        FactMeterReading fact = FactMeterReading.builder()
                .tenantId(event.getTenantId())
                .schemeId(event.getSchemeId())
                .userId(event.getUserId())
                .extractedReading(event.getExtractedReading())
                .confirmedReading(event.getConfirmedReading())
                .confidence(event.getConfidence())
                .imageUrl(event.getImageUrl())
                .readingAt(readingAt)
                .channel(event.getChannel())
                .readingDate(readingDate)
                .submissionStatus(submissionStatus)
                .readingType(readingType)
                .createdAt(LocalDateTime.now())
                .build();

        meterReadingRepository.save(fact);
        log.info("Ingested fact_meter_reading_table for scheme={} tenant={}", event.getSchemeId(), event.getTenantId());
    }

    @Override
    @Transactional
    public void ingestWaterQuantity(WaterQuantityEvent event) {
        ensureTenantExists(event.getTenantId(), null);
        LocalDate date = parseDate(event.getDate());
        ensureDateExists(date);
        LocalDateTime now = LocalDateTime.now();

        FactWaterQuantity fact = FactWaterQuantity.builder()
                .tenantId(event.getTenantId())
                .schemeId(event.getSchemeId())
                .userId(event.getUserId())
                .waterQuantity(event.getWaterQuantity())
                .submissionStatus(event.getSubmissionStatus())
                .outageReason(event.getOutageReason())
                .nonSubmissionReason(event.getNonSubmissionReason())
                .date(date)
                .createdAt(now)
                .updatedAt(now)
                .build();

        waterQuantityRepository.save(fact);
        log.info("Ingested fact_water_quantity_table for scheme={} tenant={}", event.getSchemeId(), event.getTenantId());
    }

    @Override
    @Transactional
    public void ingestEscalation(EscalationEvent event) {
        ensureTenantExists(event.getTenantId(), null);
        LocalDateTime now = LocalDateTime.now();

        FactEscalation fact = FactEscalation.builder()
                .tenantId(event.getTenantId())
                .schemeId(event.getSchemeId())
                .escalationType(intCodeToVarchar(event.getEscalationType()))
                .message(event.getMessage())
                .userId(event.getUserId())
                .resolutionStatus(event.getResolutionStatus())
                .remark(event.getRemark())
                .createdAt(now)
                .updatedAt(now)
                .build();

        escalationRepository.save(fact);
        log.info("Ingested fact_escalation_table for scheme={} tenant={}", event.getSchemeId(), event.getTenantId());
    }

    @Override
    @Transactional
    public void ingestSchemePerformance(SchemePerformanceEvent event) {
        LocalDate lastSupplyDate = parseDate(event.getLastWaterSupplyDate());
        LocalDateTime now = LocalDateTime.now();

        FactSchemePerformance fact = FactSchemePerformance.builder()
                .tenantId(event.getTenantId())
                .schemeId(event.getSchemeId())
                .performanceScore(event.getPerformanceScore())
                .lastWaterSupplyDate(lastSupplyDate)
                .createdAt(now)
                .updatedAt(now)
                .build();

        schemePerformanceRepository.save(fact);
        log.info("Ingested fact_scheme_performance_table for scheme={} tenant={}", event.getSchemeId(), event.getTenantId());
    }

    private void ensureDateExists(LocalDate date) {
        if (date == null || dimDateRepository.findByFullDate(date).isPresent()) {
            return;
        }

        int month = date.getMonthValue();
        int quarter = ((month - 1) / 3) + 1;
        String monthName = date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        int week = date.get(WeekFields.ISO.weekOfWeekBasedYear());
        boolean isWeekend = date.getDayOfWeek().getValue() >= 6;

        DimDate dimDate = DimDate.builder()
                .dateKey(Integer.parseInt(date.format(DateTimeFormatter.BASIC_ISO_DATE)))
                .fullDate(date)
                .day(date.getDayOfMonth())
                .month(month)
                .monthName(monthName)
                .quarter(quarter)
                .year(date.getYear())
                .week(week)
                .isWeekend(isWeekend)
                .fiscalYear(date.getYear())
                .build();

        try {
            dimDateRepository.save(dimDate);
        } catch (DataIntegrityViolationException e) {
            // Another concurrent ingest may have inserted the same date.
            log.debug("Dim date already exists for {}", date);
        }
    }

    @Override
    @Transactional
    public void ingestTenantEscalation(TenantEscalationEvent event) {
        ensureTenantExists(event.getTenantId(), event.getTenantSchema());
        OffsetDateTime now = OffsetDateTime.now();
        int operatorCount = event.getOperators() != null ? event.getOperators().size() : 0;
        int escalationRowsCreated = 0;
        int anomalyRowsCreated = 0;

        // One fact_escalation_table row + one anomaly_table row per operator
        if (event.getOperators() == null) return;
        for (TenantEscalationEvent.TenantOperatorEscalationDetail op : event.getOperators()) {
            if (op.getCorrelationId() == null || op.getCorrelationId().isBlank()) {
                log.warn("Skipping operator escalation row — correlationId is null/blank; row discarded");
                continue;
            }
            if (op.getUserId() == null) {
                log.warn("Skipping operator escalation row — userId is null (schemeId={}, tenant={})", op.getSchemeId(), event.getTenantId());
                continue;
            }
            boolean neverUploaded = LAST_RECORDED_BFM_DATE_NEVER.equalsIgnoreCase(op.getLastRecordedBfmDate());
            if (op.getConsecutiveDaysMissed() == null && !neverUploaded) {
                log.warn("Skipping operator escalation row — consecutiveDaysMissed is null (schemeId={}, tenant={})", op.getSchemeId(), event.getTenantId());
                continue;
            }

            Integer schemeId = null;
            try {
                if (op.getSchemeId() != null && !op.getSchemeId().isBlank()) {
                    schemeId = Integer.parseInt(op.getSchemeId());
                }
            } catch (NumberFormatException e) {
                log.warn("Could not parse schemeId '{}' for operator row", op.getSchemeId());
            }
            if (schemeId == null) {
                log.warn("Skipping operator escalation row — schemeId is null/invalid (schemeId='{}', tenant={})", op.getSchemeId(), event.getTenantId());
                continue;
            }

            // Same correlation ID for both tables — allows joining fact_escalation and anomaly on the same event
            String correlationId = buildCorrelationId(EscalationType.NO_SUBMISSION, op.getUserId(), event.getTenantId(), schemeId);

            // Idempotency: delegate to the DB unique constraint; catch duplicate-key on concurrent delivery
            if (event.getOfficerId() == null) {
                log.warn("Skipping fact_escalation row — officerId is null (schemeId={}, tenant={})", schemeId, event.getTenantId());
            } else {
                String escalationMessage = neverUploaded
                        ? op.getName() + " has never submitted a reading"
                        : op.getName() + " has not submitted for " + op.getConsecutiveDaysMissed() + " consecutive days";
                try {
                    FactEscalation escalationFact = FactEscalation.builder()
                            .tenantId(event.getTenantId())
                            .schemeId(schemeId)
                            .escalationType(intCodeToVarchar(EscalationType.NO_SUBMISSION.code))
                            .message(escalationMessage)
                            .correlationId(correlationId)
                            .userId(event.getOfficerId().intValue())
                            .resolutionStatus(1) // UNRESOLVED
                            .remark(null)
                            .createdAt(now.toLocalDateTime())
                            .updatedAt(now.toLocalDateTime())
                            .build();
                    escalationRepository.save(escalationFact);
                    escalationRowsCreated++;
                } catch (DataIntegrityViolationException e) {
                    log.debug("Skipping duplicate fact_escalation for correlationId={}", correlationId);
                }
            }

            LocalDate previousReadingDate = null;
            if (!neverUploaded && op.getLastRecordedBfmDate() != null && !op.getLastRecordedBfmDate().isBlank()) {
                previousReadingDate = parseDateOrNull(op.getLastRecordedBfmDate());
            }

            BigDecimal previousReading = op.getLastConfirmedReading() != null
                    ? BigDecimal.valueOf(op.getLastConfirmedReading()) : null;

            String anomalyReason = neverUploaded
                    ? "No submission — operator has never uploaded a reading"
                    : "No submission for " + op.getConsecutiveDaysMissed() + " consecutive days";
            try {
                Anomaly anomaly = Anomaly.builder()
                        .uuid(UUID.randomUUID().toString())
                        .type(intCodeToVarchar(EscalationType.NO_SUBMISSION.code))
                        .userId(op.getUserId())
                        .schemeId(schemeId)
                        .tenantId(event.getTenantId())
                        .previousReading(previousReading)
                        .previousReadingDate(previousReadingDate)
                        .consecutiveDaysMissed(op.getConsecutiveDaysMissed())
                        .reason(anomalyReason)
                        .status(1) // OPEN
                        .correlationId(correlationId)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                anomalyRepository.save(anomaly);
                anomalyRowsCreated++;
            } catch (DataIntegrityViolationException e) {
                log.debug("Skipping duplicate anomaly for correlationId={}", correlationId);
            }
        }
        log.info("Processed {} operators for tenant={}: {} escalation rows, {} anomaly rows created (of {} total)",
                operatorCount, event.getTenantId(), escalationRowsCreated, anomalyRowsCreated, operatorCount);
    }

    @Override
    @Transactional
    public void ingestAnomalyRecorded(AnomalyEvent event) {
        OffsetDateTime now = OffsetDateTime.now();
        String uuid = event.getUuid();
        if (uuid == null || uuid.isBlank()) {
            uuid = UUID.randomUUID().toString();
            log.warn("Anomaly event missing uuid; generated new uuid={}", uuid);
        }
        Integer status = event.getStatus() != null ? event.getStatus() : 1;
        if (event.getStatus() == null) {
            log.warn("Anomaly event missing status; defaulting to status=1 (OPEN) for uuid={}", uuid);
        }
        String correlationId = event.getCorrelationId();
        if (isWaterAnomaly(event.getType()) && (correlationId == null || correlationId.isBlank())) {
            correlationId = resolveCorrelationId(event);
            log.warn("Water anomaly missing correlationId, derived correlationId={} (uuid={})", correlationId, uuid);
        }

        Anomaly anomaly = Anomaly.builder()
                .uuid(uuid)
                .type(intCodeToVarchar(event.getType()))
                .userId(event.getUserId())
                .schemeId(event.getSchemeId())
                .tenantId(event.getTenantId())
                .aiReading(event.getAiReading())
                .aiConfidencePercentage(event.getAiConfidencePercentage())
                .overriddenReading(event.getOverriddenReading())
                .retries(event.getRetries())
                .previousReading(event.getPreviousReading())
                .previousReadingDate(event.getPreviousReadingDate())
                .consecutiveDaysMissed(event.getConsecutiveDaysMissed())
                .reason(event.getReason())
                .status(status)
                .correlationId(correlationId)
                .createdAt(now)
                .updatedAt(now)
                .build();

        try {
            anomalyRepository.save(anomaly);
            log.info("Ingested anomaly_table row for scheme={} tenant={} uuid={}",
                    event.getSchemeId(), event.getTenantId(), event.getUuid());
        } catch (DataIntegrityViolationException e) {
            log.debug("Skipping duplicate anomaly uuid={} (unique constraint)", event.getUuid());
        }

        if (isWaterAnomaly(event.getType())) {
            FactEscalation escalation = FactEscalation.builder()
                    .tenantId(event.getTenantId())
                    .schemeId(event.getSchemeId())
                    .escalationType(intCodeToVarchar(event.getType()))
                    .message(event.getReason())
                    .userId(event.getUserId())
                    .resolutionStatus(event.getStatus())
                    .remark(null)
                    .correlationId(correlationId)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            escalationRepository.save(escalation);
            log.info("Ingested fact_escalation_table for water anomaly type={} scheme={} tenant={}",
                    event.getType(), event.getSchemeId(), event.getTenantId());
        }
    }

    private void ensureTenantExists(Integer tenantId, String tenantSchema) {
        if (tenantId == null) return;
        if (dimTenantRepository.existsById(tenantId)) return;
        String stateCode = (tenantSchema != null && tenantSchema.startsWith("tenant_")
                && tenantSchema.length() > "tenant_".length())
                ? tenantSchema.substring("tenant_".length())
                : "unknown";
        LocalDateTime now = LocalDateTime.now();
        DimTenant stub = DimTenant.builder()
                .tenantId(tenantId)
                .stateCode(stateCode)
                .title(tenantSchema != null ? tenantSchema : "tenant-" + tenantId)
                .countryCode("IN")
                .status(1)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            dimTenantRepository.save(stub);
            log.info("Created dim_tenant stub [id={} stateCode={}] — full data expected via TENANT_CREATED event", tenantId, stateCode);
        } catch (DataIntegrityViolationException e) {
            // Another thread inserted first — treat as success
            log.debug("dim_tenant stub for id={} already inserted by concurrent thread, continuing", tenantId);
        }
    }

    private LocalDateTime parseTimestamp(String value) {
        if (value == null || value.isBlank()) return LocalDateTime.now();
        try {
            return LocalDateTime.parse(value);
        } catch (Exception e) {
            log.warn("Could not parse timestamp '{}', falling back to now", value);
            return LocalDateTime.now();
        }
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return LocalDate.now();
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            log.warn("Could not parse date '{}', falling back to today", value);
            return LocalDate.now();
        }
    }

    /**
     * Builds a deterministic correlation ID for an anomaly event.
     * <ul>
     *   <li>Water anomalies ({@link EscalationType#WATER_ANOMALIES}): keyed on {@code tenantId:schemeId:type}
     *       — one record per supply event regardless of which user reported it.</li>
     *   <li>User anomalies ({@link EscalationType#USER_ANOMALIES}): keyed on {@code userId:tenantId:schemeId:type}
     *       — distinct per operator so concurrent violations from different users are not collapsed.</li>
     * </ul>
     */
    String buildCorrelationId(EscalationType type, Integer userId, Integer tenantId, Integer schemeId) {
        String key = EscalationType.USER_ANOMALIES.contains(type)
                ? userId + ":" + tenantId + ":" + schemeId + ":" + type.label
                : tenantId + ":" + schemeId + ":" + type.label;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private LocalDate parseDateOrNull(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            log.warn("Could not parse date '{}', storing null", value);
            return null;
        }
    }

    private static String intCodeToVarchar(Integer code) {
        if (code == null) {
            return null;
        }
        EscalationType type = EscalationType.fromCode(code);
        if (type == null) {
            return String.valueOf(code);
        }
        return switch (type) {
            case NO_WATER_SUPPLY -> "no_supply";
            case LOW_WATER_SUPPLY -> "under_supply";
            case OVER_WATER_SUPPLY -> "over_supply";
            default -> type.label.toLowerCase(Locale.ROOT);
        };
    }

    private boolean isWaterAnomaly(Integer type) {
        if (type == null) {
            return false;
        }
        return EscalationType.WATER_ANOMALIES.stream().anyMatch(t -> t.code == type);
    }

    private String resolveCorrelationId(AnomalyEvent event) {
        if (event.getCorrelationId() != null && !event.getCorrelationId().isBlank()) {
            return event.getCorrelationId();
        }
        if (event.getType() != null) {
            EscalationType type = EscalationType.WATER_ANOMALIES.stream()
                    .filter(t -> t.code == event.getType())
                    .findFirst()
                    .orElse(null);
            if (type != null) {
                return buildCorrelationId(type, event.getUserId(), event.getTenantId(), event.getSchemeId());
            }
        }
        return event.getUuid();
    }
}
