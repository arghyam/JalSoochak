package org.arghyam.jalsoochak.tenant.service;

import org.arghyam.jalsoochak.tenant.event.WeeklyReportRequestEvent;
import org.arghyam.jalsoochak.tenant.kafka.KafkaProducer;
import org.arghyam.jalsoochak.tenant.repository.NudgeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Enumerates the officers of a single tenant and publishes one {@code WEEKLY_REPORT_REQUEST} per
 * officer to {@code common-topic}, asking analytics-service to compute that officer's Weekly Water
 * Service Situation Report KPIs (which message-service then renders and delivers).
 *
 * <p>Called by {@link TenantSchedulerManager} on each tenant's individual weekly schedule. Unlike the
 * daily report, both Section Officers and Sub-Divisional Officers receive this one — they get
 * different layouts, resolved downstream from {@code officerUserType}.</p>
 *
 * <p>The reported window is the last complete week beginning on the tenant's configured
 * {@code weekStartDay}, which the caller resolves from config and passes in. It is independent of the
 * day the job fires on.</p>
 *
 * <p>This service reads only officer <em>user ids</em> (no PII); PII is resolved by message-service at
 * delivery time.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeeklySituationReportSchedulerService {

    private static final String COMMON_TOPIC = "common-topic";
    /** Supply dates are stored on the IST calendar day, so week boundaries must be found in IST. */
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String SDO_ROLE = "SUB_DIVISIONAL_OFFICER";
    private static final int DAYS_IN_WEEK = 7;

    private final NudgeRepository nudgeRepository;
    private final KafkaProducer kafkaProducer;

    /** Officer roles that receive the weekly report. Both SO and SDO by default. */
    @Value("${weekly-report.officer.user-types:SECTION_OFFICER,SUB_DIVISIONAL_OFFICER}")
    private String officerUserTypesCsv;

    public void processWeeklyReportsForTenant(String schema, int tenantId, DayOfWeek weekStartDay) {
        LocalDate weekEnd = lastCompletedWeekEnd(LocalDate.now(IST), weekStartDay);
        LocalDate weekStart = weekEnd.minusDays(DAYS_IN_WEEK - 1L);
        LocalDate previousWeekEnd = weekEnd.minusDays(DAYS_IN_WEEK);
        LocalDate previousWeekStart = weekStart.minusDays(DAYS_IN_WEEK);

        String correlationId = UUID.randomUUID().toString();
        long startNanos = System.nanoTime();
        List<String> roles = officerRoles();

        log.info("[WeeklyReportJob] corr={} start: tenant={} schema={} weekStartDay={} week={}..{}"
                        + " prevWeek={}..{} roles={}",
                correlationId, tenantId, schema, weekStartDay, weekStart, weekEnd,
                previousWeekStart, previousWeekEnd, roles);

        // Requested-count per role — the denominator the downstream GENERATED/SENT counts reconcile
        // against. LinkedHashMap keeps the configured order.
        Map<String, Integer> requestedByRole = new LinkedHashMap<>();
        int count = 0;
        for (String role : roles) {
            List<Long> officerIds = nudgeRepository.findDistinctOfficerUserIdsByUserType(schema, role);
            // Merge rather than put: a role listed twice in the CSV is published twice, so the
            // per-role total must add up to `count` instead of being overwritten by the last pass.
            requestedByRole.merge(role, officerIds.size(), Integer::sum);
            log.info("[WeeklyReportJob] corr={} result=REQUESTED role={} tenant={} officers={}",
                    correlationId, role, tenantId, officerIds.size());
            boolean isSdo = SDO_ROLE.equalsIgnoreCase(role);
            for (Long officerUserId : officerIds) {
                // For an SDO, resolve the Section Officers under them (shared-scheme derivation) so
                // analytics can compute the per-officer performance table. Ids only — no PII.
                List<Long> subordinateOfficerIds = isSdo
                        ? nudgeRepository.findSubordinateSectionOfficerIds(schema, officerUserId)
                        : null;
                WeeklyReportRequestEvent event = WeeklyReportRequestEvent.builder()
                        .eventType("WEEKLY_REPORT_REQUEST")
                        .tenantId(tenantId)
                        .tenantSchema(schema)
                        .officerUserId(officerUserId)
                        .officerUserType(role)
                        .weekStart(weekStart.toString())
                        .weekEnd(weekEnd.toString())
                        .previousWeekStart(previousWeekStart.toString())
                        .previousWeekEnd(previousWeekEnd.toString())
                        .correlationId(correlationId)
                        .subordinateOfficerUserIds(subordinateOfficerIds)
                        .build();
                kafkaProducer.publishJson(COMMON_TOPIC, event);
                count++;
                log.debug("[WeeklyReportJob] corr={} published WEEKLY_REPORT_REQUEST officer={} role={} subordinates={}",
                        correlationId, officerUserId, role,
                        subordinateOfficerIds != null ? subordinateOfficerIds.size() : 0);
            }
        }

        long tookMs = (System.nanoTime() - startNanos) / 1_000_000L;
        log.info("[WeeklyReportJob] corr={} done: tenant={} schema={} week={}..{} requested={} officer(s)"
                        + " requestedByRole={} tookMs={}",
                correlationId, tenantId, schema, weekStart, weekEnd, count, requestedByRole, tookMs);
    }

    /**
     * The day that closed the last <em>complete</em> week before {@code today}, for a week running
     * {@code weekStartDay} through the day before it. With {@code weekStartDay = MONDAY} this is the
     * previous Sunday, the long-standing behaviour.
     *
     * <p>Derived from the run day rather than assumed to be "yesterday", because the weekly cron is
     * per-tenant configurable and need not fire on the day the week starts. For a Monday–Sunday week,
     * run on a Monday this is yesterday; run on a Thursday it is the Sunday four days back; run on a
     * Sunday it is the Sunday a week back, since today's week has not closed yet.</p>
     *
     * <p>{@link TemporalAdjusters#previous} is strict — it never returns {@code today} — which is what
     * guarantees the reported week never includes a day that is still in progress.</p>
     */
    private static LocalDate lastCompletedWeekEnd(LocalDate today, DayOfWeek weekStartDay) {
        // minus(1) rolls, so MONDAY yields SUNDAY.
        return today.with(TemporalAdjusters.previous(weekStartDay.minus(1)));
    }

    private List<String> officerRoles() {
        return Arrays.stream(officerUserTypesCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
