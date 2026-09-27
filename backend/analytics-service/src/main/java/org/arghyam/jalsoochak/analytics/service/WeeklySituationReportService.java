package org.arghyam.jalsoochak.analytics.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.dto.WeeklyReportKpiDTO;
import org.arghyam.jalsoochak.analytics.repository.TenantPopulationRepository;
import org.arghyam.jalsoochak.analytics.repository.WeeklySituationReportRepository;
import org.arghyam.jalsoochak.analytics.repository.WeeklySituationReportRepository.SchemeWeekSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

import static org.arghyam.jalsoochak.analytics.service.ReportMaths.perDayRatio;

/**
 * Builds the {@link WeeklyReportKpiDTO} for one officer over the week carried by the request,
 * with the week before it as the comparison column.
 *
 * <p>Both report variants are computed here; the officer's role selects only the supply-day
 * threshold and whether the per-Section-Officer table is populated. Everything is derived from the
 * per-scheme snapshots returned by {@link WeeklySituationReportRepository}, so a KPI count and the
 * list that explains it are always drawn from the same rows.</p>
 *
 * <p>Computes from {@code analytics_schema} only; no PII.</p>
 */
@Service
@Slf4j
public class WeeklySituationReportService {

    private static final int DAYS_IN_WEEK = 7;
    private static final String SDO_ROLE = "SUB_DIVISIONAL_OFFICER";

    private final WeeklySituationReportRepository reportRepository;
    private final TenantPopulationRepository tenantPopulationRepository;

    /**
     * The bar below which a scheme's weekly average LPCD is treated as under-supply. A service-level
     * judgement rather than a constant of the domain, so it is configurable; 15 matches the templates.
     */
    private final int lowLpcdThreshold;

    /**
     * Minimum supply-days for a scheme to count as "supplying" in each variant. The Section Officer's
     * report asks whether a scheme ran at all (1 day); the SDO's asks whether it ran often enough
     * (4 days). The asymmetry is deliberate and comes straight from the two templates — it means an
     * SO's own figure is normally higher than the same officer's row in their SDO's table.
     */
    private final int soMinSupplyDays;
    private final int sdoMinSupplyDays;

    public WeeklySituationReportService(
            WeeklySituationReportRepository reportRepository,
            TenantPopulationRepository tenantPopulationRepository,
            @Value("${water-reports.weekly.low-lpcd-threshold:15}") int lowLpcdThreshold,
            @Value("${water-reports.weekly.so-min-supply-days:1}") int soMinSupplyDays,
            @Value("${water-reports.weekly.sdo-min-supply-days:4}") int sdoMinSupplyDays) {
        this.reportRepository = reportRepository;
        this.tenantPopulationRepository = tenantPopulationRepository;
        this.lowLpcdThreshold = lowLpcdThreshold;
        this.soMinSupplyDays = soMinSupplyDays;
        this.sdoMinSupplyDays = sdoMinSupplyDays;
    }

    /**
     * @param officerUserType           SECTION_OFFICER or SUB_DIVISIONAL_OFFICER; selects the supply-day bar
     * @param subordinateOfficerUserIds Section Officers under an SDO; null/empty for an SO report
     */
    public WeeklyReportKpiDTO buildReport(Integer tenantId, Long officerUserId, String officerUserType,
                                          LocalDate weekStart, LocalDate weekEnd,
                                          LocalDate previousWeekStart, LocalDate previousWeekEnd,
                                          List<Long> subordinateOfficerUserIds) {
        boolean isSdo = SDO_ROLE.equalsIgnoreCase(officerUserType);
        int minSupplyDays = isSdo ? sdoMinSupplyDays : soMinSupplyDays;
        int personsPerHousehold = tenantPopulationRepository.personsPerHousehold(tenantId);

        List<SchemeWeekSnapshot> week =
                reportRepository.listSchemeWeekSnapshots(tenantId, officerUserId, weekStart, weekEnd, null);
        List<SchemeWeekSnapshot> previous = reportRepository.listSchemeWeekSnapshots(
                tenantId, officerUserId, previousWeekStart, previousWeekEnd, null);

        List<WeeklyReportKpiDTO.SectionOfficerWeekSummary> officerSummaries =
                (subordinateOfficerUserIds == null || subordinateOfficerUserIds.isEmpty())
                        ? List.of()
                        : subordinateOfficerUserIds.stream()
                                .map(soUserId -> buildOfficerSummary(tenantId, soUserId, officerUserId,
                                        weekStart, weekEnd, minSupplyDays, personsPerHousehold))
                                .toList();

        return WeeklyReportKpiDTO.builder()
                .weekStart(weekStart.toString())
                .weekEnd(weekEnd.toString())
                .previousWeekStart(previousWeekStart.toString())
                .previousWeekEnd(previousWeekEnd.toString())
                .week(summarise(week, minSupplyDays, personsPerHousehold))
                .previousWeek(summarise(previous, minSupplyDays, personsPerHousehold))
                .noSupplySchemeIds(schemeIds(week, s -> s.supplyDays() == 0))
                .lowSupplyDaysSchemeIds(schemeIds(week, s -> s.supplyDays() >= 1 && s.supplyDays() <= 3))
                .lowLpcdSchemeIds(schemeIds(week, s -> isLowLpcd(s, personsPerHousehold)))
                .sectionOfficerSummaries(officerSummaries)
                .build();
    }

    /**
     * One Section Officer's row in the SDO report, narrowed to the schemes that officer shares with
     * the SDO — an SO's other schemes belong to a different SDO's report, not this one.
     */
    private WeeklyReportKpiDTO.SectionOfficerWeekSummary buildOfficerSummary(
            Integer tenantId, Long soUserId, Long sdoUserId, LocalDate weekStart, LocalDate weekEnd,
            int minSupplyDays, int personsPerHousehold) {
        List<SchemeWeekSnapshot> shared =
                reportRepository.listSchemeWeekSnapshots(tenantId, soUserId, weekStart, weekEnd, sdoUserId);
        WeeklyReportKpiDTO.WeekKpis kpis = summarise(shared, minSupplyDays, personsPerHousehold);
        return WeeklyReportKpiDTO.SectionOfficerWeekSummary.builder()
                .officerUserId(soUserId)
                .totalSchemes(kpis.getTotalSchemes())
                .schemesSupplying(kpis.getSchemesSupplying())
                .schemesNotSupplying(kpis.getSchemesNotSupplying())
                .schemesLowLpcd(kpis.getSchemesLowLpcd())
                .avgLpcd(kpis.getAvgLpcd())
                .build();
    }

    private WeeklyReportKpiDTO.WeekKpis summarise(
            List<SchemeWeekSnapshot> schemes, int minSupplyDays, int personsPerHousehold) {
        long litres = schemes.stream().mapToLong(SchemeWeekSnapshot::litres).sum();
        long households = schemes.stream().mapToLong(SchemeWeekSnapshot::fhtc).sum();

        return WeeklyReportKpiDTO.WeekKpis.builder()
                .totalSchemes(schemes.size())
                .schemesSupplying((int) schemes.stream().filter(s -> s.supplyDays() >= minSupplyDays).count())
                .schemesNotSupplying((int) schemes.stream().filter(s -> s.supplyDays() == 0).count())
                .schemesLowLpcd((int) schemes.stream().filter(s -> isLowLpcd(s, personsPerHousehold)).count())
                // Summed before dividing, not an average of per-scheme LPCDs: the latter would weight a
                // 10-connection scheme the same as a 10,000-connection one.
                .avgLpcd(perDayRatio(litres, households * personsPerHousehold, DAYS_IN_WEEK))
                .build();
    }

    /**
     * Whether a scheme averaged at or below the low-LPCD bar across the week.
     *
     * <p>Two exclusions, both deliberate. A scheme with no connections has an <em>undefined</em> LPCD,
     * not a zero one, so it is not under-supplying. A scheme that supplied on no day is already listed
     * in full as a no-supply scheme; repeating it here would make this list read as "no supply" rather
     * than "ran, but too weakly", which is the distinction that makes it actionable.</p>
     */
    private boolean isLowLpcd(SchemeWeekSnapshot scheme, int personsPerHousehold) {
        if (scheme.fhtc() <= 0 || scheme.supplyDays() == 0) {
            return false;
        }
        return perDayRatio(scheme.litres(), scheme.fhtc() * personsPerHousehold, DAYS_IN_WEEK) <= lowLpcdThreshold;
    }

    private static List<Integer> schemeIds(
            List<SchemeWeekSnapshot> schemes, java.util.function.Predicate<SchemeWeekSnapshot> filter) {
        return schemes.stream().filter(filter).map(SchemeWeekSnapshot::schemeId).toList();
    }
}
