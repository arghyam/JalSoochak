package org.arghyam.jalsoochak.analytics.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.dto.DailyReportKpiDTO;
import org.arghyam.jalsoochak.analytics.repository.DailySituationReportRepository;
import org.arghyam.jalsoochak.analytics.repository.SchemeRegularityRepository;
import org.springframework.beans.factory.annotation.Value;
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
 *
 * <p>The SDO report's per-Section-Officer breakdown rows are additionally narrowed to the schemes
 * each Section Officer <em>shares</em> with the SDO — see
 * {@link #buildOfficerSummary(Integer, Long, LocalDate, Long)}.</p>
 */
@Service
@Slf4j
public class DailySituationReportService {

    private static final int SUPPLY_WINDOW_DAYS = 7;

    /** Report days are calendar days in IST; anomaly {@code created_at} is stored UTC-naive. */
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Kolkata");

    private final DailySituationReportRepository reportRepository;
    private final SchemeRegularityRepository schemeRegularityRepository;

    /**
     * Computes the data behind the report's Priority Actions and Reasons for No Water Supply sections.
     * Off by default, matching the same property in message-service, which hides both sections in the
     * rendered PDF — so the two outage queries are skipped rather than run for output nobody sees.
     * Flipping {@code DAILY_REPORT_OUTAGE_SECTIONS_ENABLED} in both services restores them with no
     * code change.
     */
    private final boolean outageDetailSectionsEnabled;

    public DailySituationReportService(
            DailySituationReportRepository reportRepository,
            SchemeRegularityRepository schemeRegularityRepository,
            @Value("${daily-report.sections.outage-details.enabled:false}") boolean outageDetailSectionsEnabled) {
        this.reportRepository = reportRepository;
        this.schemeRegularityRepository = schemeRegularityRepository;
        this.outageDetailSectionsEnabled = outageDetailSectionsEnabled;
    }

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

        int totalSchemes = safeCount(schemeRegularityRepository.getSchemeCountByUser(tenantId, officerUserId.intValue(), null));
        long population = reportRepository.populationServed(tenantId, officerUserId, null);

        DailyReportKpiDTO.DayKpis yesterday =
                buildDay(tenantId, officerUserId, reportDate, totalSchemes, population, null);
        DailyReportKpiDTO.DayKpis previousDay =
                buildDay(tenantId, officerUserId, previousDate, totalSchemes, population, null);

        // Reasons for No Water Supply + Priority Actions cover the report day (D-1) only, and are
        // computed only while the two outage sections are enabled (they are hidden by default).
        List<DailyReportKpiDTO.ReasonCount> reasons = buildOutageReasons(tenantId, officerUserId, reportDate);
        List<DailyReportKpiDTO.PriorityAction> priorityActions =
                buildPriorityActions(tenantId, officerUserId, reportDate);

        List<DailyReportKpiDTO.TypeCount> anomalies = reportRepository.countAnomaliesByType(
                tenantId, officerUserId, istDayStartUtc(reportDate), istDayStartUtc(reportDate.plusDays(1)), null);

        // SDO-only: per-Section-Officer single-day Summary breakdown (covered day D-1). Each row is
        // scoped to the schemes that Section Officer shares with this SDO — an SO's other schemes
        // belong to a different SDO's report, not this one.
        List<DailyReportKpiDTO.SectionOfficerSummary> sectionOfficerSummaries =
                (subordinateOfficerUserIds == null || subordinateOfficerUserIds.isEmpty())
                        ? List.of()
                        : subordinateOfficerUserIds.stream()
                                .map(soUserId -> buildOfficerSummary(tenantId, soUserId, reportDate, officerUserId))
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
     * Per-reason scheme counts behind the Reasons for No Water Supply section, or an empty list when
     * that section is disabled (see {@link #outageDetailSectionsEnabled}).
     */
    private List<DailyReportKpiDTO.ReasonCount> buildOutageReasons(
            Integer tenantId, Long officerUserId, LocalDate reportDate) {
        if (!outageDetailSectionsEnabled) {
            return List.of();
        }
        return schemeRegularityRepository
                .getOutageReasonSchemeCountByUser(tenantId, officerUserId.intValue(), reportDate, reportDate)
                .stream()
                .map(r -> DailyReportKpiDTO.ReasonCount.builder()
                        .reason(r.outageReason())
                        .count(r.schemeCount() != null ? r.schemeCount() : 0)
                        .build())
                .toList();
    }

    /**
     * Per-scheme outage detail behind the Priority Actions section, or an empty list when that section
     * is disabled (see {@link #outageDetailSectionsEnabled}). {@code daysNoSupply} is null for a scheme
     * that has never supplied water.
     */
    private List<DailyReportKpiDTO.PriorityAction> buildPriorityActions(
            Integer tenantId, Long officerUserId, LocalDate reportDate) {
        if (!outageDetailSectionsEnabled) {
            return List.of();
        }
        return reportRepository.listNoSupplyByScheme(tenantId, officerUserId, reportDate, null)
                .stream()
                .map(s -> DailyReportKpiDTO.PriorityAction.builder()
                        .schemeId(s.schemeId())
                        .issue(s.outageReason())
                        .daysNoSupply(daysSince(s.lastSupplyDate(), reportDate))
                        .build())
                .toList();
    }

    private static Integer daysSince(LocalDate lastSupplyDate, LocalDate reportDate) {
        return lastSupplyDate == null ? null : (int) ChronoUnit.DAYS.between(lastSupplyDate, reportDate);
    }

    /**
     * Single-day Summary KPIs for one Section Officer, scoped to that officer's own schemes. Reuses the
     * same {@link #buildDay} logic as the main Summary section; officer name + mobile are resolved
     * downstream (message-service).
     */
    public DailyReportKpiDTO.SectionOfficerSummary buildOfficerSummary(
            Integer tenantId, Long officerUserId, LocalDate reportDate) {
        return buildOfficerSummary(tenantId, officerUserId, reportDate, null);
    }

    /**
     * As {@link #buildOfficerSummary(Integer, Long, LocalDate)} but, when {@code supervisorUserId} is
     * non-null, every KPI is narrowed to the schemes this officer shares with that supervisor — the
     * per-officer row of the SDO report's breakdown table.
     *
     * <p>Without the narrowing a Section Officer's schemes that are <em>not</em> mapped to this SDO
     * would be counted in this SDO's report. Note the consequence: a Section Officer's own report
     * legitimately shows more schemes than the same officer's row in an SDO's breakdown.</p>
     */
    public DailyReportKpiDTO.SectionOfficerSummary buildOfficerSummary(
            Integer tenantId, Long officerUserId, LocalDate reportDate, Long supervisorUserId) {
        int totalSchemes = safeCount(schemeRegularityRepository.getSchemeCountByUser(
                tenantId, officerUserId.intValue(), supervisorUserId));
        long population = reportRepository.populationServed(tenantId, officerUserId, supervisorUserId);
        DailyReportKpiDTO.DayKpis day =
                buildDay(tenantId, officerUserId, reportDate, totalSchemes, population, supervisorUserId);
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

    /**
     * The single-day KPIs for one officer. A non-null {@code supervisorUserId} narrows every query to
     * the schemes the officer shares with that supervisor; {@code null} covers all of their schemes.
     */
    private DailyReportKpiDTO.DayKpis buildDay(
            Integer tenantId, Long userId, LocalDate day, int totalSchemes, long population,
            Long supervisorUserId) {

        int supplying = reportRepository.countSchemesSupplyingOnDay(tenantId, userId, day, supervisorUserId);
        int submitting = reportRepository.countSchemesSubmittingOnDay(tenantId, userId, day, supervisorUserId);
        long litres = reportRepository.sumWaterSuppliedOnDay(tenantId, userId, day, supervisorUserId);

        int supplyDaysWeek = reportRepository.sumSupplyDaysInRange(
                tenantId, userId, day.minusDays(SUPPLY_WINDOW_DAYS - 1L), day, supervisorUserId);

        int anomalyCount = reportRepository
                .countAnomaliesByType(tenantId, userId, istDayStartUtc(day), istDayStartUtc(day.plusDays(1)),
                        supervisorUserId)
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
