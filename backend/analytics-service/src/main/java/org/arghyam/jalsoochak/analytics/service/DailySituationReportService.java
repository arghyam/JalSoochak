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

    private final DailySituationReportRepository reportRepository;
    private final SchemeRegularityRepository schemeRegularityRepository;

    public DailyReportKpiDTO buildReport(Integer tenantId, Long officerUserId, LocalDate reportDate) {
        LocalDate previousDate = reportDate.minusDays(1);

        int totalSchemes = safeCount(schemeRegularityRepository.getSchemeCountByUser(tenantId, officerUserId.intValue()));
        long population = reportRepository.populationServed(tenantId, officerUserId);

        DailyReportKpiDTO.DayKpis yesterday = buildDay(tenantId, officerUserId, reportDate, totalSchemes, population);
        DailyReportKpiDTO.DayKpis previousDay = buildDay(tenantId, officerUserId, previousDate, totalSchemes, population);

        // Section 3 + 4 are reported for the covered day (D-1) only.
        List<DailyReportKpiDTO.ReasonCount> reasons =
                schemeRegularityRepository.getOutageReasonSchemeCountByUser(tenantId, officerUserId.intValue(), reportDate, reportDate)
                        .stream()
                        .map(r -> DailyReportKpiDTO.ReasonCount.builder()
                                .reason(r.outageReason())
                                .count(r.schemeCount() != null ? r.schemeCount() : 0)
                                .build())
                        .toList();

        List<DailyReportKpiDTO.TypeCount> anomalies = reportRepository.countAnomaliesByType(
                tenantId, officerUserId, reportDate.atStartOfDay(), reportDate.plusDays(1).atStartOfDay());

        return DailyReportKpiDTO.builder()
                .reportDate(reportDate.toString())
                .previousDate(previousDate.toString())
                .totalSchemes(totalSchemes)
                .yesterday(yesterday)
                .previousDay(previousDay)
                .reasonsForNoSupply(reasons)
                .anomaliesByType(anomalies)
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
                .countAnomaliesByType(tenantId, userId, day.atStartOfDay(), day.plusDays(1).atStartOfDay())
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

    private static int safeCount(Integer value) {
        return value != null ? value : 0;
    }

    private static double round1(double value) {
        return BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP).doubleValue();
    }
}
