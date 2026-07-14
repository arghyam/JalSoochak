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

    public void processDailyReportsForTenant(String schema, int tenantId) {
        LocalDate reportDate = LocalDate.now(IST).minusDays(1);
        int count = 0;

        for (String role : officerRoles()) {
            List<Long> officerIds = nudgeRepository.findDistinctOfficerUserIdsByUserType(schema, role);
            for (Long officerUserId : officerIds) {
                DailyReportRequestEvent event = DailyReportRequestEvent.builder()
                        .eventType("DAILY_REPORT_REQUEST")
                        .tenantId(tenantId)
                        .tenantSchema(schema)
                        .officerUserId(officerUserId)
                        .officerUserType(role)
                        .reportDate(reportDate.toString())
                        .build();
                kafkaProducer.publishJson(COMMON_TOPIC, event);
                count++;
                log.debug("[DailyReportJob] Published DAILY_REPORT_REQUEST for officer={} role={}", officerUserId, role);
            }
        }

        log.info("[DailyReportJob] schema={} → requested reports for {} officers (date={})",
                schema, count, reportDate);
    }

    private List<String> officerRoles() {
        return Arrays.stream(officerUserTypesCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
