package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.event.DailyReportRequestEvent;
import org.arghyam.jalsoochak.tenant.kafka.KafkaProducer;
import org.arghyam.jalsoochak.tenant.repository.NudgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Enumerates the officers of a single tenant and publishes one {@code DAILY_REPORT_REQUEST} per
 * officer to {@code common-topic}, asking analytics-service to compute that officer's Daily Water
 * Service Situation Report KPIs (which message-service then renders and delivers).
 *
 * <p>Called by {@link TenantSchedulerManager} on each tenant's individual daily schedule. This
 * service reads only officer <em>user ids</em> (no PII); PII is resolved by message-service at
 * delivery time.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DailySituationReportSchedulerService {

    private static final String COMMON_TOPIC = "common-topic";
    /** reading_date is stored on the IST calendar day, so "yesterday" must be evaluated in IST. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final NudgeRepository nudgeRepository;
    private final KafkaProducer kafkaProducer;

    /** Officer roles that receive the daily report. Both SO and SDO by default. */
    @Value("${daily-report.officer.user-types:SECTION_OFFICER,SUB_DIVISIONAL_OFFICER}")
    private String officerUserTypesCsv;

    private static final String SDO_ROLE = "SUB_DIVISIONAL_OFFICER";

    public void processDailyReportsForTenant(String schema, int tenantId) {
        LocalDate reportDate = LocalDate.now(IST).minusDays(1);
        String correlationId = UUID.randomUUID().toString();
        long startNanos = System.nanoTime();
        List<String> roles = officerRoles();

        log.info("[DailyReportJob] corr={} start: tenant={} schema={} date={} roles={}",
                correlationId, tenantId, schema, reportDate, roles);

        int count = 0;
        for (String role : roles) {
            List<Long> officerIds = nudgeRepository.findDistinctOfficerUserIdsByUserType(schema, role);
            log.info("[DailyReportJob] corr={} tenant={} role={} → {} officer(s)",
                    correlationId, tenantId, role, officerIds.size());
            boolean isSdo = SDO_ROLE.equalsIgnoreCase(role);
            for (Long officerUserId : officerIds) {
                // For an SDO, resolve the Section Officers under them (shared-scheme derivation) so
                // analytics can compute the per-officer Summary breakdown. Ids only — no PII.
                List<Long> subordinateOfficerIds = isSdo
                        ? nudgeRepository.findSubordinateSectionOfficerIds(schema, officerUserId)
                        : null;
                DailyReportRequestEvent event = DailyReportRequestEvent.builder()
                        .eventType("DAILY_REPORT_REQUEST")
                        .tenantId(tenantId)
                        .tenantSchema(schema)
                        .officerUserId(officerUserId)
                        .officerUserType(role)
                        .reportDate(reportDate.toString())
                        .correlationId(correlationId)
                        .subordinateOfficerUserIds(subordinateOfficerIds)
                        .build();
                kafkaProducer.publishJson(COMMON_TOPIC, event);
                count++;
                log.debug("[DailyReportJob] corr={} published DAILY_REPORT_REQUEST officer={} role={} subordinates={}",
                        correlationId, officerUserId, role,
                        subordinateOfficerIds != null ? subordinateOfficerIds.size() : 0);
            }
        }

        long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info("[DailyReportJob] corr={} done: tenant={} schema={} date={} requested={} officer(s) tookMs={}",
                correlationId, tenantId, schema, reportDate, count, tookMs);
    }

    private List<String> officerRoles() {
        return Arrays.stream(officerUserTypesCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
