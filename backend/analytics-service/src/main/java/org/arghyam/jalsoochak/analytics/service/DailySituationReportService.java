package org.arghyam.jalsoochak.analytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.dto.DailyReportKpiDTO;
import org.arghyam.jalsoochak.analytics.repository.DailySituationReportRepository;
import org.arghyam.jalsoochak.analytics.repository.SchemeRegularityRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Builds the {@link DailyReportKpiDTO} for one officer, scoped to the schemes mapped to that
 * officer, for the day the report covers (D-1) and the comparison day (D-2).
 *
 * <p>Reuses the existing officer-scoped helpers in {@link SchemeRegularityRepository}
 * ({@code getSchemeCountByUser}, {@code getOutageReasonSchemeCountByUser}) and the new
 * single-day / week-window queries in {@link DailySituationReportRepository}. Computes only from
 * {@code analytics_schema}; no PII involved.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DailySituationReportService {

    private static final int SUPPLY_WINDOW_DAYS = 7;

    /** Report days are calendar days in IST; anomaly {@code created_at} is stored UTC-naive. */
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Kolkata");

    private final DailySituationReportRepository reportRepository;
    private final SchemeRegularityRepository schemeRegularityRepository;

    public DailyReportKpiDTO buildReport(Integer tenantId, Long officerUserId, LocalDate reportDate) {
        return buildReport(tenantId, officerUserId, reportDate, null);
    }

    /**
     * Builds the officer's report. When {@code subordinateOfficerUserIds} is non-empty (an SDO report),
     * additionally computes a per-Section-Officer single-day Summary breakdown
     * ({@link DailyReportKpiDTO#getSectionOfficerSummaries()}); message-service resolves each officer's
     * name + mobile at render time.
     */
    public DailyReportKpiDTO buildReport(Integer tenantId, Long officerUserId, LocalDate reportDate,
                                         List<Long> subordinateOfficerUserIds) {
        LocalDate previousDate = reportDate.minusDays(1);

        int totalSchemes = safeCount(schemeRegularityRepository.getSchemeCountByUser(tenantId, officerUserId.intValue()));
        long population = reportRepository.populationServed(tenantId, officerUserId);

        DailyReportKpiDTO.DayKpis yesterday = buildDay(tenantId, officerUserId, reportDate, totalSchemes, population);
        DailyReportKpiDTO.DayKpis previousDay = buildDay(tenantId, officerUserId, previousDate, totalSchemes, population);

        // Section 3 + 4 are reported for the covered day (D-1) only.
        // Section 3 ("Reasons for No Water Supply") is sourced from non_submission_reason: the
        // reason an operator gave for not submitting a reading on the day (submission_status =
        // NOT_SUBMITTED), grouped by reason with a distinct-scheme count.
        List<DailyReportKpiDTO.ReasonCount> reasons =
                schemeRegularityRepository.getNonSubmissionReasonSchemeCountByUser(tenantId, officerUserId.intValue(), reportDate, reportDate)
                        .stream()
                        .map(r -> DailyReportKpiDTO.ReasonCount.builder()
                                .reason(r.nonSubmissionReason())
                                .count(r.schemeCount() != null ? r.schemeCount() : 0)
                                .build())
                        .toList();

        List<DailyReportKpiDTO.TypeCount> anomalies = reportRepository.countAnomaliesByType(
                tenantId, officerUserId, istDayStartUtc(reportDate), istDayStartUtc(reportDate.plusDays(1)));

        // Section 2 — Priority Actions: per-scheme outage detail for the covered day (D-1).
        List<DailyReportKpiDTO.PriorityAction> priorityActions =
                reportRepository.listNoSupplyByScheme(tenantId, officerUserId, reportDate)
                        .stream()
                        .map(s -> DailyReportKpiDTO.PriorityAction.builder()
                                .schemeId(s.schemeId())
                                .issue(s.nonSubmissionReason())
                                .daysNoSupply(s.lastSupplyDate() == null
                                        ? null
                                        : (int) ChronoUnit.DAYS.between(s.lastSupplyDate(), reportDate))
                                .build())
                        .toList();

        // SDO-only: per-Section-Officer single-day Summary breakdown (covered day D-1).
        List<DailyReportKpiDTO.SectionOfficerSummary> sectionOfficerSummaries =
                (subordinateOfficerUserIds == null || subordinateOfficerUserIds.isEmpty())
                        ? List.of()
                        : subordinateOfficerUserIds.stream()
                                .map(soUserId -> buildOfficerSummary(tenantId, soUserId, reportDate))
                                .toList();

        return DailyReportKpiDTO.builder()
                .reportDate(reportDate.toString())
                .previousDate(previousDate.toString())
                .totalSchemes(totalSchemes)
                .yesterday(yesterday)
                .previousDay(previousDay)
                .reasonsForNoSupply(reasons)
                .anomaliesByType(anomalies)
                .priorityActions(priorityActions)
                .sectionOfficerSummaries(sectionOfficerSummaries)
                .build();
    }

    /**
     * Single-day Summary KPIs for one Section Officer, scoped to that officer's own schemes — the
     * per-officer row of the SDO report's breakdown table. Reuses the same {@link #buildDay} logic as
     * the main Summary section; officer name + mobile are resolved downstream (message-service).
     */
    public DailyReportKpiDTO.SectionOfficerSummary buildOfficerSummary(
            Integer tenantId, Long officerUserId, LocalDate reportDate) {
        int totalSchemes = safeCount(schemeRegularityRepository.getSchemeCountByUser(tenantId, officerUserId.intValue()));
        long population = reportRepository.populationServed(tenantId, officerUserId);
        DailyReportKpiDTO.DayKpis day = buildDay(tenantId, officerUserId, reportDate, totalSchemes, population);
        return DailyReportKpiDTO.SectionOfficerSummary.builder()
                .officerUserId(officerUserId)
                .totalSchemes(totalSchemes)
                .schemesSupplying(day.getSchemesSupplying())
                .schemesNotSupplying(day.getSchemesNotSupplying())
                .avgLpcd(day.getAvgLpcd())
                .avgMld(day.getAvgMld())
                .regularSupplyPctWeek(day.getRegularSupplyPctWeek())
                .readingSubmissionPct(day.getReadingSubmissionPct())
                .anomalousCount(day.getAnomalousCount())
                .build();
    }

    private DailyReportKpiDTO.DayKpis buildDay(
            Integer tenantId, Long userId, LocalDate day, int totalSchemes, long population) {

        int supplying = reportRepository.countSchemesSupplyingOnDay(tenantId, userId, day);
        int submitting = reportRepository.countSchemesSubmittingOnDay(tenantId, userId, day);
        long litres = reportRepository.sumWaterSuppliedOnDay(tenantId, userId, day);

        int supplyDaysWeek = reportRepository.sumSupplyDaysInRange(
                tenantId, userId, day.minusDays(SUPPLY_WINDOW_DAYS - 1L), day);

        int anomalyCount = reportRepository
                .countAnomaliesByType(tenantId, userId, istDayStartUtc(day), istDayStartUtc(day.plusDays(1)))
                .stream()
                .mapToInt(DailyReportKpiDTO.TypeCount::getCount)
                .sum();

        double avgMld = round1(litres / 1_000_000.0);
        double avgLpcd = population > 0 ? round1((double) litres / population) : 0.0;
        double regularSupplyPctWeek = totalSchemes > 0
                ? round1(100.0 * supplyDaysWeek / ((long) totalSchemes * SUPPLY_WINDOW_DAYS)) : 0.0;
        double readingSubmissionPct = totalSchemes > 0
                ? round1(100.0 * submitting / totalSchemes) : 0.0;

        return DailyReportKpiDTO.DayKpis.builder()
                .schemesSupplying(supplying)
                .schemesNotSupplying(Math.max(totalSchemes - supplying, 0))
                .avgLpcd(avgLpcd)
                .avgMld(avgMld)
                .regularSupplyPctWeek(regularSupplyPctWeek)
                .readingSubmissionPct(readingSubmissionPct)
                .anomalousCount(anomalyCount)
                .build();
    }

    /**
     * UTC-naive instant for the start of the given IST calendar day, matching how anomaly
     * {@code created_at} timestamps are stored. Used to build the half-open interval
     * {@code [reportDate 00:00 IST, next day 00:00 IST)} against UTC-stored timestamps.
     */
    private static LocalDateTime istDayStartUtc(LocalDate day) {
        return day.atStartOfDay(REPORT_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    private static int safeCount(Integer value) {
        return value != null ? value : 0;
    }

    private static double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
