package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.config.EscalationScheduleConfig;
import org.arghyam.jalsoochak.tenant.event.EscalationEvent;
import org.arghyam.jalsoochak.tenant.event.OperatorEscalationDetail;
import org.arghyam.jalsoochak.tenant.kafka.KafkaProducer;
import org.arghyam.jalsoochak.tenant.repository.NudgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Processes escalations for a single tenant. Called by {@link TenantSchedulerManager}
 * on each tenant's individual schedule.
 *
 * <p>Users who missed &ge; level2Threshold days are escalated to the district
 * officer; those between level1 and level2 thresholds go to the section officer.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EscalationSchedulerService {

    private static final String COMMON_TOPIC = "common-topic";

    /**
     * Sentinel string set on OperatorEscalationDetail.lastRecordedBfmDate when an operator
     * has never uploaded a reading. Must match the value consumed by analytics-service
     * (FactServiceImpl.LAST_RECORDED_BFM_DATE_NEVER).
     */
    static final String LAST_RECORDED_BFM_DATE_NEVER = "Never";

    private final NudgeRepository nudgeRepository;
    private final TenantConfigService tenantConfigService;
    private final KafkaProducer kafkaProducer;

    @Transactional(readOnly = true)
    public void processEscalationsForTenant(String schema, int tenantId) {
        EscalationScheduleConfig cfg = tenantConfigService.getEscalationConfig(tenantId);
        int level1Days = cfg.getLevel1Days();
        int level2Days = cfg.getLevel2Days();
        String level1UserType = cfg.getLevel1OfficerType();
        String level2UserType = cfg.getLevel2OfficerType();
        if (level1Days < 0 || level2Days < level1Days) {
            throw new IllegalArgumentException("Invalid escalation thresholds for tenantId=" + tenantId);
        }if (level1UserType == null || level1UserType.isBlank()
                || level2UserType == null || level2UserType.isBlank()) {
            throw new IllegalArgumentException("Officer types must be configured for tenantId=" + tenantId);
        }

        log.info("[EscalationJob] schema={} – L1: {} days ({}), L2: {} days ({})",
                schema, level1Days, level1UserType, level2Days, level2UserType);

        // Preload all officer data for both levels in two queries (avoids N+1 inside the stream)
        Map<Object, Map<String, Object>> level1OfficersByScheme =
                nudgeRepository.findAllOfficersByUserType(schema, level1UserType);
        Map<Object, Map<String, Object>> level2OfficersByScheme =
                nudgeRepository.findAllOfficersByUserType(schema, level2UserType);

        // officer-key → (officerRow, level, detailList)
        // Key = "LEVEL_<n>|<phone>" to keep level1 and level2 officers separate
        Map<String, OfficerGroup> officerGroups = new LinkedHashMap<>();

        // reading_date is stored on the IST calendar day, so "today"/missed-day math must be IST.
        LocalDate processingDate = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        int total = nudgeRepository.streamUsersWithMissedDays(schema, level1Days, processingDate, row -> {
            // days_since_last_upload is NULL when the operator has never uploaded
            Number daysSinceObj = (Number) row.get("days_since_last_upload");
            boolean neverUploaded = (daysSinceObj == null);
            int daysSinceLastUpload = neverUploaded ? Integer.MAX_VALUE : daysSinceObj.intValue();

            // Never-uploaded operators go straight to level-2 (most severe)
            int escalationLevel = (neverUploaded || daysSinceLastUpload >= level2Days) ? 2 : 1;
            // Use null for display so PDFs/reports don't show Integer.MAX_VALUE for never-uploaded operators
            Integer displayedMissedDays = neverUploaded ? null : daysSinceLastUpload;
            Object schemeId = row.get("scheme_id");

            Map<String, Object> officerRow = escalationLevel == 2
                    ? level2OfficersByScheme.get(schemeId)
                    : level1OfficersByScheme.get(schemeId);
            if (officerRow == null) {
                log.warn("[EscalationJob] No officer found – schema={}, schemeId={}, escalationLevel={}, skipping",
                        schema, schemeId, escalationLevel);
                return;
            }

            String officerPhone = (String) officerRow.get("phone_number");
            String officerName = (String) officerRow.get("name");
            Integer officerLanguageId = officerRow.get("language_id") != null
                    ? ((Number) officerRow.get("language_id")).intValue() : null;
            Long officerId = officerRow.get("user_id") != null
                    ? ((Number) officerRow.get("user_id")).longValue() : null;
            Long officerWhatsappConnectionId = officerRow.get("whatsapp_connection_id") != null
                    ? ((Number) officerRow.get("whatsapp_connection_id")).longValue() : null;
            if (officerPhone == null || officerPhone.isBlank()) {
                log.warn("[EscalationJob] Skipping escalation due to missing officerPhone – schema={}, schemeId={}, escalationLevel={}",
                        schema, schemeId, escalationLevel);
                return;
            }

            // Look up SO name from preloaded map for informational purposes in the detail
            String soName = "";
            if (escalationLevel == 2) {
                Map<String, Object> soRow = level1OfficersByScheme.get(schemeId);
                if (soRow != null) {
                    soName = (String) soRow.getOrDefault("name", "");
                }
            } else {
                soName = officerName; // level1 officer IS the SO
            }

            // Display "Never" when no reading exists; otherwise show the actual date
            Object lastReadingDateObj = row.get("last_reading_date");
            String lastRecordedBfmDate = (lastReadingDateObj == null) ? LAST_RECORDED_BFM_DATE_NEVER : lastReadingDateObj.toString();

            Object lastConfirmedReadingObj = row.get("last_confirmed_reading");
            Double lastConfirmedReading = lastConfirmedReadingObj != null
                    ? ((Number) lastConfirmedReadingObj).doubleValue() : null;

            Integer userId = row.get("user_id") != null
                    ? ((Number) row.get("user_id")).intValue() : null;

            int effectiveDays = neverUploaded ? 0 : daysSinceLastUpload;
            LocalDate streakStart = neverUploaded ? LocalDate.of(1970, 1, 1) : processingDate.minusDays(effectiveDays);
            String operatorIdentifier;
            if (userId != null) {
                operatorIdentifier = userId.toString();
            } else if (row.get("phone_number") != null) {
                operatorIdentifier = (String) row.get("phone_number");
            } else {
                operatorIdentifier = UUID.randomUUID().toString();
            }
            String opCorrelationKey = schema + ":" + schemeId + ":" + operatorIdentifier + ":NO_SUBMISSION:" + streakStart;
            String opCorrelationId = UUID.nameUUIDFromBytes(
                    opCorrelationKey.getBytes(StandardCharsets.UTF_8)).toString();

            OperatorEscalationDetail detail = OperatorEscalationDetail.builder()
                    .name((String) row.get("name"))
                    .phoneNumber((String) row.get("phone_number"))
                    .schemeName(row.get("scheme_name") != null ? (String) row.get("scheme_name") : String.valueOf(schemeId))
                    .schemeId(String.valueOf(schemeId))
                    .soName(soName)
                    .consecutiveDaysMissed(displayedMissedDays)
                    .lastRecordedBfmDate(lastRecordedBfmDate)
                    .userId(userId)
                    .lastConfirmedReading(lastConfirmedReading)
                    .correlationId(opCorrelationId)
                    .build();

            String userType = escalationLevel == 2 ? level2UserType : level1UserType;
            String groupKey = "LEVEL_" + escalationLevel + "|" + officerPhone;
            officerGroups.computeIfAbsent(groupKey, k ->
                    new OfficerGroup(officerPhone, officerName, escalationLevel, officerLanguageId,
                            officerId, officerWhatsappConnectionId, userType))
                    .details.add(detail);
        });
        log.info("[EscalationJob] schema={} → {} users exceeded level1 threshold", schema, total);

        // Publish one EscalationEvent per officer
        for (OfficerGroup group : officerGroups.values()) {
            String officerCorrelationKey = tenantId + ":" + (group.officerId != null ? group.officerId : group.officerPhone) + ":NO_SUBMISSION";
            String officerCorrelationId = UUID.nameUUIDFromBytes(
                    officerCorrelationKey.getBytes(StandardCharsets.UTF_8)).toString();

            EscalationEvent event = EscalationEvent.builder()
                    .eventType("ESCALATION")
                    .escalationLevel(group.level)
                    .anomalyType("no_submission")
                    .officerPhone(group.officerPhone)
                    .officerName(group.officerName)
                    .operators(group.details)
                    .tenantId(tenantId)
                    .officerLanguageId(group.officerLanguageId)
                    .officerId(group.officerId)
                    .officerWhatsappConnectionId(group.officerWhatsappConnectionId)
                    .tenantSchema(schema)
                    .correlationId(officerCorrelationId)
                    .officerUserType(group.officerUserType)
                    .build();
            kafkaProducer.publishJson(COMMON_TOPIC, event);
            log.info("[EscalationJob] Published EscalationEvent level={} with {} operators",
                    group.level, group.details.size());
        }
    }

    private static class OfficerGroup {
        final String officerPhone;
        final String officerName;
        final int level;
        final Integer officerLanguageId;
        final Long officerId;
        final Long officerWhatsappConnectionId;
        final String officerUserType;
        final List<OperatorEscalationDetail> details = new ArrayList<>();

        OfficerGroup(String officerPhone, String officerName, int level, Integer officerLanguageId,
                     Long officerId, Long officerWhatsappConnectionId, String officerUserType) {
            this.officerPhone = officerPhone;
            this.officerName = officerName;
            this.level = level;
            this.officerLanguageId = officerLanguageId;
            this.officerId = officerId;
            this.officerWhatsappConnectionId = officerWhatsappConnectionId;
            this.officerUserType = officerUserType;
        }
    }
}
