package org.arghyam.jalsoochak.analytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.dto.DailyReportKpiDTO;
import org.arghyam.jalsoochak.analytics.repository.DailySituationReportRepository;
import org.arghyam.jalsoochak.analytics.repository.DailySituationReportRepository.SchemeDaySnapshot;
import org.arghyam.jalsoochak.analytics.repository.TenantPopulationRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.arghyam.jalsoochak.analytics.service.ReportMaths.percentage;
import static org.arghyam.jalsoochak.analytics.service.ReportMaths.ratio;

/**
 * Builds the {@link DailyReportKpiDTO} for one Section Officer: the current IST day from 00:00 up to
 * the cut-off the scheduler ran at.
 *
 * <p>Every summary figure except litres and anomalies is derived from a single per-scheme result set
 * ({@link DailySituationReportRepository#listSchemeDaySnapshots}), so the counts, the household
 * percentages and the Section 2 list cannot disagree with one another — which separate queries
 * against a table still being written to mid-afternoon could.</p>
 *
 * <p>Computes from {@code analytics_schema} only; no PII.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DailySituationReportService {

    /** Report days are calendar days in IST; anomaly {@code created_at} is stored UTC-naive. */
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Kolkata");

    private final DailySituationReportRepository reportRepository;
    private final TenantPopulationRepository tenantPopulationRepository;

    /**
     * @param cutoffIst the instant the job ran, closing the day's window. Null falls back to the end
     *                  of the report day, which is the right answer for a manual rebuild of a past
     *                  day and harmless for today.
     */
    public DailyReportKpiDTO buildReport(Integer tenantId, Long officerUserId, LocalDate reportDate,
                                         LocalDateTime cutoffIst) {
        LocalDateTime windowEnd = cutoffIst != null ? cutoffIst : reportDate.plusDays(1).atStartOfDay();

        List<SchemeDaySnapshot> schemes = reportRepository.listSchemeDaySnapshots(tenantId, officerUserId, reportDate);

        int totalSchemes = schemes.size();
        int supplying = (int) schemes.stream().filter(SchemeDaySnapshot::supplied).count();

        long totalHouseholds = schemes.stream().mapToLong(SchemeDaySnapshot::fhtc).sum();
        long householdsWithSupply = schemes.stream()
                .filter(SchemeDaySnapshot::supplied)
                .mapToLong(SchemeDaySnapshot::fhtc)
                .sum();
        long householdsWithoutSupply = totalHouseholds - householdsWithSupply;

        // The "without" percentage is computed from its own count rather than as 100 - with, so the
        // two rows on the page each round independently instead of drifting to 100.1%.
        double pctWith = percentage(householdsWithSupply, totalHouseholds);
        double pctWithout = percentage(householdsWithoutSupply, totalHouseholds);

        long litres = reportRepository.sumWaterSuppliedOnDay(tenantId, officerUserId, reportDate);
        long populationSupplying = householdsWithSupply * tenantPopulationRepository.personsPerHousehold(tenantId);
        // Per the template footnote, this LPCD is "of schemes that have supplied water": it asks how
        // well the schemes that ran actually served, not how well the whole command area was served.
        double avgLpcd = ratio(litres, populationSupplying);

        LocalDateTime fromUtc = istToUtc(reportDate.atStartOfDay());
        LocalDateTime toUtc = istToUtc(windowEnd);

        int anomalousCount = reportRepository.countAnomalies(tenantId, officerUserId, fromUtc, toUtc);
        List<DailyReportKpiDTO.SchemeAnomaly> schemeAnomalies =
                reportRepository.listAnomaliesByScheme(tenantId, officerUserId, fromUtc, toUtc).stream()
                        .map(a -> DailyReportKpiDTO.SchemeAnomaly.builder()
                                .schemeId(a.schemeId())
                                .type(a.type())
                                .build())
                        .toList();

        List<Integer> noSupplySchemeIds = schemes.stream()
                .filter(s -> !s.supplied())
                .map(SchemeDaySnapshot::schemeId)
                .toList();

        return DailyReportKpiDTO.builder()
                .reportDate(reportDate.toString())
                .cutoffIst(windowEnd.toString())
                .totalSchemes(totalSchemes)
                .schemesSupplying(supplying)
                .schemesNotSupplying(Math.max(totalSchemes - supplying, 0))
                .householdsWithSupply(householdsWithSupply)
                .householdsWithSupplyPct(pctWith)
                .householdsWithoutSupply(householdsWithoutSupply)
                .householdsWithoutSupplyPct(pctWithout)
                .totalHouseholds(totalHouseholds)
                .avgLpcd(avgLpcd)
                .anomalousCount(anomalousCount)
                .noSupplySchemeIds(noSupplySchemeIds)
                .schemeAnomalies(schemeAnomalies)
                .build();
    }

    /**
     * Converts an IST wall-clock instant to the UTC-naive form anomaly {@code created_at} is stored
     * in, so the report's IST window lines up with timestamps written in UTC.
     */
    private static LocalDateTime istToUtc(LocalDateTime ist) {
        return ist.atZone(REPORT_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
