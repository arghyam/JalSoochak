package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import org.arghyam.jalsoochak.analytics.dto.response.AverageSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.AverageWaterSupplyResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NonSubmissionReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardBoundaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardLevel2BoundaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardLevel2MetricsResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardResponse;
import org.arghyam.jalsoochak.analytics.dto.response.OutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicOutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicNationalSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicWaterQuantityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.RegionWiseWaterQuantityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ReadingSubmissionRateResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SchemeRegularityListResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SchemeStatusAndTopReportingResponse;
import org.arghyam.jalsoochak.analytics.dto.response.CriticalSchemesResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ContinuousSchemesResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserNonSubmissionReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserOutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SubmissionStatusSummaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserSubmissionStatusResponse;
import org.arghyam.jalsoochak.analytics.entity.DimUser;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.enums.RegularityScope;
import org.arghyam.jalsoochak.analytics.enums.SchemeStatus;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.repository.AggregateReadRepository;
import org.arghyam.jalsoochak.analytics.repository.DimUserRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.repository.SchemeRegularityRepository;
import org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper;
import org.arghyam.jalsoochak.analytics.helper.RegularityThresholdFilter;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SchemeRegularityServiceImpl implements SchemeRegularityService {

    private static final String SCHEME_REGULARITY_CACHE_PREFIX = ":scheme_regularity";
    private static final String READING_SUBMISSION_RATE_CACHE_PREFIX = ":reading_submission_rate";
    private static final String NATIONAL_DASHBOARD_CACHE_PREFIX = ":national:dashboard";
    private static final String NATIONAL_DASHBOARD_BOUNDARY_CACHE_KEY = ":national:dashboard:boundaries:v1";
    private static final String NATIONAL_DASHBOARD_LEVEL2_BOUNDARY_CACHE_KEY = ":national:dashboard:boundaries:level2:v1";
    private static final String NATIONAL_DASHBOARD_LEVEL2_METRICS_CACHE_PREFIX = ":national:dashboard:metrics:level2";
    private static final String REGION_WISE_WATER_QUANTITY_CACHE_PREFIX = ":water_quantity:region_wise";
    private static final String PERIODIC_WATER_QUANTITY_CACHE_PREFIX = ":water_quantity:periodic";
    private static final String PERIODIC_SCHEME_REGULARITY_CACHE_PREFIX = ":scheme_regularity:periodic";
    private static final String OUTAGE_REASON_SCHEME_COUNT_CACHE_PREFIX = ":outage_reasons";
    private static final String PERIODIC_OUTAGE_REASON_SCHEME_COUNT_CACHE_PREFIX = ":outage_reasons:periodic";
    private static final String NON_SUBMISSION_REASON_SCHEME_COUNT_CACHE_PREFIX = ":non_submission_reasons";
    private static final String SUBMISSION_STATUS_SUMMARY_CACHE_PREFIX = ":submission_status:summary";
    private static final String SCHEME_STATUS_COUNT_CACHE_PREFIX = ":schemes:status-count";
    private static final String SCHEME_STATUS_TOP_REPORTING_CACHE_PREFIX = ":schemes:dashboard";
    private static final String SCHEME_REGION_REPORT_CACHE_PREFIX = ":schemes:region-report";
    private static final int DEFAULT_TOP_SCHEME_COUNT = 10;
    private static final int DEFAULT_PAGE_COUNT = 10;
    private static final String DEBUG_LOG_PATH = "/home/beehyv/Desktop/Codes/jalSoochak/JalSoochak_New/.cursor/debug.log";
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

    @Value("${analytics.scheduler.scheme-status.critical-after-days:30}")
    private int criticalAfterDays;

    /** When true, serve metrics from the pre-aggregation tables (legacy SQL is the fallback). */
    @Value("${analytics.read-from-aggregates:true}")
    private boolean readFromAggregates;

    /**
     * Dashboard response cache TTL in hours. Defaults to 1 so today's counts refresh
     * hourly (the hourly aggregation task re-rolls the current day each hour).
     */
    @Value("${analytics.cache.ttl-hours:1}")
    private long cacheTtlHours;

    /**
     * Trailing window (in days, inclusive) that a single-day regularity request expands to: a share-of-days
     * KPI is meaningless over one day, so {@code start == end} widens to this many trailing days. Applied to
     * the {@code /scheme-regularity/average} endpoints (whole response) and to the regularity slice of the
     * national dashboard (state- and district-wise; other KPIs stay on the literal window). Env-only (not
     * per-tenant). The {@code /periodic} endpoints never expand — their buckets stay literal.
     */
    @Value("${analytics.dashboard.regularity.single-day-lookback-days:30}")
    private int regularitySingleDayLookbackDays;

    private final SchemeRegularityRepository schemeRegularityRepository;
    private final AggregateReadRepository aggregateReadRepository;
    private final DimTenantRepository dimTenantRepository;
    private final DimUserRepository dimUserRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public AverageSchemeRegularityResponse getAverageSchemeRegularity(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);
        // Single-day requests expand to a trailing window before the cache key / query / reported window.
        startDate = expandSingleDayWindowStart(startDate, endDate);
        // #region agent log
        appendDebugLog(
                "H1",
                "SchemeRegularityServiceImpl:getAverageSchemeRegularity:entry",
                "Regularity request entry",
                Map.of("parentLgdId", parentLgdId, "startDate", String.valueOf(startDate), "endDate", String.valueOf(endDate)));
        // #endregion

        String cacheKey = SCHEME_REGULARITY_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":lgd:" + parentLgdId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v2";
        AverageSchemeRegularityResponse cached = readFromCache(cacheKey, AverageSchemeRegularityResponse.class);
        if (cached != null) {
            return cached;
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        SchemeRegularityRepository.SchemeRegularityMetrics metrics;
        try {
            metrics = aggregateRegionMetricsOrNull(tenantId, "LGD", parentLgdId, startDate, endDate, false);
            if (metrics == null) {
                metrics = schemeRegularityRepository.getSchemeRegularityMetrics(tenantId, parentLgdId, startDate, endDate);
            }
        } catch (Exception ex) {
            // #region agent log
            appendDebugLog(
                    "H2",
                    "SchemeRegularityServiceImpl:getAverageSchemeRegularity:repo_exception",
                    "Regularity repository call failed",
                    Map.of("errorType", ex.getClass().getName(), "errorMessage", String.valueOf(ex.getMessage())));
            // #endregion
            throw ex;
        }
        // #region agent log
        appendDebugLog(
                "H2",
                "SchemeRegularityServiceImpl:getAverageSchemeRegularity:repo_success",
                "Regularity repository call succeeded",
                Map.of("daysInRange", daysInRange, "schemeCount", metrics.schemeCount(), "regularSchemeCount", metrics.regularSchemeCount()));
        // #endregion

        BigDecimal thresholdPercent =
                schemeRegularityRepository.getEffectiveTenantRegularityThresholdPercent(tenantId);
        int thresholdDays = RegularityThresholdFilter.thresholdDays(daysInRange, thresholdPercent);
        BigDecimal averageRegularity =
                RegularityThresholdFilter.regularityRate(metrics.regularSchemeCount(), metrics.schemeCount());

        AverageSchemeRegularityResponse response = AverageSchemeRegularityResponse.builder()
                .lgdId(parentLgdId)
                .parentDepartmentId(null)
                .parentLgdLevel(null)
                .parentDepartmentLevel(null)
                .scope(RegularityScope.CURRENT.name().toLowerCase())
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .schemeCount(metrics.schemeCount())
                .totalSupplyDays(metrics.totalSupplyDays())
                .regularSchemeCount(metrics.regularSchemeCount())
                .averageRegularity(averageRegularity)
                .thresholdPercent(thresholdPercent)
                .thresholdDays(thresholdDays)
                .childRegionCount(0)
                .childRegions(List.of())
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    /**
     * Region scheme metrics from the pre-aggregation tables, or {@code null} when the
     * aggregate read path is disabled or has no DAY rows for the region/range (the
     * caller then falls back to the legacy raw-fact query). {@code useSubmissionDays}
     * selects total_submission_days (reading submitted) vs total_supply_days
     * (water supplied) for the {@code totalSupplyDays} slot of the returned record.
     *
     * <p>For the regularity KPI ({@code useSubmissionDays == false}) the "regular scheme"
     * count is computed from the base grain against the effective threshold (schemes supplying
     * on at least {@code thresholdDays} of the window), matching the legacy
     * {@code RegularityThresholdFilter} classification. The reading-submission-rate KPI
     * ({@code useSubmissionDays == true}) never classifies schemes as regular, so its regular
     * count is left 0.</p>
     */
    private SchemeRegularityRepository.SchemeRegularityMetrics aggregateRegionMetricsOrNull(
            Integer tenantId, String hierarchy, Integer regionId,
            LocalDate startDate, LocalDate endDate, boolean useSubmissionDays) {
        if (!readFromAggregates || regionId == null) {
            return null;
        }
        return aggregateReadRepository.getRegionMetrics(tenantId, hierarchy, regionId, startDate, endDate)
                .map(m -> {
                    int regularSchemeCount = 0;
                    if (!useSubmissionDays) {
                        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
                        BigDecimal thresholdPercent =
                                schemeRegularityRepository.getEffectiveTenantRegularityThresholdPercent(tenantId);
                        int thresholdDays = RegularityThresholdFilter.thresholdDays(daysInRange, thresholdPercent);
                        regularSchemeCount = (int) aggregateReadRepository.getRegularSchemeCount(
                                tenantId, hierarchy, regionId, startDate, endDate, thresholdDays);
                    }
                    return new SchemeRegularityRepository.SchemeRegularityMetrics(
                            m.schemeCount(),
                            (int) (useSubmissionDays ? m.totalSubmissionDays() : m.totalSupplyDays()),
                            regularSchemeCount);
                })
                .orElse(null);
    }

    /**
     * Periodic scheme-regularity is served from the legacy path. The per-bucket "regular scheme"
     * classification uses a per-bucket day count and threshold that are not pre-aggregated, so this
     * always returns {@code null} and the caller uses the legacy per-bucket SQL. (Periodic
     * water-quantity still uses the aggregate path; only the regularity series falls back here.)
     */
    private List<SchemeRegularityRepository.PeriodicSchemeRegularityMetrics> aggregatePeriodicRegularityOrNull(
            Integer tenantId, String hierarchy, Integer regionId,
            LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        return null;
    }

    /**
     * Parent-region reason distribution map (outage or non-submission) from the base
     * grain, falling back lazily to {@code legacy} when the aggregate path is off or
     * the region has no aggregated rows in range. {@code outage}=true reads
     * outage reasons, else non-submission reasons.
     */
    private Map<String, Integer> resolveReasonParentMap(
            Integer tenantId, String hierarchy, Integer regionId, boolean outage,
            LocalDate startDate, LocalDate endDate, java.util.function.Supplier<Map<String, Integer>> legacy) {
        if (readFromAggregates && regionId != null) {
            Optional<Map<String, Integer>> agg = aggregateReadRepository.getReasonDistribution(
                    tenantId, hierarchy, regionId, outage, startDate, endDate);
            if (agg.isPresent()) {
                return agg.get();
            }
        }
        return legacy.get();
    }

    /**
     * Submission-status summary (compliant/anomalous reading counts + scheme count)
     * from the pre-aggregation tables, or {@code null} to fall back to legacy SQL.
     */
    private SubmissionStatusSummaryResponse aggregateSubmissionStatusOrNull(
            Integer tenantId, String hierarchy, Integer regionId, LocalDate startDate, LocalDate endDate) {
        if (!readFromAggregates || regionId == null) {
            return null;
        }
        return aggregateReadRepository.getRegionMetrics(tenantId, hierarchy, regionId, startDate, endDate)
                .map(m -> SubmissionStatusSummaryResponse.builder()
                        .schemeCount(m.schemeCount())
                        .compliantSubmissionCount((int) m.compliantSubmissionCount())
                        .anomalousSubmissionCount((int) m.anomalousSubmissionCount())
                        .build())
                .orElse(null);
    }

    @Override
    public ReadingSubmissionRateResponse getReadingSubmissionRateByLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);
        // #region agent log
        appendDebugLog(
                "H3",
                "SchemeRegularityServiceImpl:getReadingSubmissionRateByLgd:entry",
                "Submission rate request entry",
                Map.of(
                        "tenantId", tenantId,
                        "parentLgdId", parentLgdId,
                        "startDate", String.valueOf(startDate),
                        "endDate", String.valueOf(endDate)));
        // #endregion

        String cacheKey = READING_SUBMISSION_RATE_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":lgd:" + parentLgdId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v3";
        ReadingSubmissionRateResponse cached = readFromCache(cacheKey, ReadingSubmissionRateResponse.class);
        if (cached != null) {
            return cached;
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        Integer parentLgdLevel = schemeRegularityRepository.getLgdLevelForTenant(tenantId, parentLgdId);
        SchemeRegularityRepository.SchemeRegularityMetrics metrics;
        try {
            metrics = aggregateRegionMetricsOrNull(tenantId, "LGD", parentLgdId, startDate, endDate, true);
            if (metrics == null) {
                metrics = schemeRegularityRepository.getReadingSubmissionRateMetricsByLgd(tenantId, parentLgdId, startDate, endDate);
            }
        } catch (Exception ex) {
            // #region agent log
            appendDebugLog(
                    "H3",
                    "SchemeRegularityServiceImpl:getReadingSubmissionRateByLgd:repo_exception",
                    "Submission rate repository call failed",
                    Map.of("errorType", ex.getClass().getName(), "errorMessage", String.valueOf(ex.getMessage())));
            // #endregion
            throw ex;
        }
        // #region agent log
        appendDebugLog(
                "H3",
                "SchemeRegularityServiceImpl:getReadingSubmissionRateByLgd:repo_success",
                "Submission rate repository call succeeded",
                Map.of("daysInRange", daysInRange, "schemeCount", metrics.schemeCount(), "totalSupplyDays", metrics.totalSupplyDays()));
        // #endregion

        BigDecimal readingSubmissionRate = BigDecimal.ZERO;
        if (metrics.schemeCount() > 0 && daysInRange > 0) {
            BigDecimal denominator = BigDecimal.valueOf((long) metrics.schemeCount() * daysInRange);
            readingSubmissionRate = BigDecimal.valueOf(metrics.totalSupplyDays())
                    .divide(denominator, 4, RoundingMode.HALF_UP);
        }

        ReadingSubmissionRateResponse response = ReadingSubmissionRateResponse.builder()
                .parentLgdId(parentLgdId)
                .parentDepartmentId(null)
                .parentLgdLevel(parentLgdLevel)
                .parentDepartmentLevel(null)
                .scope(RegularityScope.CURRENT.name().toLowerCase())
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .schemeCount(metrics.schemeCount())
                .totalSubmissionDays(metrics.totalSupplyDays())
                .readingSubmissionRate(readingSubmissionRate)
                .childRegionCount(0)
                .childRegions(List.of())
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public AverageSchemeRegularityResponse getAverageSchemeRegularityByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);
        // Single-day requests expand to a trailing window before the cache key / query / reported window.
        startDate = expandSingleDayWindowStart(startDate, endDate);

        String cacheKey = SCHEME_REGULARITY_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":department:" + parentDepartmentId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v2";
        AverageSchemeRegularityResponse cached = readFromCache(cacheKey, AverageSchemeRegularityResponse.class);
        if (cached != null) {
            return cached;
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        SchemeRegularityRepository.SchemeRegularityMetrics metrics =
                aggregateRegionMetricsOrNull(tenantId, "DEPT", parentDepartmentId, startDate, endDate, false);
        if (metrics == null) {
            metrics = schemeRegularityRepository.getSchemeRegularityMetricsByDepartment(
                    tenantId, parentDepartmentId, startDate, endDate);
        }

        BigDecimal thresholdPercent =
                schemeRegularityRepository.getEffectiveTenantRegularityThresholdPercent(tenantId);
        int thresholdDays = RegularityThresholdFilter.thresholdDays(daysInRange, thresholdPercent);
        BigDecimal averageRegularity =
                RegularityThresholdFilter.regularityRate(metrics.regularSchemeCount(), metrics.schemeCount());

        AverageSchemeRegularityResponse response = AverageSchemeRegularityResponse.builder()
                .lgdId(null)
                .parentDepartmentId(parentDepartmentId)
                .parentLgdLevel(null)
                .parentDepartmentLevel(null)
                .scope(RegularityScope.CURRENT.name().toLowerCase())
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .schemeCount(metrics.schemeCount())
                .totalSupplyDays(metrics.totalSupplyDays())
                .regularSchemeCount(metrics.regularSchemeCount())
                .averageRegularity(averageRegularity)
                .thresholdPercent(thresholdPercent)
                .thresholdDays(thresholdDays)
                .childRegionCount(0)
                .childRegions(List.of())
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public AverageSchemeRegularityResponse getAverageSchemeRegularityForChildRegions(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);
        // Single-day requests expand to a trailing window before the cache key / query / reported window.
        startDate = expandSingleDayWindowStart(startDate, endDate);

        String cacheKey = SCHEME_REGULARITY_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":lgd:" + parentLgdId
                + ":scope:child"
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v2";
        AverageSchemeRegularityResponse cached = readFromCache(cacheKey, AverageSchemeRegularityResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentLgdLevel = schemeRegularityRepository.getLgdLevelForTenant(tenantId, parentLgdId);
        if (parentLgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        if (parentLgdLevel >= 6) {
            throw new IllegalArgumentException("No child LGD level available for parent_lgd_id: " + parentLgdId);
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        List<SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics> metrics =
                aggregateChildRegularityOrNull(tenantId, "LGD", parentLgdId, parentLgdLevel, startDate, endDate, daysInRange);
        if (metrics == null) {
            metrics = schemeRegularityRepository.getChildSchemeRegularityMetricsByLgd(
                    tenantId, parentLgdId, startDate, endDate);
        }

        List<AverageSchemeRegularityResponse.ChildRegionRegularity> childRegions = metrics.stream()
                .map(m -> AverageSchemeRegularityResponse.ChildRegionRegularity.builder()
                        .lgdId(m.lgdId())
                        .departmentId(null)
                        .title(m.title())
                        .schemeCount(m.schemeCount())
                        .totalSupplyDays(m.totalSupplyDays())
                        .regularSchemeCount(m.regularSchemeCount())
                        .averageRegularity(m.averageRegularity())
                        .build())
                .toList();

        int totalSchemeCount = metrics.stream()
                .mapToInt(SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics::schemeCount)
                .sum();
        int totalSupplyDays = metrics.stream()
                .mapToInt(SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics::totalSupplyDays)
                .sum();
        int totalRegularSchemeCount = metrics.stream()
                .mapToInt(SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics::regularSchemeCount)
                .sum();
        BigDecimal thresholdPercent =
                schemeRegularityRepository.getEffectiveTenantRegularityThresholdPercent(tenantId);
        int thresholdDays = RegularityThresholdFilter.thresholdDays(daysInRange, thresholdPercent);
        BigDecimal averageRegularity =
                RegularityThresholdFilter.regularityRate(totalRegularSchemeCount, totalSchemeCount);

        AverageSchemeRegularityResponse response = AverageSchemeRegularityResponse.builder()
                .lgdId(parentLgdId)
                .parentDepartmentId(null)
                .parentLgdLevel(parentLgdLevel)
                .parentDepartmentLevel(null)
                .scope(RegularityScope.CHILD.name().toLowerCase())
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .schemeCount(totalSchemeCount)
                .totalSupplyDays(totalSupplyDays)
                .regularSchemeCount(totalRegularSchemeCount)
                .averageRegularity(averageRegularity)
                .thresholdPercent(thresholdPercent)
                .thresholdDays(thresholdDays)
                .childRegionCount(childRegions.size())
                .childRegions(childRegions)
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public AverageSchemeRegularityResponse getAverageSchemeRegularityByDepartmentForChildRegions(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);
        // Single-day requests expand to a trailing window before the cache key / query / reported window.
        startDate = expandSingleDayWindowStart(startDate, endDate);

        String cacheKey = SCHEME_REGULARITY_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":department:" + parentDepartmentId
                + ":scope:child"
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v2";
        AverageSchemeRegularityResponse cached = readFromCache(cacheKey, AverageSchemeRegularityResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentDepartmentLevel =
                schemeRegularityRepository.getDepartmentLevelForTenant(tenantId, parentDepartmentId);
        if (parentDepartmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        if (parentDepartmentLevel >= 6) {
            throw new IllegalArgumentException("No child department level available for parent_department_id: " + parentDepartmentId);
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        List<SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics> metrics =
                aggregateChildRegularityOrNull(tenantId, "DEPT", parentDepartmentId, parentDepartmentLevel, startDate, endDate, daysInRange);
        if (metrics == null) {
            metrics = schemeRegularityRepository.getChildSchemeRegularityMetricsByDepartment(
                    tenantId, parentDepartmentId, startDate, endDate);
        }

        List<AverageSchemeRegularityResponse.ChildRegionRegularity> childRegions = metrics.stream()
                .map(m -> AverageSchemeRegularityResponse.ChildRegionRegularity.builder()
                        .lgdId(null)
                        .departmentId(m.departmentId())
                        .title(m.title())
                        .schemeCount(m.schemeCount())
                        .totalSupplyDays(m.totalSupplyDays())
                        .regularSchemeCount(m.regularSchemeCount())
                        .averageRegularity(m.averageRegularity())
                        .build())
                .toList();

        int totalSchemeCount = metrics.stream()
                .mapToInt(SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics::schemeCount)
                .sum();
        int totalSupplyDays = metrics.stream()
                .mapToInt(SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics::totalSupplyDays)
                .sum();
        int totalRegularSchemeCount = metrics.stream()
                .mapToInt(SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics::regularSchemeCount)
                .sum();
        BigDecimal thresholdPercent =
                schemeRegularityRepository.getEffectiveTenantRegularityThresholdPercent(tenantId);
        int thresholdDays = RegularityThresholdFilter.thresholdDays(daysInRange, thresholdPercent);
        BigDecimal averageRegularity =
                RegularityThresholdFilter.regularityRate(totalRegularSchemeCount, totalSchemeCount);

        AverageSchemeRegularityResponse response = AverageSchemeRegularityResponse.builder()
                .lgdId(null)
                .parentDepartmentId(parentDepartmentId)
                .parentLgdLevel(null)
                .parentDepartmentLevel(parentDepartmentLevel)
                .scope(RegularityScope.CHILD.name().toLowerCase())
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .schemeCount(totalSchemeCount)
                .totalSupplyDays(totalSupplyDays)
                .regularSchemeCount(totalRegularSchemeCount)
                .averageRegularity(averageRegularity)
                .thresholdPercent(thresholdPercent)
                .thresholdDays(thresholdDays)
                .childRegionCount(childRegions.size())
                .childRegions(childRegions)
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public ReadingSubmissionRateResponse getReadingSubmissionRateByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);

        String cacheKey = READING_SUBMISSION_RATE_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":department:" + parentDepartmentId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v3";
        ReadingSubmissionRateResponse cached = readFromCache(cacheKey, ReadingSubmissionRateResponse.class);
        if (cached != null) {
            return cached;
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        Integer parentDepartmentLevel = schemeRegularityRepository.getDepartmentLevelForTenant(tenantId, parentDepartmentId);
        SchemeRegularityRepository.SchemeRegularityMetrics metrics =
                aggregateRegionMetricsOrNull(tenantId, "DEPT", parentDepartmentId, startDate, endDate, true);
        if (metrics == null) {
            metrics = schemeRegularityRepository.getReadingSubmissionRateMetricsByDepartment(
                    tenantId, parentDepartmentId, startDate, endDate);
        }

        BigDecimal readingSubmissionRate = BigDecimal.ZERO;
        if (metrics.schemeCount() > 0 && daysInRange > 0) {
            BigDecimal denominator = BigDecimal.valueOf((long) metrics.schemeCount() * daysInRange);
            readingSubmissionRate = BigDecimal.valueOf(metrics.totalSupplyDays())
                    .divide(denominator, 4, RoundingMode.HALF_UP);
        }

        ReadingSubmissionRateResponse response = ReadingSubmissionRateResponse.builder()
                .parentLgdId(null)
                .parentDepartmentId(parentDepartmentId)
                .parentLgdLevel(null)
                .parentDepartmentLevel(parentDepartmentLevel)
                .scope(RegularityScope.CURRENT.name().toLowerCase())
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .schemeCount(metrics.schemeCount())
                .totalSubmissionDays(metrics.totalSupplyDays())
                .readingSubmissionRate(readingSubmissionRate)
                .childRegionCount(0)
                .childRegions(List.of())
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public ReadingSubmissionRateResponse getReadingSubmissionRateByLgdForChildRegions(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);

        String cacheKey = READING_SUBMISSION_RATE_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":lgd:" + parentLgdId
                + ":scope:child"
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v3";
        ReadingSubmissionRateResponse cached = readFromCache(cacheKey, ReadingSubmissionRateResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentLgdLevel = schemeRegularityRepository.getLgdLevelForTenant(tenantId, parentLgdId);
        if (parentLgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        if (parentLgdLevel >= 6) {
            throw new IllegalArgumentException("No child LGD level available for parent_lgd_id: " + parentLgdId);
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        List<SchemeRegularityRepository.ChildRegionReadingSubmissionMetrics> metrics =
                aggregateChildSubmissionOrNull(tenantId, "LGD", parentLgdId, parentLgdLevel, startDate, endDate, daysInRange);
        if (metrics == null) {
            metrics = schemeRegularityRepository.getChildReadingSubmissionRateMetricsByLgd(
                    tenantId, parentLgdId, startDate, endDate);
        }

        List<ReadingSubmissionRateResponse.ChildRegionReadingSubmissionRate> childRegions = metrics.stream()
                .map(m -> ReadingSubmissionRateResponse.ChildRegionReadingSubmissionRate.builder()
                        .lgdId(m.lgdId())
                        .departmentId(null)
                        .title(m.title())
                        .schemeCount(m.schemeCount())
                        .totalSubmissionDays(m.totalSubmissionDays())
                        .readingSubmissionRate(m.readingSubmissionRate())
                        .build())
                .toList();

        int totalSchemeCount = metrics.stream()
                .map(SchemeRegularityRepository.ChildRegionReadingSubmissionMetrics::schemeCount)
                .mapToInt(Integer::intValue)
                .sum();
        int totalSubmissionDays = metrics.stream()
                .map(SchemeRegularityRepository.ChildRegionReadingSubmissionMetrics::totalSubmissionDays)
                .mapToInt(Integer::intValue)
                .sum();
        BigDecimal readingSubmissionRate = BigDecimal.ZERO;
        if (totalSchemeCount > 0 && daysInRange > 0) {
            readingSubmissionRate = BigDecimal.valueOf(totalSubmissionDays)
                    .divide(BigDecimal.valueOf((long) totalSchemeCount * daysInRange), 4, RoundingMode.HALF_UP);
        }

        ReadingSubmissionRateResponse response = ReadingSubmissionRateResponse.builder()
                .parentLgdId(parentLgdId)
                .parentDepartmentId(null)
                .parentLgdLevel(parentLgdLevel)
                .parentDepartmentLevel(null)
                .scope(RegularityScope.CHILD.name().toLowerCase())
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .schemeCount(totalSchemeCount)
                .totalSubmissionDays(totalSubmissionDays)
                .readingSubmissionRate(readingSubmissionRate)
                .childRegionCount(childRegions.size())
                .childRegions(childRegions)
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public ReadingSubmissionRateResponse getReadingSubmissionRateByDepartmentForChildRegions(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);

        String cacheKey = READING_SUBMISSION_RATE_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":department:" + parentDepartmentId
                + ":scope:child"
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v3";
        ReadingSubmissionRateResponse cached = readFromCache(cacheKey, ReadingSubmissionRateResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentDepartmentLevel = schemeRegularityRepository.getDepartmentLevelForTenant(tenantId, parentDepartmentId);
        if (parentDepartmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        if (parentDepartmentLevel >= 6) {
            throw new IllegalArgumentException("No child department level available for parent_department_id: " + parentDepartmentId);
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        List<SchemeRegularityRepository.ChildRegionReadingSubmissionMetrics> metrics =
                aggregateChildSubmissionOrNull(tenantId, "DEPT", parentDepartmentId, parentDepartmentLevel, startDate, endDate, daysInRange);
        if (metrics == null) {
            metrics = schemeRegularityRepository.getChildReadingSubmissionRateMetricsByDepartment(
                    tenantId, parentDepartmentId, startDate, endDate);
        }

        List<ReadingSubmissionRateResponse.ChildRegionReadingSubmissionRate> childRegions = metrics.stream()
                .map(m -> ReadingSubmissionRateResponse.ChildRegionReadingSubmissionRate.builder()
                        .lgdId(null)
                        .departmentId(m.departmentId())
                        .title(m.title())
                        .schemeCount(m.schemeCount())
                        .totalSubmissionDays(m.totalSubmissionDays())
                        .readingSubmissionRate(m.readingSubmissionRate())
                        .build())
                .toList();

        int totalSchemeCount = metrics.stream()
                .map(SchemeRegularityRepository.ChildRegionReadingSubmissionMetrics::schemeCount)
                .mapToInt(Integer::intValue)
                .sum();
        int totalSubmissionDays = metrics.stream()
                .map(SchemeRegularityRepository.ChildRegionReadingSubmissionMetrics::totalSubmissionDays)
                .mapToInt(Integer::intValue)
                .sum();
        BigDecimal readingSubmissionRate = BigDecimal.ZERO;
        if (totalSchemeCount > 0 && daysInRange > 0) {
            readingSubmissionRate = BigDecimal.valueOf(totalSubmissionDays)
                    .divide(BigDecimal.valueOf((long) totalSchemeCount * daysInRange), 4, RoundingMode.HALF_UP);
        }

        ReadingSubmissionRateResponse response = ReadingSubmissionRateResponse.builder()
                .parentLgdId(null)
                .parentDepartmentId(parentDepartmentId)
                .parentLgdLevel(null)
                .parentDepartmentLevel(parentDepartmentLevel)
                .scope(RegularityScope.CHILD.name().toLowerCase())
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .schemeCount(totalSchemeCount)
                .totalSubmissionDays(totalSubmissionDays)
                .readingSubmissionRate(readingSubmissionRate)
                .childRegionCount(childRegions.size())
                .childRegions(childRegions)
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public BigDecimal getAveragePerformanceScoreByLgd(
            Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);
        return schemeRegularityRepository.getAveragePerformanceScoreByLgd(parentLgdId, startDate, endDate);
    }

    @Override
    public BigDecimal getAveragePerformanceScoreByDepartment(
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);
        return schemeRegularityRepository.getAveragePerformanceScoreByDepartment(
                parentDepartmentId, startDate, endDate);
    }

    @Override
    public List<SchemeRegularityRepository.ChildRegionPerformanceScore> getChildAveragePerformanceScoreByLgd(
            Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);
        String cacheKey = ":performance_score:child:lgd:"
                + parentLgdId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v1";

        List<SchemeRegularityRepository.ChildRegionPerformanceScore> cached = readFromCache(
                cacheKey,
                new TypeReference<>() {
                });
        if (cached != null) {
            return cached;
        }

        List<SchemeRegularityRepository.ChildRegionPerformanceScore> response =
                schemeRegularityRepository.getChildAveragePerformanceScoreByLgd(parentLgdId, startDate, endDate);
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public List<SchemeRegularityRepository.ChildRegionPerformanceScore> getChildAveragePerformanceScoreByDepartment(
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);
        String cacheKey = ":performance_score:child:department:"
                + parentDepartmentId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v1";

        List<SchemeRegularityRepository.ChildRegionPerformanceScore> cached = readFromCache(
                cacheKey,
                new TypeReference<>() {
                });
        if (cached != null) {
            return cached;
        }

        List<SchemeRegularityRepository.ChildRegionPerformanceScore> response =
                schemeRegularityRepository.getChildAveragePerformanceScoreByDepartment(
                        parentDepartmentId, startDate, endDate);
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public AverageWaterSupplyResponse getAverageWaterSupplyPerCurrentRegion(
            Integer tenantId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateDateRange(startDate, endDate);

        String cacheKey = ":water_supply:tenant:" + tenantId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v4";
        AverageWaterSupplyResponse cached = readFromCache(cacheKey, AverageWaterSupplyResponse.class);
        if (cached != null) {
            return cached;
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        List<SchemeRegularityRepository.SchemeWaterSupplyMetrics> metrics =
                aggregateSchemeWaterSupplyOrNull(tenantId, startDate, endDate, daysInRange);
        if (metrics == null) {
            metrics = schemeRegularityRepository.getAverageWaterSupplyPerCurrentRegion(tenantId, startDate, endDate);
        }

        List<AverageWaterSupplyResponse.SchemeWaterSupply> schemes = metrics.stream()
                .map(m -> AverageWaterSupplyResponse.SchemeWaterSupply.builder()
                        .schemeId(m.schemeId())
                        .schemeName(m.schemeName())
                        .householdCount(m.householdCount())
                        .achievedFhtcCount(m.achievedFhtcCount())
                        .plannedFhtcCount(m.plannedFhtcCount())
                        .totalWaterSuppliedLiters(m.totalWaterSuppliedLiters())
                        .supplyDays(m.supplyDays())
                        .avgLitersPerHousehold(m.averageLitersPerHousehold())
                        .build())
                .toList();

        AverageWaterSupplyResponse response = AverageWaterSupplyResponse.builder()
                .tenantId(tenantId)
                .stateCode(getTenantStateCode(tenantId))
                .parentLgdLevel(null)
                .parentDepartmentLevel(null)
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .schemeCount(schemes.size())
                .schemes(schemes)
                .childRegionCount(0)
                .childRegions(List.of())
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public AverageWaterSupplyResponse getAverageWaterSupplyPerCurrentRegionForCurrentScope(
            Integer tenantId, LocalDate startDate, LocalDate endDate) {
        AverageWaterSupplyResponse response =
                getAverageWaterSupplyPerCurrentRegion(tenantId, startDate, endDate);
        // Contract: `scope=current` should not expose LGD/department child rows.
        response.setChildRegionCount(null);
        response.setChildRegions(null);
        return response;
    }

    @Override
    public AverageWaterSupplyResponse getAverageWaterSupplyPerNation(
            LocalDate startDate, LocalDate endDate) {
        validateDateRange(startDate, endDate);

        String cacheKey = ":water_supply:nation"
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v4";
        AverageWaterSupplyResponse cached = readFromCache(cacheKey, AverageWaterSupplyResponse.class);
        if (cached != null) {
            return cached;
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        List<SchemeRegularityRepository.ChildRegionWaterSupplyMetrics> metrics =
                schemeRegularityRepository.getAverageWaterSupplyPerNation(startDate, endDate);

        List<AverageWaterSupplyResponse.ChildRegionWaterSupply> childRegions = metrics.stream()
                .map(m -> AverageWaterSupplyResponse.ChildRegionWaterSupply.builder()
                        .lgdId(null)
                        .departmentId(null)
                        .title(m.title())
                        .totalHouseholdCount(m.totalHouseholdCount())
                        .totalAchievedFhtcCount(m.totalAchievedFhtcCount())
                        .totalPlannedFhtcCount(m.totalPlannedFhtcCount())
                        .totalWaterSuppliedLiters(m.totalWaterSuppliedLiters())
                        .schemeCount(m.schemeCount())
                        .avgWaterSupplyPerScheme(m.avgWaterSupplyPerScheme())
                        .build())
                .toList();

        AverageWaterSupplyResponse response = AverageWaterSupplyResponse.builder()
                .tenantId(null)
                .stateCode(null)
                .parentLgdLevel(null)
                .parentDepartmentLevel(null)
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .schemeCount(0)
                .schemes(List.of())
                .childRegionCount(childRegions.size())
                .childRegions(childRegions)
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public AverageWaterSupplyResponse getAverageWaterSupplyPerNationForChildScope(
            LocalDate startDate, LocalDate endDate) {
        AverageWaterSupplyResponse response = getAverageWaterSupplyPerNation(startDate, endDate);
        // Contract: `scope=child` at nation level should not expose scheme-level rows.
        response.setSchemeCount(null);
        response.setSchemes(null);
        return response;
    }

    @Override
    public NationalDashboardResponse getNationalDashboard(LocalDate startDate, LocalDate endDate) {
        validateDateRange(startDate, endDate);

        String cacheKey = buildNationalDashboardCacheKey(startDate, endDate);
        NationalDashboardResponse cached = readFromCache(cacheKey, NationalDashboardResponse.class);
        if (cached != null) {
            return cached;
        }
        return buildAndCacheNationalDashboard(startDate, endDate, cacheKey);
    }

    @Override
    public NationalDashboardResponse refreshNationalDashboard(LocalDate startDate, LocalDate endDate) {
        validateDateRange(startDate, endDate);

        String cacheKey = buildNationalDashboardCacheKey(startDate, endDate);
        return buildAndCacheNationalDashboard(startDate, endDate, cacheKey);
    }

    @Override
    public NationalDashboardResponse getNationalDashboardForApi(LocalDate startDate, LocalDate endDate) {
        return getNationalDashboard(startDate, endDate);
    }

    @Override
    public NationalDashboardBoundaryResponse getNationalDashboardBoundariesForApi() {
        NationalDashboardBoundaryResponse cached =
                readFromCache(NATIONAL_DASHBOARD_BOUNDARY_CACHE_KEY, NationalDashboardBoundaryResponse.class);
        if (cached != null) {
            return cached;
        }
        return buildAndCacheNationalDashboardBoundaries();
    }

    @Override
    public NationalDashboardLevel2BoundaryResponse getNationalDashboardLevel2BoundariesForApi() {
        NationalDashboardLevel2BoundaryResponse cached =
                readFromCache(NATIONAL_DASHBOARD_LEVEL2_BOUNDARY_CACHE_KEY, NationalDashboardLevel2BoundaryResponse.class);
        if (cached != null) {
            return cached;
        }
        return buildAndCacheNationalDashboardLevel2Boundaries();
    }

    @Override
    public NationalDashboardLevel2MetricsResponse getNationalDashboardLevel2MetricsForApi(
            LocalDate startDate, LocalDate endDate) {
        validateDateRange(startDate, endDate);

        String cacheKey = NATIONAL_DASHBOARD_LEVEL2_METRICS_CACHE_PREFIX
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v1";
        NationalDashboardLevel2MetricsResponse cached =
                readFromCache(cacheKey, NationalDashboardLevel2MetricsResponse.class);
        if (cached != null) {
            return cached;
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;

        if (readFromAggregates) {
            Optional<List<AggregateReadRepository.NationalRegionRow>> agg =
                    aggregateReadRepository.getNationalRegionMetrics(2, startDate, endDate);
            if (agg.isPresent()) {
                NationalDashboardLevel2MetricsResponse aggResponse =
                        buildNationalLevel2FromAggregate(agg.get(), startDate, endDate, daysInRange);
                writeToCache(cacheKey, aggResponse);
                return aggResponse;
            }
        }

        List<SchemeRegularityRepository.Level2WaterSupplyMetrics> quantityRows =
                schemeRegularityRepository.getLgdLevel2WiseWaterSupplyMetricsForNation(startDate, endDate);
        List<SchemeRegularityRepository.Level2SupplyDaysInEfficientRange> efficientRangeRows =
                schemeRegularityRepository.getLgdLevel2WiseSupplyDaysInEfficientRangeForNation(startDate, endDate);
        // Regularity over a single day is a degenerate share-of-days KPI, so widen just the regularity
        // window to a trailing lookback (water-quantity and reading-submission stay on the literal
        // requested range). The scheme-count denominator is window-independent, so the rate stays in [0,1].
        LocalDate regularityStartDate = expandSingleDayWindowStart(startDate, endDate);
        List<SchemeRegularityRepository.Level2RegularityMetrics> regularityRows =
                schemeRegularityRepository.getLgdLevel2WiseRegularityMetricsForNation(regularityStartDate, endDate);
        List<SchemeRegularityRepository.Level2ReadingSubmissionMetrics> submissionRows =
                schemeRegularityRepository.getLgdLevel2WiseReadingSubmissionMetricsForNation(startDate, endDate);

        List<SchemeRegularityRepository.OutageReasonSchemeCount> outageRows =
                schemeRegularityRepository.getOverallOutageReasonSchemeCount(startDate, endDate);
        Map<String, Integer> overallOutageReasonDistribution = buildReasonCountMap(outageRows);

        record Key(Integer tenantId, Integer lgdId) {}

        Map<Key, Long> supplyDaysInEfficientRangeByKey = efficientRangeRows.stream()
                .filter(r -> r.tenantId() != null && r.lgdId() != null)
                .collect(Collectors.toMap(
                        r -> new Key(r.tenantId(), r.lgdId()),
                        r -> r.supplyDaysInEfficientRange() != null ? r.supplyDaysInEfficientRange() : 0L,
                        (a, b) -> a,
                        LinkedHashMap::new));

        Map<Key, SchemeRegularityRepository.Level2RegularityMetrics> regularityByKey = regularityRows.stream()
                .filter(r -> r.tenantId() != null && r.lgdId() != null)
                .collect(Collectors.toMap(
                        r -> new Key(r.tenantId(), r.lgdId()),
                        Function.identity(),
                        (a, b) -> a,
                        LinkedHashMap::new));

        Map<Key, SchemeRegularityRepository.Level2ReadingSubmissionMetrics> submissionByKey = submissionRows.stream()
                .filter(r -> r.tenantId() != null && r.lgdId() != null)
                .collect(Collectors.toMap(
                        r -> new Key(r.tenantId(), r.lgdId()),
                        Function.identity(),
                        (a, b) -> a,
                        LinkedHashMap::new));

        List<NationalDashboardLevel2MetricsResponse.LgdLevel2MetricsRow> districts = quantityRows.stream()
                .filter(r -> r.tenantId() != null && r.lgdId() != null)
                .map(row -> {
                    Key key = new Key(row.tenantId(), row.lgdId());
                    SchemeRegularityRepository.Level2RegularityMetrics reg = regularityByKey.get(key);
                    SchemeRegularityRepository.Level2ReadingSubmissionMetrics sub = submissionByKey.get(key);

                    Integer schemeCount = row.schemeCount();
                    Integer totalSupplyDays = reg != null ? reg.totalSupplyDays() : 0;
                    // reg.schemeCount() shares the same {{NWS}} scheme universe as row.schemeCount(), so the
                    // regular count is a subset of the displayed count (rate in [0,1]).
                    int regularSchemeCount = reg != null ? reg.regularSchemeCount() : 0;
                    Integer totalSubmissionDays = sub != null ? sub.totalSubmissionDays() : 0;

                    BigDecimal averageRegularity = RegularityThresholdFilter.regularityRate(
                            regularSchemeCount, schemeCount != null ? schemeCount : 0);

                    BigDecimal readingSubmissionRate = BigDecimal.ZERO;
                    if (schemeCount != null && schemeCount > 0 && daysInRange > 0) {
                        readingSubmissionRate = BigDecimal.valueOf(totalSubmissionDays)
                                .divide(BigDecimal.valueOf((long) schemeCount * daysInRange), 4, RoundingMode.HALF_UP);
                    }

                    return NationalDashboardLevel2MetricsResponse.LgdLevel2MetricsRow.builder()
                            .tenantId(row.tenantId())
                            .lgdId(row.lgdId())
                            .tenantStatus(row.tenantStatus())
                            .stateCode(row.stateCode())
                            .stateTitle(row.stateTitle())
                            .districtTitle(row.districtTitle())
                            .schemeCount(schemeCount)
                            .totalHouseholdCount(row.totalHouseholdCount())
                            .totalAchievedFhtcCount(row.totalAchievedFhtcCount())
                            .totalPlannedFhtcCount(row.totalPlannedFhtcCount())
                            .totalWaterSuppliedLiters(row.totalWaterSuppliedLiters())
                            .avgWaterSupplyPerScheme(row.avgWaterSupplyPerScheme())
                            .supplyDaysInEfficientRange(
                                    supplyDaysInEfficientRangeByKey.getOrDefault(key, 0L))
                            .totalSupplyDays(totalSupplyDays)
                            .regularSchemeCount(regularSchemeCount)
                            .averageRegularity(averageRegularity)
                            .totalSubmissionDays(totalSubmissionDays)
                            .readingSubmissionRate(readingSubmissionRate)
                            .build();
                })
                .toList();

        NationalDashboardLevel2MetricsResponse response = NationalDashboardLevel2MetricsResponse.builder()
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .regularityStartDate(regularityStartDate)
                .regularityDaysInRange((int) ChronoUnit.DAYS.between(regularityStartDate, endDate) + 1)
                .overallOutageReasonDistribution(overallOutageReasonDistribution)
                .districts(districts)
                .build();

        writeToCache(cacheKey, response);
        return response;
    }

    private String buildNationalDashboardCacheKey(LocalDate startDate, LocalDate endDate) {
        return NATIONAL_DASHBOARD_CACHE_PREFIX
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v5";
    }

    /** Build the national district (level-2) metrics from pre-aggregated region rows. */
    private NationalDashboardLevel2MetricsResponse buildNationalLevel2FromAggregate(
            List<AggregateReadRepository.NationalRegionRow> rows,
            LocalDate startDate, LocalDate endDate, int daysInRange) {
        // Regularity matches the legacy fallback: a scheme is "regular" when it supplied water on at
        // least the national threshold's share of the window's days (threshold uniform across the
        // national dashboard), classified with the national work-status filter. Summing the per-day
        // regular_scheme_count would over-count, so it is derived per region from the base grain.
        int regularityThresholdDays = RegularityThresholdFilter.thresholdDays(
                daysInRange, schemeRegularityRepository.getEffectiveNationalRegularityThresholdPercent());
        List<NationalDashboardLevel2MetricsResponse.LgdLevel2MetricsRow> districts = rows.stream()
                .map(r -> {
                    int regularSchemeCount = (int) aggregateReadRepository.getNationalRegularSchemeCount(
                            r.tenantId(), "LGD", r.regionId(), startDate, endDate, regularityThresholdDays);
                    return NationalDashboardLevel2MetricsResponse.LgdLevel2MetricsRow.builder()
                        .tenantId(r.tenantId())
                        .lgdId(r.regionId())
                        .tenantStatus(r.tenantStatus())
                        .stateCode(r.stateCode())
                        .stateTitle(r.stateTitle())
                        .districtTitle(r.regionTitle())
                        .schemeCount(r.schemeCount())
                        .totalHouseholdCount(r.totalHouseholdCount())
                        .totalAchievedFhtcCount(r.totalAchievedFhtc())
                        .totalPlannedFhtcCount(r.totalPlannedFhtc())
                        .totalWaterSuppliedLiters(r.totalWaterSuppliedLiters())
                        .avgWaterSupplyPerScheme(r.schemeCount() > 0
                                ? aggregateRatio(r.totalWaterSuppliedLiters(), r.schemeCount()) : BigDecimal.ZERO)
                        .supplyDaysInEfficientRange(r.supplyDaysInEfficientRange())
                        .totalSupplyDays((int) r.totalSupplyDays())
                        .regularSchemeCount(regularSchemeCount)
                        .averageRegularity(RegularityThresholdFilter.regularityRate(
                                regularSchemeCount, r.schemeCount()))
                        .totalSubmissionDays((int) r.totalSubmissionDays())
                        .readingSubmissionRate(aggregateRatio(r.totalSubmissionDays(), (long) r.schemeCount() * daysInRange))
                        .build();
                })
                .toList();

        Map<String, Integer> overallOutageReasonDistribution =
                buildReasonCountMap(schemeRegularityRepository.getOverallOutageReasonSchemeCount(startDate, endDate));

        return NationalDashboardLevel2MetricsResponse.builder()
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .overallOutageReasonDistribution(overallOutageReasonDistribution)
                .districts(districts)
                .build();
    }

    /** Build the national (state level-1) dashboard from pre-aggregated region rows. */
    private NationalDashboardResponse buildNationalDashboardFromAggregate(
            List<AggregateReadRepository.NationalRegionRow> rows,
            LocalDate startDate, LocalDate endDate, int daysInRange) {
        List<NationalDashboardResponse.StateQuantityPerformance> quantity = rows.stream()
                .map(r -> NationalDashboardResponse.StateQuantityPerformance.builder()
                        .tenantId(r.tenantId())
                        .lgdId(r.regionId())
                        .tenantStatus(r.tenantStatus())
                        .stateCode(r.stateCode())
                        .stateTitle(r.stateTitle())
                        .schemeCount(r.schemeCount())
                        .totalHouseholdCount(r.totalHouseholdCount())
                        .totalAchievedFhtcCount(r.totalAchievedFhtc())
                        .totalPlannedFhtcCount(r.totalPlannedFhtc())
                        .totalWaterSuppliedLiters(r.totalWaterSuppliedLiters())
                        .avgWaterSupplyPerScheme(r.schemeCount() > 0
                                ? aggregateRatio(r.totalWaterSuppliedLiters(), r.schemeCount()) : BigDecimal.ZERO)
                        .supplyDaysInEfficientRange(r.supplyDaysInEfficientRange())
                        .build())
                .toList();

        // Regularity matches the legacy fallback (regular schemes ÷ scheme count, threshold-based)
        // rather than a supply-day fraction. Threshold is uniform (national percent); the regular
        // count is derived per region from the base grain with the national work-status filter,
        // since the per-day regular_scheme_count is not additive across the window.
        int regularityThresholdDays = RegularityThresholdFilter.thresholdDays(
                daysInRange, schemeRegularityRepository.getEffectiveNationalRegularityThresholdPercent());
        List<NationalDashboardResponse.StateRegularity> regularity = rows.stream()
                .map(r -> {
                    int regularSchemeCount = (int) aggregateReadRepository.getNationalRegularSchemeCount(
                            r.tenantId(), "LGD", r.regionId(), startDate, endDate, regularityThresholdDays);
                    return NationalDashboardResponse.StateRegularity.builder()
                        .tenantId(r.tenantId())
                        .lgdId(r.regionId())
                        .tenantStatus(r.tenantStatus())
                        .stateCode(r.stateCode())
                        .stateTitle(r.stateTitle())
                        .schemeCount(r.schemeCount())
                        .totalSupplyDays((int) r.totalSupplyDays())
                        .regularSchemeCount(regularSchemeCount)
                        .averageRegularity(RegularityThresholdFilter.regularityRate(
                                regularSchemeCount, r.schemeCount()))
                        .build();
                })
                .toList();

        List<NationalDashboardResponse.StateReadingSubmissionRate> submission = rows.stream()
                .map(r -> NationalDashboardResponse.StateReadingSubmissionRate.builder()
                        .tenantId(r.tenantId())
                        .lgdId(r.regionId())
                        .tenantStatus(r.tenantStatus())
                        .stateCode(r.stateCode())
                        .stateTitle(r.stateTitle())
                        .schemeCount(r.schemeCount())
                        .totalSubmissionDays((int) r.totalSubmissionDays())
                        .readingSubmissionRate(aggregateRatio(r.totalSubmissionDays(), (long) r.schemeCount() * daysInRange))
                        .build())
                .toList();

        Map<String, Integer> overallOutageReasonDistribution =
                buildReasonCountMap(schemeRegularityRepository.getOverallOutageReasonSchemeCount(startDate, endDate));

        return NationalDashboardResponse.builder()
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .stateWiseQuantityPerformance(quantity)
                .stateWiseRegularity(regularity)
                .stateWiseReadingSubmissionRate(submission)
                .overallOutageReasonDistribution(overallOutageReasonDistribution)
                .build();
    }

    private NationalDashboardResponse buildAndCacheNationalDashboard(
            LocalDate startDate, LocalDate endDate, String cacheKey) {
        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;

        if (readFromAggregates) {
            Optional<List<AggregateReadRepository.NationalRegionRow>> agg =
                    aggregateReadRepository.getNationalRegionMetrics(1, startDate, endDate);
            if (agg.isPresent()) {
                NationalDashboardResponse aggResponse =
                        buildNationalDashboardFromAggregate(agg.get(), startDate, endDate, daysInRange);
                writeToCache(cacheKey, aggResponse);
                return aggResponse;
            }
        }

        List<SchemeRegularityRepository.ChildRegionWaterSupplyMetrics> quantityMetrics =
                schemeRegularityRepository.getAverageWaterSupplyPerNation(startDate, endDate);
        Map<Integer, Long> supplyDaysInEfficientRangeByTenantId =
                schemeRegularityRepository.getTenantWiseSupplyDaysInEfficientRange(startDate, endDate).stream()
                        .collect(Collectors.toMap(
                                SchemeRegularityRepository.TenantSupplyDaysInEfficientRange::tenantId,
                                r -> r.supplyDaysInEfficientRange() != null ? r.supplyDaysInEfficientRange() : 0L,
                                (a, b) -> a,
                                LinkedHashMap::new));
        // Regularity over a single day is a degenerate share-of-days KPI, so widen just the regularity
        // window to a trailing lookback (water-quantity and reading-submission stay on the literal
        // requested range). The scheme-count denominator is window-independent, so the rate stays in [0,1].
        LocalDate regularityStartDate = expandSingleDayWindowStart(startDate, endDate);
        List<SchemeRegularityRepository.StateSchemeRegularityMetrics> regularityMetrics =
                schemeRegularityRepository.getStateWiseRegularityMetrics(regularityStartDate, endDate);
        List<SchemeRegularityRepository.StateReadingSubmissionMetrics> submissionMetrics =
                schemeRegularityRepository.getStateWiseReadingSubmissionMetrics(startDate, endDate);
        List<SchemeRegularityRepository.OutageReasonSchemeCount> outageRows =
                schemeRegularityRepository.getOverallOutageReasonSchemeCount(startDate, endDate);

        Map<Integer, SchemeRegularityRepository.NationalDashboardTenantStateMetadata> tenantStateMetadataByTenantId =
                schemeRegularityRepository.getNationalDashboardTenantStateMetadata().stream()
                        .collect(Collectors.toMap(
                                SchemeRegularityRepository.NationalDashboardTenantStateMetadata::tenantId,
                                Function.identity(),
                                (a, b) -> a,
                                LinkedHashMap::new));

        List<NationalDashboardResponse.StateQuantityPerformance> stateWiseQuantityPerformance = quantityMetrics.stream()
                .map(metric -> {
                    SchemeRegularityRepository.NationalDashboardTenantStateMetadata meta =
                            tenantStateMetadataByTenantId.get(metric.tenantId());
                    return NationalDashboardResponse.StateQuantityPerformance.builder()
                        .tenantId(metric.tenantId())
                        .lgdId(meta != null ? meta.lgdId() : null)
                        .tenantStatus(meta != null ? meta.tenantStatus() : null)
                        .stateCode(metric.stateCode())
                        .stateTitle(metric.title())
                        .schemeCount(metric.schemeCount())
                        .totalHouseholdCount(metric.totalHouseholdCount())
                        .totalAchievedFhtcCount(metric.totalAchievedFhtcCount())
                        .totalPlannedFhtcCount(metric.totalPlannedFhtcCount())
                        .totalWaterSuppliedLiters(metric.totalWaterSuppliedLiters())
                        .avgWaterSupplyPerScheme(metric.avgWaterSupplyPerScheme())
                        .supplyDaysInEfficientRange(
                                supplyDaysInEfficientRangeByTenantId.getOrDefault(metric.tenantId(), 0L))
                        .build();
                })
                .toList();

        List<NationalDashboardResponse.StateRegularity> stateWiseRegularity = regularityMetrics.stream()
                .map(metric -> {
                    SchemeRegularityRepository.NationalDashboardTenantStateMetadata meta =
                            tenantStateMetadataByTenantId.get(metric.tenantId());
                    BigDecimal averageRegularity = RegularityThresholdFilter.regularityRate(
                            metric.regularSchemeCount(), metric.schemeCount());
                    return NationalDashboardResponse.StateRegularity.builder()
                            .tenantId(metric.tenantId())
                            .lgdId(meta != null ? meta.lgdId() : null)
                            .tenantStatus(meta != null ? meta.tenantStatus() : null)
                            .stateCode(metric.stateCode())
                            .stateTitle(metric.title())
                            .schemeCount(metric.schemeCount())
                            .totalSupplyDays(metric.totalSupplyDays())
                            .regularSchemeCount(metric.regularSchemeCount())
                            .averageRegularity(averageRegularity)
                            .build();
                })
                .toList();

        List<NationalDashboardResponse.StateReadingSubmissionRate> stateWiseReadingSubmissionRate = submissionMetrics.stream()
                .map(metric -> {
                    SchemeRegularityRepository.NationalDashboardTenantStateMetadata meta =
                            tenantStateMetadataByTenantId.get(metric.tenantId());
                    BigDecimal readingSubmissionRate = BigDecimal.ZERO;
                    if (metric.schemeCount() > 0 && daysInRange > 0) {
                        readingSubmissionRate = BigDecimal.valueOf(metric.totalSubmissionDays())
                                .divide(BigDecimal.valueOf((long) metric.schemeCount() * daysInRange), 4, RoundingMode.HALF_UP);
                    }
                    return NationalDashboardResponse.StateReadingSubmissionRate.builder()
                            .tenantId(metric.tenantId())
                            .lgdId(meta != null ? meta.lgdId() : null)
                            .tenantStatus(meta != null ? meta.tenantStatus() : null)
                            .stateCode(metric.stateCode())
                            .stateTitle(metric.title())
                            .schemeCount(metric.schemeCount())
                            .totalSubmissionDays(metric.totalSubmissionDays())
                            .readingSubmissionRate(readingSubmissionRate)
                            .build();
                })
                .toList();

        Map<String, Integer> overallOutageReasonDistribution = buildReasonCountMap(outageRows);
        NationalDashboardResponse response = NationalDashboardResponse.builder()
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .regularityStartDate(regularityStartDate)
                .regularityDaysInRange((int) ChronoUnit.DAYS.between(regularityStartDate, endDate) + 1)
                .stateWiseQuantityPerformance(stateWiseQuantityPerformance)
                .stateWiseRegularity(stateWiseRegularity)
                .stateWiseReadingSubmissionRate(stateWiseReadingSubmissionRate)
                .overallOutageReasonDistribution(overallOutageReasonDistribution)
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    private NationalDashboardBoundaryResponse buildAndCacheNationalDashboardBoundaries() {
        JsonNode nationalBoundary = parseBoundaryGeoJson(schemeRegularityRepository.getNationalBoundaryGeoJson());
        List<NationalDashboardBoundaryResponse.StateBoundary> stateWiseBoundaries =
                schemeRegularityRepository.getNationalDashboardStateBoundaries().stream()
                        .map(row -> NationalDashboardBoundaryResponse.StateBoundary.builder()
                                .tenantId(row.tenantId())
                                .lgdId(row.lgdId())
                                .tenantStatus(row.tenantStatus())
                                .stateCode(row.stateCode())
                                .stateTitle(row.stateTitle())
                                .boundary(parseBoundaryGeoJson(row.boundaryGeoJson()))
                                .build())
                        .toList();
        NationalDashboardBoundaryResponse response = NationalDashboardBoundaryResponse.builder()
                .nationalBoundary(nationalBoundary)
                .stateWiseBoundaries(stateWiseBoundaries)
                .build();
        writeToCache(NATIONAL_DASHBOARD_BOUNDARY_CACHE_KEY, response);
        return response;
    }

    private NationalDashboardLevel2BoundaryResponse buildAndCacheNationalDashboardLevel2Boundaries() {
        JsonNode nationalBoundary = parseBoundaryGeoJson(schemeRegularityRepository.getNationalBoundaryGeoJson());

        List<NationalDashboardLevel2BoundaryResponse.LgdLevel2Boundary> lgdLevel2Boundaries =
                schemeRegularityRepository.getNationalDashboardLevel2LgdBoundaries().stream()
                        .map(row -> NationalDashboardLevel2BoundaryResponse.LgdLevel2Boundary.builder()
                                .tenantId(row.tenantId())
                                .lgdId(row.lgdId())
                                .tenantStatus(row.tenantStatus())
                                .stateCode(row.stateCode())
                                .stateTitle(row.stateTitle())
                                .title(row.title())
                                .boundary(parseBoundaryGeoJson(row.boundaryGeoJson()))
                                .build())
                        .toList();

        NationalDashboardLevel2BoundaryResponse response = NationalDashboardLevel2BoundaryResponse.builder()
                .nationalBoundary(nationalBoundary)
                .lgdLevel2Boundaries(lgdLevel2Boundaries)
                .build();
        writeToCache(NATIONAL_DASHBOARD_LEVEL2_BOUNDARY_CACHE_KEY, response);
        return response;
    }

    @Override
    public AverageWaterSupplyResponse getAverageWaterSupplyPerCurrentRegionByLgd(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateLgdInput(lgdId);
        validateDateRange(startDate, endDate);
        // #region agent log
        appendDebugLog(
                "H1",
                "SchemeRegularityServiceImpl:getAverageWaterSupplyPerSchemeByLgd:entry",
                "Entered average-per-region LGD branch",
                Map.of(
                        "tenantId", tenantId,
                        "lgdId", lgdId,
                        "startDate", String.valueOf(startDate),
                        "endDate", String.valueOf(endDate)));
        // #endregion

        String cacheKey = ":water_supply:tenant:" + tenantId
                + ":lgd:" + lgdId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v5";
        AverageWaterSupplyResponse cached = readFromCache(cacheKey, AverageWaterSupplyResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentLgdLevel = schemeRegularityRepository.getLgdLevel(lgdId);
        if (parentLgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + lgdId);
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        List<SchemeRegularityRepository.ChildRegionWaterSupplyMetrics> metrics;
        try {
            metrics = aggregateChildWaterSupplyOrNull(tenantId, "LGD", lgdId, parentLgdLevel, startDate, endDate);
            if (metrics == null) {
                metrics = schemeRegularityRepository.getAverageWaterSupplyPerCurrentRegionByLgd(tenantId, lgdId, startDate, endDate);
            }
        } catch (Exception ex) {
            // #region agent log
            appendDebugLog(
                    "H2",
                    "SchemeRegularityServiceImpl:getAverageWaterSupplyPerSchemeByLgd:repo_exception",
                    "LGD branch repository call failed",
                    Map.of("errorType", ex.getClass().getName(), "errorMessage", String.valueOf(ex.getMessage())));
            // #endregion
            throw ex;
        }
        // #region agent log
        appendDebugLog(
                "H3",
                "SchemeRegularityServiceImpl:getAverageWaterSupplyPerSchemeByLgd:repo_success",
                "LGD branch repository call succeeded",
                Map.of("daysInRange", daysInRange, "metricRows", metrics.size()));
        // #endregion

        List<AverageWaterSupplyResponse.ChildRegionWaterSupply> childRegions = metrics.stream()
                .map(m -> AverageWaterSupplyResponse.ChildRegionWaterSupply.builder()
                        .lgdId(m.lgdId())
                        .departmentId(null)
                        .title(m.title())
                        .totalHouseholdCount(m.totalHouseholdCount())
                        .totalAchievedFhtcCount(m.totalAchievedFhtcCount())
                        .totalPlannedFhtcCount(m.totalPlannedFhtcCount())
                        .totalWaterSuppliedLiters(m.totalWaterSuppliedLiters())
                        .schemeCount(m.schemeCount())
                        .avgWaterSupplyPerScheme(m.avgWaterSupplyPerScheme())
                        .build())
                .toList();

        AverageWaterSupplyResponse response = AverageWaterSupplyResponse.builder()
                .tenantId(tenantId)
                .stateCode(getTenantStateCode(tenantId))
                .parentLgdLevel(parentLgdLevel)
                .parentDepartmentLevel(null)
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .schemeCount(0)
                .schemes(List.of())
                .childRegionCount(childRegions.size())
                .childRegions(childRegions)
                .currentRegion(toCurrentRegion(
                        schemeRegularityRepository.getRegionOwnWaterSupplyByLgd(tenantId, lgdId, startDate, endDate)))
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    private AverageWaterSupplyResponse.ChildRegionWaterSupply toCurrentRegion(
            SchemeRegularityRepository.ChildRegionWaterSupplyMetrics m) {
        return AverageWaterSupplyResponse.ChildRegionWaterSupply.builder()
                .lgdId(m.lgdId())
                .departmentId(m.departmentId())
                .title(m.title())
                .totalHouseholdCount(m.totalHouseholdCount())
                .totalAchievedFhtcCount(m.totalAchievedFhtcCount())
                .totalPlannedFhtcCount(m.totalPlannedFhtcCount())
                .totalWaterSuppliedLiters(m.totalWaterSuppliedLiters())
                .schemeCount(m.schemeCount())
                .avgWaterSupplyPerScheme(m.avgWaterSupplyPerScheme())
                .build();
    }

    @Override
    public AverageWaterSupplyResponse getAverageWaterSupplyPerCurrentRegionByLgdForChildScope(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate) {
        AverageWaterSupplyResponse response =
                getAverageWaterSupplyPerCurrentRegionByLgd(tenantId, lgdId, startDate, endDate);
        // Contract: `scope=child` should not expose scheme-level rows.
        response.setSchemeCount(null);
        response.setSchemes(null);
        return response;
    }

    @Override
    public AverageWaterSupplyResponse getAverageWaterSupplyPerCurrentRegionByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);
        // #region agent log
        appendDebugLog(
                "H4",
                "SchemeRegularityServiceImpl:getAverageWaterSupplyPerSchemeByDepartment:entry",
                "Entered average-per-region department branch",
                Map.of(
                        "tenantId", tenantId,
                        "parentDepartmentId", parentDepartmentId,
                        "startDate", String.valueOf(startDate),
                        "endDate", String.valueOf(endDate)));
        // #endregion

        String cacheKey = ":water_supply:tenant:" + tenantId
                + ":department:" + parentDepartmentId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v5";
        AverageWaterSupplyResponse cached = readFromCache(cacheKey, AverageWaterSupplyResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentDepartmentLevel = schemeRegularityRepository.getDepartmentLevel(parentDepartmentId);
        if (parentDepartmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        List<SchemeRegularityRepository.ChildRegionWaterSupplyMetrics> metrics;
        try {
            metrics = aggregateChildWaterSupplyOrNull(tenantId, "DEPT", parentDepartmentId, parentDepartmentLevel, startDate, endDate);
            if (metrics == null) {
                metrics = schemeRegularityRepository.getAverageWaterSupplyPerCurrentRegionByDepartment(tenantId, parentDepartmentId, startDate, endDate);
            }
        } catch (Exception ex) {
            // #region agent log
            appendDebugLog(
                    "H5",
                    "SchemeRegularityServiceImpl:getAverageWaterSupplyPerSchemeByDepartment:repo_exception",
                    "Department branch repository call failed",
                    Map.of("errorType", ex.getClass().getName(), "errorMessage", String.valueOf(ex.getMessage())));
            // #endregion
            throw ex;
        }
        // #region agent log
        appendDebugLog(
                "H5",
                "SchemeRegularityServiceImpl:getAverageWaterSupplyPerSchemeByDepartment:repo_success",
                "Department branch repository call succeeded",
                Map.of("daysInRange", daysInRange, "metricRows", metrics.size()));
        // #endregion

        List<AverageWaterSupplyResponse.ChildRegionWaterSupply> childRegions = metrics.stream()
                .map(m -> AverageWaterSupplyResponse.ChildRegionWaterSupply.builder()
                        .lgdId(null)
                        .departmentId(m.departmentId())
                        .title(m.title())
                        .totalHouseholdCount(m.totalHouseholdCount())
                        .totalAchievedFhtcCount(m.totalAchievedFhtcCount())
                        .totalPlannedFhtcCount(m.totalPlannedFhtcCount())
                        .totalWaterSuppliedLiters(m.totalWaterSuppliedLiters())
                        .schemeCount(m.schemeCount())
                        .avgWaterSupplyPerScheme(m.avgWaterSupplyPerScheme())
                        .build())
                .toList();

        AverageWaterSupplyResponse response = AverageWaterSupplyResponse.builder()
                .tenantId(tenantId)
                .stateCode(getTenantStateCode(tenantId))
                .parentLgdLevel(null)
                .parentDepartmentLevel(parentDepartmentLevel)
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .schemeCount(0)
                .schemes(List.of())
                .childRegionCount(childRegions.size())
                .childRegions(childRegions)
                .currentRegion(toCurrentRegion(schemeRegularityRepository
                        .getRegionOwnWaterSupplyByDepartment(tenantId, parentDepartmentId, startDate, endDate)))
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public AverageWaterSupplyResponse getAverageWaterSupplyPerCurrentRegionByDepartmentForChildScope(
            Integer tenantId,
            Integer parentDepartmentId,
            LocalDate startDate,
            LocalDate endDate) {
        AverageWaterSupplyResponse response =
                getAverageWaterSupplyPerCurrentRegionByDepartment(
                        tenantId, parentDepartmentId, startDate, endDate);
        // Contract: `scope=child` should not expose scheme-level rows.
        response.setSchemeCount(null);
        response.setSchemes(null);
        return response;
    }

    @Override
    public RegionWiseWaterQuantityResponse getRegionWiseWaterQuantityByLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);

        String cacheKey = REGION_WISE_WATER_QUANTITY_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":parent_lgd:" + parentLgdId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v6";
        RegionWiseWaterQuantityResponse cached = readFromCache(cacheKey, RegionWiseWaterQuantityResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentLgdLevel = schemeRegularityRepository.getLgdLevel(parentLgdId);
        if (parentLgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }

        List<SchemeRegularityRepository.ChildRegionWaterQuantityMetrics> metrics =
                aggregateChildWaterQuantityOrNull(tenantId, "LGD", parentLgdId, parentLgdLevel, startDate, endDate);
        if (metrics == null) {
            metrics = schemeRegularityRepository.getRegionWiseWaterQuantityByLgd(tenantId, parentLgdId, startDate, endDate);
        }

        List<RegionWiseWaterQuantityResponse.ChildRegionWaterQuantity> childRegions = metrics.stream()
                .map(metric -> RegionWiseWaterQuantityResponse.ChildRegionWaterQuantity.builder()
                        .lgdId(metric.lgdId())
                        .departmentId(null)
                        .title(metric.title())
                        .waterQuantity(metric.waterQuantity())
                        .householdCount(metric.householdCount())
                        .achievedFhtcCount(metric.achievedFhtcCount())
                        .plannedFhtcCount(metric.plannedFhtcCount())
                        .supplyDaysInEfficientRange(metric.supplyDaysInEfficientRange())
                        .build())
                .toList();

        RegionWiseWaterQuantityResponse response = RegionWiseWaterQuantityResponse.builder()
                .parentLgdId(parentLgdId)
                .parentDepartmentId(null)
                .parentLgdLevel(parentLgdLevel)
                .parentDepartmentLevel(null)
                .startDate(startDate)
                .endDate(endDate)
                .childRegionCount(childRegions.size())
                .childRegions(childRegions)
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public RegionWiseWaterQuantityResponse getRegionWiseWaterQuantityByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);

        String cacheKey = REGION_WISE_WATER_QUANTITY_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":parent_department:" + parentDepartmentId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v4";
        RegionWiseWaterQuantityResponse cached = readFromCache(cacheKey, RegionWiseWaterQuantityResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentDepartmentLevel = schemeRegularityRepository.getDepartmentLevel(parentDepartmentId);
        if (parentDepartmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }

        List<SchemeRegularityRepository.ChildRegionWaterQuantityMetrics> metrics =
                aggregateChildWaterQuantityOrNull(tenantId, "DEPT", parentDepartmentId, parentDepartmentLevel, startDate, endDate);
        if (metrics == null) {
            metrics = schemeRegularityRepository.getRegionWiseWaterQuantityByDepartment(
                    tenantId, parentDepartmentId, startDate, endDate);
        }

        List<RegionWiseWaterQuantityResponse.ChildRegionWaterQuantity> childRegions = metrics.stream()
                .map(metric -> RegionWiseWaterQuantityResponse.ChildRegionWaterQuantity.builder()
                        .lgdId(null)
                        .departmentId(metric.departmentId())
                        .title(metric.title())
                        .waterQuantity(metric.waterQuantity())
                        .householdCount(metric.householdCount())
                        .achievedFhtcCount(metric.achievedFhtcCount())
                        .plannedFhtcCount(metric.plannedFhtcCount())
                        .supplyDaysInEfficientRange(metric.supplyDaysInEfficientRange())
                        .build())
                .toList();

        RegionWiseWaterQuantityResponse response = RegionWiseWaterQuantityResponse.builder()
                .parentLgdId(null)
                .parentDepartmentId(parentDepartmentId)
                .parentLgdLevel(null)
                .parentDepartmentLevel(parentDepartmentLevel)
                .startDate(startDate)
                .endDate(endDate)
                .childRegionCount(childRegions.size())
                .childRegions(childRegions)
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public PeriodicWaterQuantityResponse getPeriodicWaterQuantityByLgdId(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        validateTenantInput(tenantId);
        validateLgdInput(lgdId);
        validateDateRange(startDate, endDate);
        validateScaleInput(scale);

        String cacheKey = PERIODIC_WATER_QUANTITY_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":lgd:" + lgdId
                + ":scale:" + scale.name().toLowerCase()
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v2";
        PeriodicWaterQuantityResponse cached = readFromCache(cacheKey, PeriodicWaterQuantityResponse.class);
        if (cached != null) {
            return cached;
        }

        List<SchemeRegularityRepository.PeriodicWaterQuantityMetrics> metrics =
                aggregatePeriodicWaterQuantityOrNull(tenantId, "LGD", lgdId, startDate, endDate, scale);
        if (metrics == null) {
            metrics = schemeRegularityRepository.getPeriodicWaterQuantityByLgdId(lgdId, startDate, endDate, scale);
        }

        PeriodicWaterQuantityResponse response =
                buildPeriodicWaterQuantityResponse(lgdId, null, startDate, endDate, scale, metrics);
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public PeriodicWaterQuantityResponse getPeriodicWaterQuantityByDepartment(
            Integer tenantId, Integer departmentId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        validateTenantInput(tenantId);
        validateDepartmentInput(departmentId);
        validateDateRange(startDate, endDate);
        validateScaleInput(scale);

        List<SchemeRegularityRepository.PeriodicWaterQuantityMetrics> metrics =
                aggregatePeriodicWaterQuantityOrNull(tenantId, "DEPT", departmentId, startDate, endDate, scale);
        if (metrics == null) {
            metrics = schemeRegularityRepository.getPeriodicWaterQuantityByDepartment(departmentId, startDate, endDate, scale);
        }

        return buildPeriodicWaterQuantityResponse(null, departmentId, startDate, endDate, scale, metrics);
    }

    /**
     * Periodic water-quantity buckets from pre-rolled aggregates (DAY/WEEK/MONTH), or
     * {@code null} to fall back to legacy. averageWaterQuantity = total water supplied /
     * water-quantity row count for the bucket (matches the legacy per-row average).
     */
    private List<SchemeRegularityRepository.PeriodicWaterQuantityMetrics> aggregatePeriodicWaterQuantityOrNull(
            Integer tenantId, String hierarchy, Integer regionId,
            LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        if (!readFromAggregates || regionId == null) {
            return null;
        }
        // DAY/WEEK/MONTH are read directly; QUARTER/YEAR are re-bucketed from stored MONTH rows.
        List<AggregateReadRepository.PeriodicRegionRow> rows =
                aggregateReadRepository.getPeriodicRegionMetrics(tenantId, hierarchy, regionId, scale.name(), startDate, endDate);
        if (rows.isEmpty()) {
            return null;
        }
        return rows.stream()
                .map(r -> {
                    // After the de-dup a scheme-day has at most one qualifying water row, so
                    // the supply-day count IS the qualifying-row count (the average's divisor).
                    BigDecimal avg = r.totalSupplyDays() > 0
                            ? BigDecimal.valueOf(r.totalWaterSuppliedLiters())
                                    .divide(BigDecimal.valueOf(r.totalSupplyDays()), 4, RoundingMode.HALF_UP)
                            : BigDecimal.ZERO;
                    return new SchemeRegularityRepository.PeriodicWaterQuantityMetrics(
                            r.periodStart(), r.periodEnd(), null, avg,
                            r.totalHouseholdCount(), r.totalAchievedFhtc(), r.totalPlannedFhtc());
                })
                .toList();
    }

    private static BigDecimal aggregateRatio(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 4, RoundingMode.HALF_UP);
    }

    /**
     * Per-child scheme-regularity is served from the legacy path: the threshold-based
     * "regular scheme" classification is per child region and is not pre-aggregated, so this
     * always returns {@code null} and the caller uses the legacy per-child SQL. (The region-wide
     * and national regularity cards do use the aggregate path via {@code getRegularSchemeCount}.)
     */
    private List<SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics> aggregateChildRegularityOrNull(
            Integer tenantId, String hierarchy, Integer parentRegionId, Integer parentLevel,
            LocalDate startDate, LocalDate endDate, int daysInRange) {
        return null;
    }

    /** Per-child reading-submission rows from aggregates, or {@code null} to fall back to legacy. */
    private List<SchemeRegularityRepository.ChildRegionReadingSubmissionMetrics> aggregateChildSubmissionOrNull(
            Integer tenantId, String hierarchy, Integer parentRegionId, Integer parentLevel,
            LocalDate startDate, LocalDate endDate, int daysInRange) {
        if (!readFromAggregates || parentRegionId == null || parentLevel == null) {
            return null;
        }
        boolean dept = "DEPT".equals(hierarchy);
        return aggregateReadRepository.getChildRegionMetrics(tenantId, hierarchy, parentRegionId, parentLevel, startDate, endDate)
                .map(rows -> rows.stream()
                        .map(r -> new SchemeRegularityRepository.ChildRegionReadingSubmissionMetrics(
                                dept ? null : r.regionId(),
                                dept ? r.regionId() : null,
                                r.title(),
                                r.schemeCount(),
                                (int) r.totalSubmissionDays(),
                                aggregateRatio(r.totalSubmissionDays(), (long) r.schemeCount() * daysInRange)))
                        .toList())
                .orElse(null);
    }

    /** Per-child water-quantity rows from aggregates, or {@code null} to fall back to legacy. */
    private List<SchemeRegularityRepository.ChildRegionWaterQuantityMetrics> aggregateChildWaterQuantityOrNull(
            Integer tenantId, String hierarchy, Integer parentRegionId, Integer parentLevel,
            LocalDate startDate, LocalDate endDate) {
        if (!readFromAggregates || parentRegionId == null || parentLevel == null) {
            return null;
        }
        boolean dept = "DEPT".equals(hierarchy);
        return aggregateReadRepository.getChildRegionMetrics(tenantId, hierarchy, parentRegionId, parentLevel, startDate, endDate)
                .map(rows -> rows.stream()
                        .map(r -> new SchemeRegularityRepository.ChildRegionWaterQuantityMetrics(
                                dept ? null : r.regionId(),
                                dept ? r.regionId() : null,
                                r.title(),
                                r.totalWaterSuppliedLiters(),
                                r.totalHouseholdCount(),
                                r.totalAchievedFhtc(),
                                r.totalPlannedFhtc(),
                                r.supplyDaysInEfficientRange()))
                        .toList())
                .orElse(null);
    }

    /** Per-child outage-reason rows from aggregates, or {@code null} to fall back to legacy. */
    private List<SchemeRegularityRepository.ChildRegionOutageReasonSchemeCount> aggregateChildOutageRowsOrNull(
            Integer tenantId, String hierarchy, Integer parentRegionId, Integer parentLevel,
            LocalDate startDate, LocalDate endDate) {
        if (!readFromAggregates || parentRegionId == null || parentLevel == null) {
            return null;
        }
        boolean dept = "DEPT".equals(hierarchy);
        return aggregateReadRepository.getChildReasonDistribution(tenantId, hierarchy, parentRegionId, parentLevel, true, startDate, endDate)
                .map(rows -> rows.stream()
                        .map(r -> new SchemeRegularityRepository.ChildRegionOutageReasonSchemeCount(
                                dept ? null : r.regionId(), dept ? r.regionId() : null, r.reasonKey(), r.schemeCount()))
                        .toList())
                .orElse(null);
    }

    /** Per-child non-submission-reason rows from aggregates, or {@code null} to fall back to legacy. */
    private List<SchemeRegularityRepository.ChildRegionNonSubmissionReasonSchemeCount> aggregateChildNonSubmissionRowsOrNull(
            Integer tenantId, String hierarchy, Integer parentRegionId, Integer parentLevel,
            LocalDate startDate, LocalDate endDate) {
        if (!readFromAggregates || parentRegionId == null || parentLevel == null) {
            return null;
        }
        boolean dept = "DEPT".equals(hierarchy);
        return aggregateReadRepository.getChildReasonDistribution(tenantId, hierarchy, parentRegionId, parentLevel, false, startDate, endDate)
                .map(rows -> rows.stream()
                        .map(r -> new SchemeRegularityRepository.ChildRegionNonSubmissionReasonSchemeCount(
                                dept ? null : r.regionId(), dept ? r.regionId() : null, r.reasonKey(), r.schemeCount()))
                        .toList())
                .orElse(null);
    }

    /** Per-scheme water supply (current region) from aggregates, or {@code null} to fall back to legacy. */
    private List<SchemeRegularityRepository.SchemeWaterSupplyMetrics> aggregateSchemeWaterSupplyOrNull(
            Integer tenantId, LocalDate startDate, LocalDate endDate, int daysInRange) {
        if (!readFromAggregates) {
            return null;
        }
        return aggregateReadRepository.getSchemeWaterSupply(tenantId, startDate, endDate)
                .map(rows -> rows.stream()
                        .map(r -> new SchemeRegularityRepository.SchemeWaterSupplyMetrics(
                                r.schemeId(), r.schemeName(), r.householdCount(),
                                r.achievedFhtc(), r.plannedFhtc(), r.totalWaterSuppliedLiters(), r.supplyDays(),
                                aggregateRatio(r.totalWaterSuppliedLiters(), r.householdCount() * (long) daysInRange)))
                        .toList())
                .orElse(null);
    }

    /** Per-child water-supply rows (unified supplied-water figure) from aggregates, or {@code null} to fall back. */
    private List<SchemeRegularityRepository.ChildRegionWaterSupplyMetrics> aggregateChildWaterSupplyOrNull(
            Integer tenantId, String hierarchy, Integer parentRegionId, Integer parentLevel,
            LocalDate startDate, LocalDate endDate) {
        if (!readFromAggregates || parentRegionId == null || parentLevel == null) {
            return null;
        }
        boolean dept = "DEPT".equals(hierarchy);
        return aggregateReadRepository.getChildRegionMetrics(tenantId, hierarchy, parentRegionId, parentLevel, startDate, endDate)
                .map(rows -> rows.stream()
                        .map(r -> new SchemeRegularityRepository.ChildRegionWaterSupplyMetrics(
                                null, null,
                                dept ? null : r.regionId(),
                                dept ? r.regionId() : null,
                                r.title(),
                                r.totalHouseholdCount(), r.totalAchievedFhtc(), r.totalPlannedFhtc(),
                                r.totalWaterSuppliedLiters(), r.schemeCount(),
                                r.schemeCount() > 0
                                        ? aggregateRatio(r.totalWaterSuppliedLiters(), r.schemeCount())
                                        : BigDecimal.ZERO))
                        .toList())
                .orElse(null);
    }

    /** Critical-scheme count from aggregates, falling back to {@code legacy} when off or not aggregated. */
    private long resolveCriticalCount(Integer tenantId, String hierarchy, Integer regionId,
                                      LocalDate cutoffDate, java.util.function.LongSupplier legacy) {
        if (readFromAggregates && regionId != null) {
            java.util.OptionalLong agg =
                    aggregateReadRepository.getCriticalSchemeCount(tenantId, hierarchy, regionId, cutoffDate);
            if (agg.isPresent()) {
                return agg.getAsLong();
            }
        }
        return legacy.getAsLong();
    }

    @Override
    public PeriodicSchemeRegularityResponse getPeriodicSchemeRegularityByLgdId(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        validateTenantInput(tenantId);
        validateLgdInput(lgdId);
        validateDateRange(startDate, endDate);
        validateScaleInput(scale);

        String cacheKey = PERIODIC_SCHEME_REGULARITY_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":lgd:" + lgdId
                + ":scale:" + scale.name().toLowerCase()
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v1";
        PeriodicSchemeRegularityResponse cached = readFromCache(cacheKey, PeriodicSchemeRegularityResponse.class);
        if (cached != null) {
            return cached;
        }

        List<SchemeRegularityRepository.PeriodicSchemeRegularityMetrics> metrics =
                aggregatePeriodicRegularityOrNull(tenantId, "LGD", lgdId, startDate, endDate, scale);
        if (metrics == null) {
            metrics = schemeRegularityRepository.getPeriodicSchemeRegularityByLgdId(
                    tenantId, lgdId, startDate, endDate, scale);
        }

        PeriodicSchemeRegularityResponse response =
                buildPeriodicSchemeRegularityResponse(lgdId, null, startDate, endDate, scale, metrics);
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public PeriodicSchemeRegularityResponse getPeriodicSchemeRegularityByDepartment(
            Integer tenantId, Integer departmentId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        validateTenantInput(tenantId);
        validateDepartmentInput(departmentId);
        validateDateRange(startDate, endDate);
        validateScaleInput(scale);

        List<SchemeRegularityRepository.PeriodicSchemeRegularityMetrics> metrics =
                aggregatePeriodicRegularityOrNull(tenantId, "DEPT", departmentId, startDate, endDate, scale);
        if (metrics == null) {
            metrics = schemeRegularityRepository.getPeriodicSchemeRegularityByDepartment(
                    tenantId, departmentId, startDate, endDate, scale);
        }

        return buildPeriodicSchemeRegularityResponse(null, departmentId, startDate, endDate, scale, metrics);
    }

    @Override
    public PeriodicSchemeRegularityResponse getPeriodicSchemeRegularityForNation(
            LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        validateDateRange(startDate, endDate);
        validateScaleInput(scale);

        String cacheKey = buildPeriodicSchemeRegularityForNationCacheKey(startDate, endDate, scale);
        PeriodicSchemeRegularityResponse cached =
                readFromCache(cacheKey, PeriodicSchemeRegularityResponse.class);
        if (cached != null) {
            return cached;
        }

        List<SchemeRegularityRepository.PeriodicSchemeRegularityMetrics> metrics =
                schemeRegularityRepository.getPeriodicSchemeRegularityForNation(startDate, endDate, scale);

        // Contract: national response should not be tied to a specific LGD or department.
        PeriodicSchemeRegularityResponse response =
                buildPeriodicSchemeRegularityResponse(null, null, startDate, endDate, scale, metrics);
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public PeriodicNationalSchemeRegularityResponse getPeriodicSchemeRegularityForNationForApi(
            LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        validateDateRange(startDate, endDate);
        validateScaleInput(scale);

        String cacheKey = buildPeriodicSchemeRegularityForNationForApiCacheKey(startDate, endDate, scale);
        PeriodicNationalSchemeRegularityResponse cached =
                readFromCache(cacheKey, PeriodicNationalSchemeRegularityResponse.class);
        if (cached != null) {
            return cached;
        }

        List<SchemeRegularityRepository.PeriodicSchemeRegularityMetrics> metrics =
                schemeRegularityRepository.getPeriodicSchemeRegularityForNation(startDate, endDate, scale);

        PeriodicNationalSchemeRegularityResponse response =
                buildPeriodicNationalSchemeRegularityResponse(startDate, endDate, scale, metrics);
        writeToCache(cacheKey, response);
        return response;
    }

    private String buildPeriodicSchemeRegularityForNationCacheKey(
            LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        return SCHEME_REGULARITY_CACHE_PREFIX
                + ":nation:periodic-scheme-regularity"
                + ":scale:" + scale.name().toLowerCase()
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v2";
    }

    private String buildPeriodicSchemeRegularityForNationForApiCacheKey(
            LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        return SCHEME_REGULARITY_CACHE_PREFIX
                + ":nation:periodic-scheme-regularity:api"
                + ":scale:" + scale.name().toLowerCase()
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v2";
    }

    @Override
    public PeriodicOutageReasonSchemeCountResponse getPeriodicOutageReasonSchemeCountByLgdId(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        validateTenantInput(tenantId);
        validateLgdInput(lgdId);
        validateDateRange(startDate, endDate);
        validateScaleInput(scale);

        String cacheKey = PERIODIC_OUTAGE_REASON_SCHEME_COUNT_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":lgd:" + lgdId
                + ":scale:" + scale.name().toLowerCase()
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v2";
        PeriodicOutageReasonSchemeCountResponse cached =
                readFromCache(cacheKey, PeriodicOutageReasonSchemeCountResponse.class);
        if (cached != null) {
            return cached;
        }

        List<SchemeRegularityRepository.PeriodicOutageReasonSchemeCountRow> rows =
                schemeRegularityRepository.getPeriodicOutageReasonSchemeCountByLgdId(
                        tenantId, lgdId, startDate, endDate, scale);

        PeriodicOutageReasonSchemeCountResponse response =
                buildPeriodicOutageReasonSchemeCountResponse(lgdId, null, startDate, endDate, scale, rows);
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public PeriodicOutageReasonSchemeCountResponse getPeriodicOutageReasonSchemeCountByDepartment(
            Integer tenantId, Integer departmentId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        validateTenantInput(tenantId);
        validateDepartmentInput(departmentId);
        validateDateRange(startDate, endDate);
        validateScaleInput(scale);

        String cacheKey = PERIODIC_OUTAGE_REASON_SCHEME_COUNT_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":department:" + departmentId
                + ":scale:" + scale.name().toLowerCase()
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v1";
        PeriodicOutageReasonSchemeCountResponse cached =
                readFromCache(cacheKey, PeriodicOutageReasonSchemeCountResponse.class);
        if (cached != null) {
            return cached;
        }

        List<SchemeRegularityRepository.PeriodicOutageReasonSchemeCountRow> rows =
                schemeRegularityRepository.getPeriodicOutageReasonSchemeCountByDepartment(
                        tenantId, departmentId, startDate, endDate, scale);

        PeriodicOutageReasonSchemeCountResponse response =
                buildPeriodicOutageReasonSchemeCountResponse(null, departmentId, startDate, endDate, scale, rows);
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public OutageReasonSchemeCountResponse getOutageReasonSchemeCountByLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);

        String cacheKey = OUTAGE_REASON_SCHEME_COUNT_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":parent_lgd:" + parentLgdId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v2";
        OutageReasonSchemeCountResponse cached = readFromCache(cacheKey, OutageReasonSchemeCountResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentLgdLevel = schemeRegularityRepository.getLgdLevelForTenant(tenantId, parentLgdId);
        if (parentLgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }

        Map<String, Integer> parentOutageMap = resolveReasonParentMap(tenantId, "LGD", parentLgdId, true,
                startDate, endDate,
                () -> buildReasonCountMap(schemeRegularityRepository.getOutageReasonSchemeCountByLgd(
                        tenantId, parentLgdId, startDate, endDate)));
        List<SchemeRegularityRepository.ChildRegionRef> childRegions =
                schemeRegularityRepository.getChildRegionsByLgd(tenantId, parentLgdId);
        List<SchemeRegularityRepository.ChildRegionOutageReasonSchemeCount> childRows =
                aggregateChildOutageRowsOrNull(tenantId, "LGD", parentLgdId, parentLgdLevel, startDate, endDate);
        if (childRows == null) {
            childRows = schemeRegularityRepository.getChildOutageReasonSchemeCountByLgd(tenantId, parentLgdId, startDate, endDate);
        }

        OutageReasonSchemeCountResponse response = OutageReasonSchemeCountResponse.builder()
                .lgdId(parentLgdId)
                .departmentId(null)
                .startDate(startDate)
                .endDate(endDate)
                .parentLgdLevel(parentLgdLevel)
                .parentDepartmentLevel(null)
                .outageReasonSchemeCount(parentOutageMap)
                .childRegionCount(childRegions.size())
                .childRegions(buildChildOutageRegions(
                        childRegions,
                        childRows,
                        SchemeRegularityRepository.ChildRegionOutageReasonSchemeCount::lgdId))
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public OutageReasonSchemeCountResponse getOutageReasonSchemeCountByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);

        String cacheKey = OUTAGE_REASON_SCHEME_COUNT_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":parent_department:" + parentDepartmentId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v1";
        OutageReasonSchemeCountResponse cached = readFromCache(cacheKey, OutageReasonSchemeCountResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentDepartmentLevel = schemeRegularityRepository.getDepartmentLevelForTenant(tenantId, parentDepartmentId);
        if (parentDepartmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }

        Map<String, Integer> parentOutageMap = resolveReasonParentMap(tenantId, "DEPT", parentDepartmentId, true,
                startDate, endDate,
                () -> buildReasonCountMap(schemeRegularityRepository.getOutageReasonSchemeCountByDepartment(
                        tenantId, parentDepartmentId, startDate, endDate)));
        List<SchemeRegularityRepository.ChildRegionRef> childRegions =
                schemeRegularityRepository.getChildRegionsByDepartment(tenantId, parentDepartmentId);
        List<SchemeRegularityRepository.ChildRegionOutageReasonSchemeCount> childRows =
                aggregateChildOutageRowsOrNull(tenantId, "DEPT", parentDepartmentId, parentDepartmentLevel, startDate, endDate);
        if (childRows == null) {
            childRows = schemeRegularityRepository.getChildOutageReasonSchemeCountByDepartment(
                    tenantId, parentDepartmentId, startDate, endDate);
        }

        OutageReasonSchemeCountResponse response = OutageReasonSchemeCountResponse.builder()
                .lgdId(null)
                .departmentId(parentDepartmentId)
                .startDate(startDate)
                .endDate(endDate)
                .parentLgdLevel(null)
                .parentDepartmentLevel(parentDepartmentLevel)
                .outageReasonSchemeCount(parentOutageMap)
                .childRegionCount(childRegions.size())
                .childRegions(buildChildOutageRegions(
                        childRegions,
                        childRows,
                        SchemeRegularityRepository.ChildRegionOutageReasonSchemeCount::departmentId))
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public UserOutageReasonSchemeCountResponse getOutageReasonSchemeCountByUser(
            Integer tenantId, Integer userId, LocalDate startDate, LocalDate endDate) {
        validateUserInput(userId);
        validateDateRange(startDate, endDate);

        List<SchemeRegularityRepository.OutageReasonSchemeCount> rows =
                schemeRegularityRepository.getOutageReasonSchemeCountByUser(tenantId, userId, startDate, endDate);
        List<SchemeRegularityRepository.DailyOutageReasonSchemeCount> dailyRows =
                schemeRegularityRepository.getDailyOutageReasonSchemeCountByUser(tenantId, userId, startDate, endDate);
        Integer schemeCount = schemeRegularityRepository.getSchemeCountByUser(tenantId, userId);

        Map<LocalDate, Map<String, Integer>> dailyReasonCountMap = new LinkedHashMap<>();
        LocalDate currentDate = startDate;
        while (!currentDate.isAfter(endDate)) {
            dailyReasonCountMap.put(currentDate, new LinkedHashMap<>());
            currentDate = currentDate.plusDays(1);
        }
        for (SchemeRegularityRepository.DailyOutageReasonSchemeCount row : dailyRows) {
            Map<String, Integer> reasonCount = dailyReasonCountMap.get(row.date());
            if (reasonCount == null) {
                continue;
            }
            reasonCount.put(
                    row.outageReason(),
                    row.schemeCount() == null ? 0 : row.schemeCount());
        }

        return UserOutageReasonSchemeCountResponse.builder()
                .userId(userId)
                .startDate(startDate)
                .endDate(endDate)
                .schemeCount(schemeCount == null ? 0 : schemeCount)
                .outageReasonSchemeCount(buildReasonCountMap(rows))
                .dailyOutageReasonDistribution(dailyReasonCountMap.entrySet().stream()
                        .map(entry -> UserOutageReasonSchemeCountResponse.DailyOutageReasonDistribution.builder()
                                .date(entry.getKey())
                                .outageReasonSchemeCount(entry.getValue())
                                .build())
                        .toList())
                .build();
    }

    @Override
    public UserOutageReasonSchemeCountResponse getOutageReasonSchemeCountByUserUuid(
            Integer tenantId, UUID userUuid, LocalDate startDate, LocalDate endDate) {
        return getOutageReasonSchemeCountByUser(tenantId, resolveUserIdByUuid(userUuid), startDate, endDate);
    }

    @Override
    public NonSubmissionReasonSchemeCountResponse getNonSubmissionReasonSchemeCountByLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);

        String cacheKey = NON_SUBMISSION_REASON_SCHEME_COUNT_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":parent_lgd:" + parentLgdId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v1";
        NonSubmissionReasonSchemeCountResponse cached =
                readFromCache(cacheKey, NonSubmissionReasonSchemeCountResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentLgdLevel = schemeRegularityRepository.getLgdLevelForTenant(tenantId, parentLgdId);
        if (parentLgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }

        Map<String, Integer> parentNonSubmissionMap = resolveReasonParentMap(tenantId, "LGD", parentLgdId, false,
                startDate, endDate,
                () -> buildNonSubmissionReasonCountMap(schemeRegularityRepository.getNonSubmissionReasonSchemeCountByLgd(
                        tenantId, parentLgdId, startDate, endDate)));
        List<SchemeRegularityRepository.ChildRegionRef> childRegions =
                schemeRegularityRepository.getChildRegionsByLgd(tenantId, parentLgdId);
        List<SchemeRegularityRepository.ChildRegionNonSubmissionReasonSchemeCount> childRows =
                aggregateChildNonSubmissionRowsOrNull(tenantId, "LGD", parentLgdId, parentLgdLevel, startDate, endDate);
        if (childRows == null) {
            childRows = schemeRegularityRepository.getChildNonSubmissionReasonSchemeCountByLgd(
                    tenantId, parentLgdId, startDate, endDate);
        }

        NonSubmissionReasonSchemeCountResponse response = NonSubmissionReasonSchemeCountResponse.builder()
                .lgdId(parentLgdId)
                .departmentId(null)
                .startDate(startDate)
                .endDate(endDate)
                .parentLgdLevel(parentLgdLevel)
                .parentDepartmentLevel(null)
                .nonSubmissionReasonSchemeCount(parentNonSubmissionMap)
                .childRegionCount(childRegions.size())
                .childRegions(buildChildNonSubmissionRegions(
                        childRegions,
                        childRows,
                        SchemeRegularityRepository.ChildRegionNonSubmissionReasonSchemeCount::lgdId))
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public NonSubmissionReasonSchemeCountResponse getNonSubmissionReasonSchemeCountByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);
        Integer parentDepartmentLevel =
                schemeRegularityRepository.getDepartmentLevelForTenant(tenantId, parentDepartmentId);
        if (parentDepartmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }

        Map<String, Integer> parentNonSubmissionMap = resolveReasonParentMap(tenantId, "DEPT", parentDepartmentId, false,
                startDate, endDate,
                () -> buildNonSubmissionReasonCountMap(schemeRegularityRepository.getNonSubmissionReasonSchemeCountByDepartment(
                        tenantId, parentDepartmentId, startDate, endDate)));
        List<SchemeRegularityRepository.ChildRegionRef> childRegions =
                schemeRegularityRepository.getChildRegionsByDepartment(tenantId, parentDepartmentId);
        List<SchemeRegularityRepository.ChildRegionNonSubmissionReasonSchemeCount> childRows =
                aggregateChildNonSubmissionRowsOrNull(tenantId, "DEPT", parentDepartmentId, parentDepartmentLevel, startDate, endDate);
        if (childRows == null) {
            childRows = schemeRegularityRepository.getChildNonSubmissionReasonSchemeCountByDepartment(
                    tenantId, parentDepartmentId, startDate, endDate);
        }

        return NonSubmissionReasonSchemeCountResponse.builder()
                .lgdId(null)
                .departmentId(parentDepartmentId)
                .startDate(startDate)
                .endDate(endDate)
                .parentLgdLevel(null)
                .parentDepartmentLevel(parentDepartmentLevel)
                .nonSubmissionReasonSchemeCount(parentNonSubmissionMap)
                .childRegionCount(childRegions.size())
                .childRegions(buildChildNonSubmissionRegions(
                        childRegions,
                        childRows,
                        SchemeRegularityRepository.ChildRegionNonSubmissionReasonSchemeCount::departmentId))
                .build();
    }

    @Override
    public UserNonSubmissionReasonSchemeCountResponse getNonSubmissionReasonSchemeCountByUser(
            Integer tenantId, Integer userId, LocalDate startDate, LocalDate endDate) {
        validateUserInput(userId);
        validateDateRange(startDate, endDate);

        List<SchemeRegularityRepository.NonSubmissionReasonSchemeCount> rows =
                schemeRegularityRepository.getNonSubmissionReasonSchemeCountByUser(tenantId, userId, startDate, endDate);
        List<SchemeRegularityRepository.DailyNonSubmissionReasonSchemeCount> dailyRows =
                schemeRegularityRepository.getDailyNonSubmissionReasonSchemeCountByUser(tenantId, userId, startDate, endDate);
        Integer schemeCount = schemeRegularityRepository.getSchemeCountByUser(tenantId, userId);

        Map<LocalDate, Map<String, Integer>> dailyReasonCountMap = new LinkedHashMap<>();
        LocalDate currentDate = startDate;
        while (!currentDate.isAfter(endDate)) {
            dailyReasonCountMap.put(currentDate, new LinkedHashMap<>());
            currentDate = currentDate.plusDays(1);
        }
        for (SchemeRegularityRepository.DailyNonSubmissionReasonSchemeCount row : dailyRows) {
            Map<String, Integer> reasonCount = dailyReasonCountMap.get(row.date());
            if (reasonCount == null) {
                continue;
            }
            reasonCount.put(
                    row.nonSubmissionReason(),
                    row.schemeCount() == null ? 0 : row.schemeCount());
        }

        return UserNonSubmissionReasonSchemeCountResponse.builder()
                .userId(userId)
                .startDate(startDate)
                .endDate(endDate)
                .schemeCount(schemeCount == null ? 0 : schemeCount)
                .nonSubmissionReasonSchemeCount(buildNonSubmissionReasonCountMap(rows))
                .dailyNonSubmissionReasonDistribution(dailyReasonCountMap.entrySet().stream()
                        .map(entry -> UserNonSubmissionReasonSchemeCountResponse.DailyNonSubmissionReasonDistribution.builder()
                                .date(entry.getKey())
                                .nonSubmissionReasonSchemeCount(entry.getValue())
                                .build())
                        .toList())
                .build();
    }

    @Override
    public UserNonSubmissionReasonSchemeCountResponse getNonSubmissionReasonSchemeCountByUserUuid(
            Integer tenantId, UUID userUuid, LocalDate startDate, LocalDate endDate) {
        return getNonSubmissionReasonSchemeCountByUser(tenantId, resolveUserIdByUuid(userUuid), startDate, endDate);
    }

    @Override
    public UserSubmissionStatusResponse getSubmissionStatusByUser(
            Integer tenantId, Integer userId, LocalDate startDate, LocalDate endDate) {
        validateUserInput(userId);
        validateDateRange(startDate, endDate);

        Integer schemeCount = schemeRegularityRepository.getSchemeCountByUser(tenantId, userId);
        SchemeRegularityRepository.SubmissionStatusCount submissionStatusCount =
                schemeRegularityRepository.getSubmissionStatusCountByUser(tenantId, userId, startDate, endDate);
        List<SchemeRegularityRepository.DailySubmissionSchemeCount> dailyRows =
                schemeRegularityRepository.getDailySubmissionSchemeCountByUser(tenantId, userId, startDate, endDate);

        int totalSchemeCount = schemeCount == null ? 0 : schemeCount;
        Map<LocalDate, Integer> dailySubmittedSchemeCountMap = new LinkedHashMap<>();
        LocalDate currentDate = startDate;
        while (!currentDate.isAfter(endDate)) {
            dailySubmittedSchemeCountMap.put(currentDate, 0);
            currentDate = currentDate.plusDays(1);
        }
        for (SchemeRegularityRepository.DailySubmissionSchemeCount row : dailyRows) {
            if (!dailySubmittedSchemeCountMap.containsKey(row.date())) {
                continue;
            }
            dailySubmittedSchemeCountMap.put(row.date(), row.submittedSchemeCount() == null ? 0 : row.submittedSchemeCount());
        }

        return UserSubmissionStatusResponse.builder()
                .userId(userId)
                .startDate(startDate)
                .endDate(endDate)
                .schemeCount(totalSchemeCount)
                .compliantSubmissionCount(
                        submissionStatusCount.compliantSubmissionCount() == null
                                ? 0
                                : submissionStatusCount.compliantSubmissionCount())
                .anomalousSubmissionCount(
                        submissionStatusCount.anomalousSubmissionCount() == null
                                ? 0
                                : submissionStatusCount.anomalousSubmissionCount())
                .dailySubmissionSchemeDistribution(dailySubmittedSchemeCountMap.entrySet().stream()
                        .map(entry -> UserSubmissionStatusResponse.DailySubmissionSchemeDistribution.builder()
                                .date(entry.getKey())
                                .submittedSchemeCount(entry.getValue())
                                .build())
                        .toList())
                .build();
    }

    @Override
    public UserSubmissionStatusResponse getSubmissionStatusByUserUuid(
            Integer tenantId, UUID userUuid, LocalDate startDate, LocalDate endDate) {
        return getSubmissionStatusByUser(tenantId, resolveUserIdByUuid(userUuid), startDate, endDate);
    }

    @Override
    public SubmissionStatusSummaryResponse getSubmissionStatusSummaryByLgd(
            Integer tenantId, Integer lgdId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateLgdInput(lgdId);
        validateDateRange(startDate, endDate);

        String cacheKey = SUBMISSION_STATUS_SUMMARY_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":lgd:" + lgdId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v2";
        SubmissionStatusSummaryResponse cached =
                readFromCache(cacheKey, SubmissionStatusSummaryResponse.class);
        if (cached != null) {
            return cached;
        }

        SubmissionStatusSummaryResponse aggResponse =
                aggregateSubmissionStatusOrNull(tenantId, "LGD", lgdId, startDate, endDate);
        if (aggResponse != null) {
            writeToCache(cacheKey, aggResponse);
            return aggResponse;
        }

        Integer schemeCount = schemeRegularityRepository.getSchemeCountByLgd(tenantId, lgdId);
        SchemeRegularityRepository.SubmissionStatusCount submissionStatusCount =
                schemeRegularityRepository.getSubmissionStatusCountByLgd(tenantId, lgdId, startDate, endDate);

        SubmissionStatusSummaryResponse response = SubmissionStatusSummaryResponse.builder()
                .schemeCount(schemeCount == null ? 0 : schemeCount)
                .compliantSubmissionCount(
                        submissionStatusCount.compliantSubmissionCount() == null
                                ? 0
                                : submissionStatusCount.compliantSubmissionCount())
                .anomalousSubmissionCount(
                        submissionStatusCount.anomalousSubmissionCount() == null
                                ? 0
                                : submissionStatusCount.anomalousSubmissionCount())
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public SubmissionStatusSummaryResponse getSubmissionStatusSummaryByDepartment(
            Integer tenantId, Integer departmentId, LocalDate startDate, LocalDate endDate) {
        validateTenantInput(tenantId);
        validateDepartmentInput(departmentId);
        validateDateRange(startDate, endDate);

        SubmissionStatusSummaryResponse aggResponse =
                aggregateSubmissionStatusOrNull(tenantId, "DEPT", departmentId, startDate, endDate);
        if (aggResponse != null) {
            return aggResponse;
        }

        Integer schemeCount = schemeRegularityRepository.getSchemeCountByDepartment(tenantId, departmentId);
        SchemeRegularityRepository.SubmissionStatusCount submissionStatusCount =
                schemeRegularityRepository.getSubmissionStatusCountByDepartment(tenantId, departmentId, startDate, endDate);

        return SubmissionStatusSummaryResponse.builder()
                .schemeCount(schemeCount == null ? 0 : schemeCount)
                .compliantSubmissionCount(
                        submissionStatusCount.compliantSubmissionCount() == null
                                ? 0
                                : submissionStatusCount.compliantSubmissionCount())
                .anomalousSubmissionCount(
                        submissionStatusCount.anomalousSubmissionCount() == null
                                ? 0
                                : submissionStatusCount.anomalousSubmissionCount())
                .build();
    }

    @Override
    public Map<String, Integer> getSchemeStatusCountByLgd(Integer tenantId, Integer lgdId) {
        validateTenantInput(tenantId);
        validateLgdInput(lgdId);

        String cacheKey = SCHEME_STATUS_COUNT_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":lgd:" + lgdId
                + ":v1";
        @SuppressWarnings("unchecked")
        Map<String, Integer> cached = (Map<String, Integer>) (Map<?, ?>) readFromCache(cacheKey, Map.class);
        if (cached != null) {
            return cached;
        }

        SchemeRegularityRepository.SchemeStatusCount count =
                schemeRegularityRepository.getSchemeStatusCountByLgd(tenantId, lgdId);
        Map<String, Integer> response = Map.of(
                SchemeStatus.ACTIVE.name().toLowerCase() + "_schemes_count",
                count.activeSchemeCount() == null ? 0 : count.activeSchemeCount(),
                SchemeStatus.INACTIVE.name().toLowerCase() + "_schemes_count",
                count.inactiveSchemeCount() == null ? 0 : count.inactiveSchemeCount());
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public Map<String, Integer> getSchemeStatusCountByDepartment(Integer tenantId, Integer departmentId) {
        validateTenantInput(tenantId);
        validateDepartmentInput(departmentId);
        SchemeRegularityRepository.SchemeStatusCount count =
                schemeRegularityRepository.getSchemeStatusCountByDepartment(tenantId, departmentId);
        return Map.of(
                SchemeStatus.ACTIVE.name().toLowerCase() + "_schemes_count",
                count.activeSchemeCount() == null ? 0 : count.activeSchemeCount(),
                SchemeStatus.INACTIVE.name().toLowerCase() + "_schemes_count",
                count.inactiveSchemeCount() == null ? 0 : count.inactiveSchemeCount());
    }

    @Override
    public CriticalSchemesResponse getCriticalSchemesByLgd(
            Integer tenantId, Integer lgdId, boolean list, Integer page, Integer limit) {
        validateTenantInput(tenantId);
        validateLgdInput(lgdId);

        int sanitizedDays = Math.max(0, criticalAfterDays);
        LocalDate cutoffDate = LocalDate.now(IST_ZONE).minusDays(sanitizedDays);

        long criticalCount = resolveCriticalCount(tenantId, "LGD", lgdId, cutoffDate,
                () -> schemeRegularityRepository.getCriticalSchemeCountByLgd(tenantId, lgdId, cutoffDate));
        if (!list) {
            return CriticalSchemesResponse.builder()
                    .criticalSchemeCount(criticalCount)
                    .list(false)
                    .page(null)
                    .limit(null)
                    .schemes(null)
                    .build();
        }

        int effectivePage = page == null ? 1 : page;
        int effectiveLimit = limit == null ? DEFAULT_PAGE_COUNT : limit;
        if (effectivePage < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (effectiveLimit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        int offset = (effectivePage - 1) * effectiveLimit;
        List<SchemeRegularityRepository.CriticalSchemeRow> rows =
                schemeRegularityRepository.getCriticalSchemesByLgd(tenantId, lgdId, cutoffDate, effectiveLimit, offset);

        return CriticalSchemesResponse.builder()
                .criticalSchemeCount(criticalCount)
                .list(true)
                .page(effectivePage)
                .limit(effectiveLimit)
                .schemes(rows.stream()
                        .map(r -> CriticalSchemesResponse.CriticalSchemeListItem.builder()
                                .schemeId(r.schemeId())
                                .schemeName(r.schemeName())
                                .stateSchemeId(r.stateSchemeId())
                                .centreSchemeId(r.centreSchemeId())
                                .lastSuppliedDate(r.lastSuppliedDate())
                                .build())
                        .toList())
                .build();
    }

    @Override
    public CriticalSchemesResponse getCriticalSchemesByDepartment(
            Integer tenantId, Integer departmentId, boolean list, Integer page, Integer limit) {
        validateTenantInput(tenantId);
        validateDepartmentInput(departmentId);

        int sanitizedDays = Math.max(0, criticalAfterDays);
        LocalDate cutoffDate = LocalDate.now(IST_ZONE).minusDays(sanitizedDays);

        long criticalCount = resolveCriticalCount(tenantId, "DEPT", departmentId, cutoffDate,
                () -> schemeRegularityRepository.getCriticalSchemeCountByDepartment(tenantId, departmentId, cutoffDate));
        if (!list) {
            return CriticalSchemesResponse.builder()
                    .criticalSchemeCount(criticalCount)
                    .list(false)
                    .page(null)
                    .limit(null)
                    .schemes(null)
                    .build();
        }

        int effectivePage = page == null ? 1 : page;
        int effectiveLimit = limit == null ? DEFAULT_PAGE_COUNT : limit;
        if (effectivePage < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (effectiveLimit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        int offset = (effectivePage - 1) * effectiveLimit;
        List<SchemeRegularityRepository.CriticalSchemeRow> rows =
                schemeRegularityRepository.getCriticalSchemesByDepartment(tenantId, departmentId, cutoffDate, effectiveLimit, offset);

        return CriticalSchemesResponse.builder()
                .criticalSchemeCount(criticalCount)
                .list(true)
                .page(effectivePage)
                .limit(effectiveLimit)
                .schemes(rows.stream()
                        .map(r -> CriticalSchemesResponse.CriticalSchemeListItem.builder()
                                .schemeId(r.schemeId())
                                .schemeName(r.schemeName())
                                .stateSchemeId(r.stateSchemeId())
                                .centreSchemeId(r.centreSchemeId())
                                .lastSuppliedDate(r.lastSuppliedDate())
                                .build())
                        .toList())
                .build();
    }

    @Override
    public CriticalSchemesResponse getCriticalSchemesByUser(
            Integer tenantId, Integer userId, boolean list, Integer page, Integer limit) {
        validateTenantInput(tenantId);
        validateUserInput(userId);

        int sanitizedDays = Math.max(0, criticalAfterDays);
        LocalDate cutoffDate = LocalDate.now(IST_ZONE).minusDays(sanitizedDays);

        long criticalCount = schemeRegularityRepository.getCriticalSchemeCountByUserSchemes(tenantId, userId, cutoffDate);
        if (!list) {
            return CriticalSchemesResponse.builder()
                    .criticalSchemeCount(criticalCount)
                    .list(false)
                    .page(null)
                    .limit(null)
                    .schemes(null)
                    .build();
        }

        int effectivePage = page == null ? 1 : page;
        int effectiveLimit = limit == null ? DEFAULT_PAGE_COUNT : limit;
        if (effectivePage < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (effectiveLimit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        int offset = (effectivePage - 1) * effectiveLimit;
        List<SchemeRegularityRepository.CriticalSchemeRow> rows =
                schemeRegularityRepository.getCriticalSchemesByUserSchemes(tenantId, userId, cutoffDate, effectiveLimit, offset);

        return CriticalSchemesResponse.builder()
                .criticalSchemeCount(criticalCount)
                .list(true)
                .page(effectivePage)
                .limit(effectiveLimit)
                .schemes(rows.stream()
                        .map(r -> CriticalSchemesResponse.CriticalSchemeListItem.builder()
                                .schemeId(r.schemeId())
                                .schemeName(r.schemeName())
                                .stateSchemeId(r.stateSchemeId())
                                .centreSchemeId(r.centreSchemeId())
                                .lastSuppliedDate(r.lastSuppliedDate())
                                .build())
                        .toList())
                .build();
    }

    @Override
    public CriticalSchemesResponse getCriticalSchemesByUserUuid(
            Integer tenantId, UUID userUuid, boolean list, Integer page, Integer limit) {
        return getCriticalSchemesByUser(tenantId, resolveUserIdByUuid(userUuid), list, page, limit);
    }

    @Override
    public ContinuousSchemesResponse getContinuousSchemesByLgd(
            Integer tenantId,
            Integer lgdId,
            LocalDate startDate,
            LocalDate endDate,
            boolean list,
            Integer page,
            Integer limit
    ) {
        validateTenantInput(tenantId);
        validateLgdInput(lgdId);
        validateDateRange(startDate, endDate);

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        long continuousCount = schemeRegularityRepository.getContinuousSchemeCountByLgd(
                tenantId, lgdId, startDate, endDate);
        if (!list) {
            return ContinuousSchemesResponse.builder()
                    .continuousSchemeCount(continuousCount)
                    .list(false)
                    .page(null)
                    .limit(null)
                    .startDate(startDate)
                    .endDate(endDate)
                    .daysInRange(daysInRange)
                    .schemes(null)
                    .build();
        }

        int effectivePage = page == null ? 1 : page;
        int effectiveLimit = limit == null ? DEFAULT_PAGE_COUNT : limit;
        if (effectivePage < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (effectiveLimit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        int offset = (effectivePage - 1) * effectiveLimit;
        List<SchemeRegularityRepository.ContinuousSchemeRow> rows =
                schemeRegularityRepository.getContinuousSchemesByLgd(
                        tenantId, lgdId, startDate, endDate, effectiveLimit, offset);

        return ContinuousSchemesResponse.builder()
                .continuousSchemeCount(continuousCount)
                .list(true)
                .page(effectivePage)
                .limit(effectiveLimit)
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .schemes(rows.stream()
                        .map(r -> ContinuousSchemesResponse.ContinuousSchemeListItem.builder()
                                .schemeId(r.schemeId())
                                .schemeName(r.schemeName())
                                .build())
                        .toList())
                .build();
    }

    @Override
    public ContinuousSchemesResponse getContinuousSchemesByDepartment(
            Integer tenantId,
            Integer departmentId,
            LocalDate startDate,
            LocalDate endDate,
            boolean list,
            Integer page,
            Integer limit
    ) {
        validateTenantInput(tenantId);
        validateDepartmentInput(departmentId);
        validateDateRange(startDate, endDate);

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        long continuousCount = schemeRegularityRepository.getContinuousSchemeCountByDepartment(
                tenantId, departmentId, startDate, endDate);
        if (!list) {
            return ContinuousSchemesResponse.builder()
                    .continuousSchemeCount(continuousCount)
                    .list(false)
                    .page(null)
                    .limit(null)
                    .startDate(startDate)
                    .endDate(endDate)
                    .daysInRange(daysInRange)
                    .schemes(null)
                    .build();
        }

        int effectivePage = page == null ? 1 : page;
        int effectiveLimit = limit == null ? DEFAULT_PAGE_COUNT : limit;
        if (effectivePage < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (effectiveLimit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        int offset = (effectivePage - 1) * effectiveLimit;
        List<SchemeRegularityRepository.ContinuousSchemeRow> rows =
                schemeRegularityRepository.getContinuousSchemesByDepartment(
                        tenantId, departmentId, startDate, endDate, effectiveLimit, offset);

        return ContinuousSchemesResponse.builder()
                .continuousSchemeCount(continuousCount)
                .list(true)
                .page(effectivePage)
                .limit(effectiveLimit)
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .schemes(rows.stream()
                        .map(r -> ContinuousSchemesResponse.ContinuousSchemeListItem.builder()
                                .schemeId(r.schemeId())
                                .schemeName(r.schemeName())
                                .build())
                        .toList())
                .build();
    }

    @Override
    public ContinuousSchemesResponse getContinuousSchemesByUser(
            Integer tenantId,
            Integer userId,
            LocalDate startDate,
            LocalDate endDate,
            boolean list,
            Integer page,
            Integer limit
    ) {
        validateTenantInput(tenantId);
        validateUserInput(userId);
        validateDateRange(startDate, endDate);

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        long continuousCount = schemeRegularityRepository.getContinuousSchemeCountByUserSchemes(
                tenantId, userId, startDate, endDate);
        if (!list) {
            return ContinuousSchemesResponse.builder()
                    .continuousSchemeCount(continuousCount)
                    .list(false)
                    .page(null)
                    .limit(null)
                    .startDate(startDate)
                    .endDate(endDate)
                    .daysInRange(daysInRange)
                    .schemes(null)
                    .build();
        }

        int effectivePage = page == null ? 1 : page;
        int effectiveLimit = limit == null ? DEFAULT_PAGE_COUNT : limit;
        if (effectivePage < 1) {
            throw new IllegalArgumentException("page must be >= 1");
        }
        if (effectiveLimit < 1) {
            throw new IllegalArgumentException("limit must be >= 1");
        }
        int offset = (effectivePage - 1) * effectiveLimit;
        List<SchemeRegularityRepository.ContinuousSchemeRow> rows =
                schemeRegularityRepository.getContinuousSchemesByUserSchemes(
                        tenantId, userId, startDate, endDate, effectiveLimit, offset);

        return ContinuousSchemesResponse.builder()
                .continuousSchemeCount(continuousCount)
                .list(true)
                .page(effectivePage)
                .limit(effectiveLimit)
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .schemes(rows.stream()
                        .map(r -> ContinuousSchemesResponse.ContinuousSchemeListItem.builder()
                                .schemeId(r.schemeId())
                                .schemeName(r.schemeName())
                                .build())
                        .toList())
                .build();
    }

    @Override
    public SchemeStatusAndTopReportingResponse getSchemeStatusAndTopReportingByLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate,
            Integer pageNumber, Integer limit, String sortBy, String sortDir) {
        validateTenantInput(tenantId);
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);
        pageNumber = pageNumber == null ? 1 : pageNumber;
        limit = limit == null ? DEFAULT_TOP_SCHEME_COUNT : limit;
        if (pageNumber < 1) {
            throw new IllegalArgumentException("page_number must be >= 1");
        }
        validateTopSchemeCount(limit);
        int offset = (pageNumber - 1) * limit;

        String cacheKey = SCHEME_STATUS_TOP_REPORTING_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":parent_lgd:" + parentLgdId
                + ":page:" + pageNumber
                + ":limit:" + limit
                + ":start:" + startDate
                + ":end:" + endDate
                + ":sort_by:" + Objects.toString(sortBy, "reportingRate")
                + ":sort_dir:" + Objects.toString(sortDir, "desc")
                + ":v4";
        SchemeStatusAndTopReportingResponse cached =
                readFromCache(cacheKey, SchemeStatusAndTopReportingResponse.class);
        if (cached != null) {
            return cached;
        }
        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        Integer parentLgdLevel = schemeRegularityRepository.getLgdLevelForTenant(tenantId, parentLgdId);

        SchemeRegularityRepository.SchemeStatusCount statusCount =
                schemeRegularityRepository.getSchemeStatusCountByLgd(tenantId, parentLgdId);
        long totalCount = schemeRegularityRepository.getSchemeCountByLgdInScope(tenantId, parentLgdId);
        String parentLgdCName = schemeRegularityRepository.getParentLgdCNameByLgd(tenantId, parentLgdId);
        String parentLgdTitle = schemeRegularityRepository.getParentLgdTitleByLgd(tenantId, parentLgdId);
        List<SchemeRegularityRepository.SchemeSubmissionMetrics> topSchemes =
                schemeRegularityRepository.getTopSchemeSubmissionMetricsByLgd(
                        tenantId, parentLgdId, startDate, endDate, limit, offset, sortBy, sortDir);

        SchemeStatusAndTopReportingResponse response = SchemeStatusAndTopReportingResponse.builder()
                .parentLgdId(parentLgdId)
                .parentDepartmentId(null)
                .parentLgdCName(parentLgdCName)
                .parentDepartmentCName(null)
                .parentLgdTitle(parentLgdTitle)
                .parentDepartmentTitle(null)
                .parentLgdLevel(parentLgdLevel)
                .parentDepartmentLevel(null)
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .activeSchemeCount(statusCount.activeSchemeCount() == null ? 0 : statusCount.activeSchemeCount())
                .inactiveSchemeCount(statusCount.inactiveSchemeCount() == null ? 0 : statusCount.inactiveSchemeCount())
                .totalCount(totalCount)
                .topSchemeCount(topSchemes.size())
                .topSchemes(topSchemes.stream()
                        .map(metric -> SchemeStatusAndTopReportingResponse.TopReportingScheme.builder()
                                .schemeId(metric.schemeId())
                                .schemeName(metric.schemeName())
                                .statusCode(metric.operatingStatus())
                                .status(resolveSchemeStatus(metric.operatingStatus()))
                                .submissionDays(metric.submissionDays())
                                .reportingRate(calculateReportingRate(metric.submissionDays(), daysInRange))
                                .totalWaterSupplied(metric.totalWaterSupplied())
                                .immediateParentLgdId(metric.immediateParentLgdId())
                                .immediateParentLgdCName(metric.immediateParentLgdCName())
                                .immediateParentLgdTitle(metric.immediateParentLgdTitle())
                                .immediateParentLgdLevel(metric.immediateParentLgdLevel())
                                .immediateParentDepartmentId(metric.immediateParentDepartmentId())
                                .immediateParentDepartmentCName(metric.immediateParentDepartmentCName())
                                .immediateParentDepartmentTitle(metric.immediateParentDepartmentTitle())
                                .immediateParentDepartmentLevel(metric.immediateParentDepartmentLevel())
                                .lgdLadder(buildLevelLadder(metric.level1LgdId(), metric.level2LgdId(), metric.level3LgdId(),
                                        metric.level4LgdId(), metric.level5LgdId(), metric.level6LgdId()))
                                .departmentLadder(buildLevelLadder(metric.level1DeptId(), metric.level2DeptId(), metric.level3DeptId(),
                                        metric.level4DeptId(), metric.level5DeptId(), metric.level6DeptId()))
                                .suppliedLgdLocations(buildSuppliedLgdLocations(metric))
                                .build())
                        .toList())
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public SchemeStatusAndTopReportingResponse getSchemeStatusAndTopReportingByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate,
            Integer pageNumber, Integer limit, String sortBy, String sortDir) {
        validateTenantInput(tenantId);
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);
        pageNumber = pageNumber == null ? 1 : pageNumber;
        limit = limit == null ? DEFAULT_TOP_SCHEME_COUNT : limit;
        if (pageNumber < 1) {
            throw new IllegalArgumentException("page_number must be >= 1");
        }
        validateTopSchemeCount(limit);
        int offset = (pageNumber - 1) * limit;
        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        Integer parentDepartmentLevel = schemeRegularityRepository.getDepartmentLevelForTenant(tenantId, parentDepartmentId);

        SchemeRegularityRepository.SchemeStatusCount statusCount =
                schemeRegularityRepository.getSchemeStatusCountByDepartment(tenantId, parentDepartmentId);
        long totalCount = schemeRegularityRepository.getSchemeCountByDepartmentInScope(tenantId, parentDepartmentId);
        String parentDepartmentCName =
                schemeRegularityRepository.getParentDepartmentCNameByDepartment(tenantId, parentDepartmentId);
        String parentDepartmentTitle =
                schemeRegularityRepository.getParentDepartmentTitleByDepartment(tenantId, parentDepartmentId);
        List<SchemeRegularityRepository.SchemeSubmissionMetrics> topSchemes =
                schemeRegularityRepository.getTopSchemeSubmissionMetricsByDepartment(
                        tenantId, parentDepartmentId, startDate, endDate, limit, offset, sortBy, sortDir);

        return SchemeStatusAndTopReportingResponse.builder()
                .parentLgdId(null)
                .parentDepartmentId(parentDepartmentId)
                .parentLgdCName(null)
                .parentDepartmentCName(parentDepartmentCName)
                .parentLgdTitle(null)
                .parentDepartmentTitle(parentDepartmentTitle)
                .parentLgdLevel(null)
                .parentDepartmentLevel(parentDepartmentLevel)
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .activeSchemeCount(statusCount.activeSchemeCount() == null ? 0 : statusCount.activeSchemeCount())
                .inactiveSchemeCount(statusCount.inactiveSchemeCount() == null ? 0 : statusCount.inactiveSchemeCount())
                .totalCount(totalCount)
                .topSchemeCount(topSchemes.size())
                .topSchemes(topSchemes.stream()
                        .map(metric -> SchemeStatusAndTopReportingResponse.TopReportingScheme.builder()
                                .schemeId(metric.schemeId())
                                .schemeName(metric.schemeName())
                                .statusCode(metric.operatingStatus())
                                .status(resolveSchemeStatus(metric.operatingStatus()))
                                .submissionDays(metric.submissionDays())
                                .reportingRate(calculateReportingRate(metric.submissionDays(), daysInRange))
                                .totalWaterSupplied(metric.totalWaterSupplied())
                                .immediateParentLgdId(metric.immediateParentLgdId())
                                .immediateParentLgdCName(metric.immediateParentLgdCName())
                                .immediateParentLgdTitle(metric.immediateParentLgdTitle())
                                .immediateParentLgdLevel(metric.immediateParentLgdLevel())
                                .immediateParentDepartmentId(metric.immediateParentDepartmentId())
                                .immediateParentDepartmentCName(metric.immediateParentDepartmentCName())
                                .immediateParentDepartmentTitle(metric.immediateParentDepartmentTitle())
                                .immediateParentDepartmentLevel(metric.immediateParentDepartmentLevel())
                                .lgdLadder(buildLevelLadder(metric.level1LgdId(), metric.level2LgdId(), metric.level3LgdId(),
                                        metric.level4LgdId(), metric.level5LgdId(), metric.level6LgdId()))
                                .departmentLadder(buildLevelLadder(metric.level1DeptId(), metric.level2DeptId(), metric.level3DeptId(),
                                        metric.level4DeptId(), metric.level5DeptId(), metric.level6DeptId()))
                                .suppliedLgdLocations(buildSuppliedLgdLocations(metric))
                                .build())
                        .toList())
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public void writeSchemeStatusAndTopReportingCsvByLgd(
            Integer tenantId,
            Integer parentLgdId,
            LocalDate startDate,
            LocalDate endDate,
            OutputStream outputStream,
            String sortBy,
            String sortDir) throws IOException {
        validateTenantInput(tenantId);
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);
        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, java.nio.charset.StandardCharsets.UTF_8))) {
            writer.write(AnalyticsControllerHelper.buildSchemeDashboardCsvHeader());
            writer.newLine();
            try {
                schemeRegularityRepository.streamSchemeSubmissionMetricsByLgd(
                        tenantId, parentLgdId, startDate, endDate, sortBy, sortDir, metric -> {
                            try {
                                writer.write(AnalyticsControllerHelper.buildSchemeDashboardCsvRow(
                                        metric.schemeId(),
                                        metric.schemeName(),
                                        metric.operatingStatus(),
                                        resolveSchemeStatus(metric.operatingStatus()),
                                        metric.submissionDays(),
                                        calculateReportingRate(metric.submissionDays(), daysInRange),
                                        metric.totalWaterSupplied(),
                                        metric.immediateParentLgdId(),
                                        metric.immediateParentLgdCName(),
                                        metric.immediateParentLgdTitle(),
                                        metric.immediateParentLgdLevel(),
                                        metric.immediateParentDepartmentId(),
                                        metric.immediateParentDepartmentCName(),
                                        metric.immediateParentDepartmentTitle(),
                                        metric.immediateParentDepartmentLevel(),
                                        metric.level1LgdId(),
                                        metric.level2LgdId(),
                                        metric.level3LgdId(),
                                        metric.level4LgdId(),
                                        metric.level5LgdId(),
                                        metric.level6LgdId(),
                                        metric.level1DeptId(),
                                        metric.level2DeptId(),
                                        metric.level3DeptId(),
                                        metric.level4DeptId(),
                                        metric.level5DeptId(),
                                        metric.level6DeptId()));
                                writer.newLine();
                            } catch (IOException ex) {
                                throw new UncheckedIOException(ex);
                            }
                        });
            } catch (UncheckedIOException ex) {
                throw ex.getCause();
            }
            writer.flush();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void writeSchemeStatusAndTopReportingCsvByDepartment(
            Integer tenantId,
            Integer parentDepartmentId,
            LocalDate startDate,
            LocalDate endDate,
            OutputStream outputStream,
            String sortBy,
            String sortDir) throws IOException {
        validateTenantInput(tenantId);
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);
        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;

        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outputStream, java.nio.charset.StandardCharsets.UTF_8))) {
            writer.write(AnalyticsControllerHelper.buildSchemeDashboardCsvHeader());
            writer.newLine();
            try {
                schemeRegularityRepository.streamSchemeSubmissionMetricsByDepartment(
                        tenantId, parentDepartmentId, startDate, endDate, sortBy, sortDir, metric -> {
                            try {
                                writer.write(AnalyticsControllerHelper.buildSchemeDashboardCsvRow(
                                        metric.schemeId(),
                                        metric.schemeName(),
                                        metric.operatingStatus(),
                                        resolveSchemeStatus(metric.operatingStatus()),
                                        metric.submissionDays(),
                                        calculateReportingRate(metric.submissionDays(), daysInRange),
                                        metric.totalWaterSupplied(),
                                        metric.immediateParentLgdId(),
                                        metric.immediateParentLgdCName(),
                                        metric.immediateParentLgdTitle(),
                                        metric.immediateParentLgdLevel(),
                                        metric.immediateParentDepartmentId(),
                                        metric.immediateParentDepartmentCName(),
                                        metric.immediateParentDepartmentTitle(),
                                        metric.immediateParentDepartmentLevel(),
                                        metric.level1LgdId(),
                                        metric.level2LgdId(),
                                        metric.level3LgdId(),
                                        metric.level4LgdId(),
                                        metric.level5LgdId(),
                                        metric.level6LgdId(),
                                        metric.level1DeptId(),
                                        metric.level2DeptId(),
                                        metric.level3DeptId(),
                                        metric.level4DeptId(),
                                        metric.level5DeptId(),
                                        metric.level6DeptId()));
                                writer.newLine();
                            } catch (IOException ex) {
                                throw new UncheckedIOException(ex);
                            }
                        });
            } catch (UncheckedIOException ex) {
                throw ex.getCause();
            }
            writer.flush();
        }
    }

    private static Map<String, Integer> buildLevelLadder(
            Integer level1, Integer level2, Integer level3, Integer level4, Integer level5, Integer level6) {
        Map<String, Integer> ladder = new LinkedHashMap<>();
        ladder.put("level_1", level1);
        ladder.put("level_2", level2);
        ladder.put("level_3", level3);
        ladder.put("level_4", level4);
        ladder.put("level_5", level5);
        ladder.put("level_6", level6);
        return ladder;
    }

    private static List<SchemeStatusAndTopReportingResponse.SuppliedLgdLocation> buildSuppliedLgdLocations(
            SchemeRegularityRepository.SchemeSubmissionMetrics metric) {
        List<Integer> ids = metric.suppliedLgdLocationIds() == null ? List.of() : metric.suppliedLgdLocationIds();
        List<String> names = metric.suppliedLgdLocationCNames() == null ? List.of() : metric.suppliedLgdLocationCNames();
        List<String> titles = metric.suppliedLgdLocationTitles() == null ? List.of() : metric.suppliedLgdLocationTitles();
        List<Integer> levels = metric.suppliedLgdLocationLevels() == null ? List.of() : metric.suppliedLgdLocationLevels();

        List<SchemeStatusAndTopReportingResponse.SuppliedLgdLocation> locations = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            locations.add(SchemeStatusAndTopReportingResponse.SuppliedLgdLocation.builder()
                    .lgdId(ids.get(i))
                    .lgdCName(i < names.size() ? names.get(i) : null)
                    .title(i < titles.size() ? titles.get(i) : null)
                    .lgdLevel(i < levels.size() ? levels.get(i) : null)
                    .build());
        }
        return locations;
    }

    @Override
    public SchemeRegularityListResponse getSchemeRegionReportByLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate, Integer pageNumber, Integer count) {
        validateTenantInput(tenantId);
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);
        validatePaginationInput(pageNumber, count);

        String cacheKey = SCHEME_REGION_REPORT_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":parent_lgd:" + parentLgdId
                + ":page:" + (pageNumber == null ? "all" : pageNumber)
                + ":count:" + (count == null ? "all" : count)
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v1";
        SchemeRegularityListResponse cached =
                readFromCache(cacheKey, SchemeRegularityListResponse.class);
        if (cached != null) {
            return cached;
        }
        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;

        List<SchemeRegularityRepository.SchemeRegularityListMetrics> schemes =
                schemeRegularityRepository.getSchemeRegionReportByLgd(tenantId, parentLgdId, startDate, endDate);
        String parentLgdCName = schemeRegularityRepository.getParentLgdCNameByLgd(tenantId, parentLgdId);
        String parentLgdTitle = schemeRegularityRepository.getParentLgdTitleByLgd(tenantId, parentLgdId);

        int activeCount = (int) schemes.stream()
                .filter(s -> s.operatingStatus() != null && s.operatingStatus() > 0)
                .count();
        int inactiveCount = (int) schemes.stream()
                .filter(s -> s.operatingStatus() != null && s.operatingStatus() == 0)
                .count();

        List<SchemeRegularityListResponse.SchemeMetrics> allSchemeMetrics = schemes.stream()
                .map(metric -> SchemeRegularityListResponse.SchemeMetrics.builder()
                        .schemeId(metric.schemeId())
                        .schemeName(metric.schemeName())
                        .stateSchemeId(metric.stateSchemeId())
                        .centreSchemeId(metric.centreSchemeId())
                        .statusCode(metric.operatingStatus())
                        .status(resolveSchemeStatus(metric.operatingStatus()))
                        .supplyDays(metric.supplyDays())
                        .averageRegularity(calculateReportingRate(metric.supplyDays(), daysInRange))
                        .isRegular(metric.isRegular())
                        .submissionDays(metric.submissionDays())
                        .submissionRate(calculateReportingRate(metric.submissionDays(), daysInRange))
                        .build())
                .toList();
        List<SchemeRegularityListResponse.SchemeMetrics> schemeMetrics =
                paginateSchemeReport(allSchemeMetrics, pageNumber, count);

        SchemeRegularityListResponse response = SchemeRegularityListResponse.builder()
                .parentLgdId(parentLgdId)
                .parentDepartmentId(null)
                .parentLgdCName(parentLgdCName)
                .parentDepartmentCName(null)
                .parentLgdTitle(parentLgdTitle)
                .parentDepartmentTitle(null)
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .totalSchemeCount(schemes.size())
                .activeSchemeCount(activeCount)
                .inactiveSchemeCount(inactiveCount)
                .schemeCountInResponse(schemeMetrics.size())
                .schemes(schemeMetrics)
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public SchemeRegularityListResponse getSchemeRegionReportByDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate, Integer pageNumber, Integer count) {
        validateTenantInput(tenantId);
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);
        validatePaginationInput(pageNumber, count);
        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;

        List<SchemeRegularityRepository.SchemeRegularityListMetrics> schemes =
                schemeRegularityRepository.getSchemeRegionReportByDepartment(tenantId, parentDepartmentId, startDate, endDate);
        String parentDepartmentCName =
                schemeRegularityRepository.getParentDepartmentCNameByDepartment(tenantId, parentDepartmentId);
        String parentDepartmentTitle =
                schemeRegularityRepository.getParentDepartmentTitleByDepartment(tenantId, parentDepartmentId);

        int activeCount = (int) schemes.stream()
                .filter(s -> s.operatingStatus() != null && s.operatingStatus() > 0)
                .count();
        int inactiveCount = (int) schemes.stream()
                .filter(s -> s.operatingStatus() != null && s.operatingStatus() == 0)
                .count();

        List<SchemeRegularityListResponse.SchemeMetrics> allSchemeMetrics = schemes.stream()
                .map(metric -> SchemeRegularityListResponse.SchemeMetrics.builder()
                        .schemeId(metric.schemeId())
                        .schemeName(metric.schemeName())
                        .stateSchemeId(metric.stateSchemeId())
                        .centreSchemeId(metric.centreSchemeId())
                        .statusCode(metric.operatingStatus())
                        .status(resolveSchemeStatus(metric.operatingStatus()))
                        .supplyDays(metric.supplyDays())
                        .averageRegularity(calculateReportingRate(metric.supplyDays(), daysInRange))
                        .isRegular(metric.isRegular())
                        .submissionDays(metric.submissionDays())
                        .submissionRate(calculateReportingRate(metric.submissionDays(), daysInRange))
                        .build())
                .toList();
        List<SchemeRegularityListResponse.SchemeMetrics> schemeMetrics =
                paginateSchemeReport(allSchemeMetrics, pageNumber, count);

        return SchemeRegularityListResponse.builder()
                .parentLgdId(null)
                .parentDepartmentId(parentDepartmentId)
                .parentLgdCName(null)
                .parentDepartmentCName(parentDepartmentCName)
                .parentLgdTitle(null)
                .parentDepartmentTitle(parentDepartmentTitle)
                .startDate(startDate)
                .endDate(endDate)
                .daysInRange(daysInRange)
                .totalSchemeCount(schemes.size())
                .activeSchemeCount(activeCount)
                .inactiveSchemeCount(inactiveCount)
                .schemeCountInResponse(schemeMetrics.size())
                .schemes(schemeMetrics)
                .build();
    }

    private void validateLgdInput(Integer lgdId) {
        if (lgdId == null || lgdId <= 0) {
            throw new IllegalArgumentException("lgd_id must be a positive integer");
        }
    }

    private void validateTenantInput(Integer tenantId) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenant_id must be a positive integer");
        }
    }

    private void validateUserInput(Integer userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("user_id must be a positive integer");
        }
    }

    private Integer resolveUserIdByUuid(UUID userUuid) {
        if (userUuid == null) {
            throw new IllegalArgumentException("Authenticated user UUID is required");
        }
        return dimUserRepository.findByUuid(userUuid)
                .map(DimUser::getUserId)
                .orElseThrow(() -> new IllegalArgumentException("No user found for uuid: " + userUuid));
    }

    private void validateDepartmentInput(Integer parentDepartmentId) {
        if (parentDepartmentId == null || parentDepartmentId <= 0) {
            throw new IllegalArgumentException("parent_department_id must be a positive integer");
        }
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("start_date and end_date are required");
        }
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("end_date must be on or after start_date");
        }
    }

    /**
     * A single-day regularity request ({@code start == end}) is too small a sample for a share-of-days KPI,
     * so it expands to a trailing {@link #regularitySingleDayLookbackDays} window (inclusive of the requested
     * day). Returns the (possibly widened) start date. On the {@code /scheme-regularity/average} endpoints the
     * caller uses it for the cache key, the query, and the reported window so all three agree; on the national
     * dashboard it widens only the regularity query (other KPIs keep the literal window). Multi-day requests
     * pass through unchanged.
     */
    private LocalDate expandSingleDayWindowStart(LocalDate startDate, LocalDate endDate) {
        if (startDate.equals(endDate)) {
            return endDate.minusDays(Math.max(1, regularitySingleDayLookbackDays) - 1L);
        }
        return startDate;
    }

    private void validateTopSchemeCount(Integer topSchemeCount) {
        if (topSchemeCount == null || topSchemeCount <= 0) {
            throw new IllegalArgumentException("scheme_count must be a positive integer");
        }
    }

    private void validatePaginationInput(Integer pageNumber, Integer count) {
        if (pageNumber != null && pageNumber <= 0) {
            throw new IllegalArgumentException("page_number must be a positive integer");
        }
        if (count != null && count <= 0) {
            throw new IllegalArgumentException("count must be a positive integer");
        }
    }

    private List<SchemeRegularityListResponse.SchemeMetrics> paginateSchemeReport(
            List<SchemeRegularityListResponse.SchemeMetrics> schemes, Integer pageNumber, Integer count) {
        if (pageNumber == null && count == null) {
            return schemes;
        }
        int effectivePage = pageNumber == null ? 1 : pageNumber;
        int effectiveCount = count == null ? DEFAULT_PAGE_COUNT : count;
        int fromIndex = (effectivePage - 1) * effectiveCount;
        if (fromIndex >= schemes.size()) {
            return List.of();
        }
        int toIndex = Math.min(fromIndex + effectiveCount, schemes.size());
        return schemes.subList(fromIndex, toIndex);
    }

    private BigDecimal calculateReportingRate(Integer submissionDays, Integer daysInRange) {
        if (submissionDays == null || daysInRange == null || daysInRange <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(submissionDays)
                .divide(BigDecimal.valueOf(daysInRange), 4, RoundingMode.HALF_UP);
    }

    private String resolveSchemeStatus(Integer statusCode) {
        if (statusCode == null) {
            return "unknown";
        }
        return statusCode > 0 ? "active" : "inactive";
    }

    private void validateScaleInput(PeriodScale scale) {
        if (scale == null) {
            throw new IllegalArgumentException("scale is required and must be one of: day, week, month, quarter, year");
        }
    }

    private PeriodicWaterQuantityResponse buildPeriodicWaterQuantityResponse(
            Integer lgdId,
            Integer departmentId,
            LocalDate startDate,
            LocalDate endDate,
            PeriodScale scale,
            List<SchemeRegularityRepository.PeriodicWaterQuantityMetrics> metrics) {
        List<PeriodicWaterQuantityResponse.PeriodicWaterQuantityPeriodMetric> periodicMetrics = metrics.stream()
                .map(metric -> PeriodicWaterQuantityResponse.PeriodicWaterQuantityPeriodMetric.builder()
                        .periodStartDate(metric.periodStartDate())
                        .periodEndDate(metric.periodEndDate().isAfter(endDate) ? endDate : metric.periodEndDate())
                        .averageWaterQuantity(metric.averageWaterQuantity())
                        .householdCount(metric.householdCount())
                        .achievedFhtcCount(metric.achievedFhtcCount())
                        .plannedFhtcCount(metric.plannedFhtcCount())
                        .build())
                .toList();

        return PeriodicWaterQuantityResponse.builder()
                .lgdId(lgdId)
                .departmentId(departmentId)
                .scale(scale.name().toLowerCase())
                .startDate(startDate)
                .endDate(endDate)
                .periodCount(periodicMetrics.size())
                .metrics(periodicMetrics)
                .build();
    }

    private PeriodicSchemeRegularityResponse buildPeriodicSchemeRegularityResponse(
            Integer lgdId,
            Integer departmentId,
            LocalDate startDate,
            LocalDate endDate,
            PeriodScale scale,
            List<SchemeRegularityRepository.PeriodicSchemeRegularityMetrics> metrics) {
        int schemeCount = metrics.isEmpty() ? 0 : metrics.getFirst().schemeCount();
        List<PeriodicSchemeRegularityResponse.PeriodicSchemeRegularityPeriodMetric> periodicMetrics =
                metrics.stream()
                        .map(metric -> {
                            // Cap the bucket's dates to the request window for display. The KPI's per-bucket
                            // regular count was already classified in SQL against these same capped days, so
                            // Java must not recompute the day count independently.
                            LocalDate cappedPeriodStart =
                                    metric.periodStartDate().isBefore(startDate) ? startDate : metric.periodStartDate();
                            LocalDate cappedPeriodEnd =
                                    metric.periodEndDate().isAfter(endDate) ? endDate : metric.periodEndDate();

                            BigDecimal averageRegularity = RegularityThresholdFilter.regularityRate(
                                    metric.regularSchemeCount(), metric.schemeCount());

                            return PeriodicSchemeRegularityResponse.PeriodicSchemeRegularityPeriodMetric.builder()
                                    .periodStartDate(cappedPeriodStart)
                                    .periodEndDate(cappedPeriodEnd)
                                    .totalSupplyDays(metric.totalSupplyDays())
                                    .regularSchemeCount(metric.regularSchemeCount())
                                    .totalWaterQuantity(metric.totalWaterQuantity())
                                    .averageRegularity(averageRegularity)
                                    .build();
                        })
                        .toList();

        return PeriodicSchemeRegularityResponse.builder()
                .lgdId(lgdId)
                .departmentId(departmentId)
                .schemeCount(schemeCount)
                .scale(scale.name().toLowerCase())
                .startDate(startDate)
                .endDate(endDate)
                .periodCount(periodicMetrics.size())
                .metrics(periodicMetrics)
                .build();
    }

    private PeriodicNationalSchemeRegularityResponse buildPeriodicNationalSchemeRegularityResponse(
            LocalDate startDate,
            LocalDate endDate,
            PeriodScale scale,
            List<SchemeRegularityRepository.PeriodicSchemeRegularityMetrics> metrics) {
        int schemeCount = metrics.isEmpty() ? 0 : metrics.getFirst().schemeCount();
        long totalAchievedFhtcCount = metrics.isEmpty() || metrics.getFirst().totalAchievedFhtcCount() == null
                ? 0L
                : metrics.getFirst().totalAchievedFhtcCount();
        List<PeriodicNationalSchemeRegularityResponse.PeriodicNationalSchemeRegularityPeriodMetric> periodicMetrics =
                metrics.stream()
                        .map(metric -> {
                            // Cap the bucket's dates to the request window for display. The KPI's per-bucket
                            // regular count was already classified in SQL against these same capped days, so
                            // Java must not recompute the day count independently.
                            LocalDate cappedPeriodStart =
                                    metric.periodStartDate().isBefore(startDate) ? startDate : metric.periodStartDate();
                            LocalDate cappedPeriodEnd =
                                    metric.periodEndDate().isAfter(endDate) ? endDate : metric.periodEndDate();

                            BigDecimal averageRegularity = RegularityThresholdFilter.regularityRate(
                                    metric.regularSchemeCount(), metric.schemeCount());

                            return PeriodicNationalSchemeRegularityResponse.PeriodicNationalSchemeRegularityPeriodMetric
                                    .builder()
                                    .periodStartDate(cappedPeriodStart)
                                    .periodEndDate(cappedPeriodEnd)
                                    .schemeCount(metric.schemeCount())
                                    .totalSupplyDays(metric.totalSupplyDays())
                                    .regularSchemeCount(metric.regularSchemeCount())
                                    .totalWaterQuantity(metric.totalWaterQuantity())
                                    .averageRegularity(averageRegularity)
                                    .build();
                        })
                        .toList();

        return PeriodicNationalSchemeRegularityResponse.builder()
                .schemeCount(schemeCount)
                .totalAchievedFhtcCount(totalAchievedFhtcCount)
                .scale(scale.name().toLowerCase())
                .startDate(startDate)
                .endDate(endDate)
                .periodCount(periodicMetrics.size())
                .metrics(periodicMetrics)
                .build();
    }

    private PeriodicOutageReasonSchemeCountResponse buildPeriodicOutageReasonSchemeCountResponse(
            Integer lgdId,
            Integer departmentId,
            LocalDate startDate,
            LocalDate endDate,
            PeriodScale scale,
            List<SchemeRegularityRepository.PeriodicOutageReasonSchemeCountRow> rows) {
        Map<LocalDate, LocalDate> periodEndByStart = new LinkedHashMap<>();
        Map<LocalDate, Map<String, Integer>> reasonByPeriod = new LinkedHashMap<>();

        for (SchemeRegularityRepository.PeriodicOutageReasonSchemeCountRow row : rows) {
            LocalDate ps = row.periodStartDate();
            periodEndByStart.putIfAbsent(ps, row.periodEndDate());
            if (row.outageReason() != null && row.schemeCount() != null) {
                reasonByPeriod
                        .computeIfAbsent(ps, k -> new LinkedHashMap<>())
                        .merge(row.outageReason(), row.schemeCount(), Integer::sum);
            }
            if (row.outageReason() == null) {
                reasonByPeriod.putIfAbsent(ps, new LinkedHashMap<>());
            }
        }

        LinkedHashSet<LocalDate> orderedPeriodStarts = new LinkedHashSet<>();
        for (SchemeRegularityRepository.PeriodicOutageReasonSchemeCountRow row : rows) {
            orderedPeriodStarts.add(row.periodStartDate());
        }

        List<PeriodicOutageReasonSchemeCountResponse.PeriodicOutageMetric> periodicMetrics = new ArrayList<>();
        for (LocalDate ps : orderedPeriodStarts) {
            LocalDate pe = periodEndByStart.get(ps);
            LocalDate cappedPeriodStart = ps.isBefore(startDate) ? startDate : ps;
            LocalDate cappedPeriodEnd = pe.isAfter(endDate) ? endDate : pe;
            periodicMetrics.add(
                    PeriodicOutageReasonSchemeCountResponse.PeriodicOutageMetric.builder()
                            .periodStartDate(cappedPeriodStart)
                            .periodEndDate(cappedPeriodEnd)
                            .outageReasonSchemeCount(
                                    reasonByPeriod.getOrDefault(ps, new LinkedHashMap<>()))
                            .build());
        }

        return PeriodicOutageReasonSchemeCountResponse.builder()
                .lgdId(lgdId)
                .departmentId(departmentId)
                .scale(scale.name().toLowerCase())
                .startDate(startDate)
                .endDate(endDate)
                .periodCount(periodicMetrics.size())
                .metrics(periodicMetrics)
                .build();
    }

    private Map<String, Integer> buildReasonCountMap(
            List<SchemeRegularityRepository.OutageReasonSchemeCount> rows) {
        Map<String, Integer> reasonCountMap = new LinkedHashMap<>();
        for (SchemeRegularityRepository.OutageReasonSchemeCount row : rows) {
            reasonCountMap.put(
                    row.outageReason(),
                    row.schemeCount() == null ? 0 : row.schemeCount());
        }
        return reasonCountMap;
    }

    private Map<String, Integer> buildNonSubmissionReasonCountMap(
            List<SchemeRegularityRepository.NonSubmissionReasonSchemeCount> rows) {
        Map<String, Integer> reasonCountMap = new LinkedHashMap<>();
        for (SchemeRegularityRepository.NonSubmissionReasonSchemeCount row : rows) {
            reasonCountMap.put(
                    row.nonSubmissionReason(),
                    row.schemeCount() == null ? 0 : row.schemeCount());
        }
        return reasonCountMap;
    }

    private List<OutageReasonSchemeCountResponse.ChildRegionOutageReasonSchemeCount> buildChildOutageRegions(
            List<SchemeRegularityRepository.ChildRegionRef> childRegions,
            List<SchemeRegularityRepository.ChildRegionOutageReasonSchemeCount> childRows,
            Function<SchemeRegularityRepository.ChildRegionOutageReasonSchemeCount, Integer> regionIdExtractor) {
        Map<Integer, OutageReasonSchemeCountResponse.ChildRegionOutageReasonSchemeCount> childById = new LinkedHashMap<>();
        for (SchemeRegularityRepository.ChildRegionRef childRegion : childRegions) {
            Integer regionId = childRegion.lgdId() != null ? childRegion.lgdId() : childRegion.departmentId();
            childById.put(
                    regionId,
                    OutageReasonSchemeCountResponse.ChildRegionOutageReasonSchemeCount.builder()
                            .lgdId(childRegion.lgdId())
                            .departmentId(childRegion.departmentId())
                            .title(childRegion.title())
                            .outageReasonSchemeCount(new LinkedHashMap<>())
                            .build());
        }
        for (SchemeRegularityRepository.ChildRegionOutageReasonSchemeCount row : childRows) {
            Integer regionId = regionIdExtractor.apply(row);
            OutageReasonSchemeCountResponse.ChildRegionOutageReasonSchemeCount child = childById.get(regionId);
            if (child == null) {
                continue;
            }
            child.getOutageReasonSchemeCount().put(
                    row.outageReason(),
                    row.schemeCount() == null ? 0 : row.schemeCount());
        }
        return childById.values().stream().toList();
    }

    private List<NonSubmissionReasonSchemeCountResponse.ChildRegionNonSubmissionReasonSchemeCount> buildChildNonSubmissionRegions(
            List<SchemeRegularityRepository.ChildRegionRef> childRegions,
            List<SchemeRegularityRepository.ChildRegionNonSubmissionReasonSchemeCount> childRows,
            Function<SchemeRegularityRepository.ChildRegionNonSubmissionReasonSchemeCount, Integer> regionIdExtractor) {
        Map<Integer, NonSubmissionReasonSchemeCountResponse.ChildRegionNonSubmissionReasonSchemeCount> childById =
                new LinkedHashMap<>();
        for (SchemeRegularityRepository.ChildRegionRef childRegion : childRegions) {
            Integer regionId = childRegion.lgdId() != null ? childRegion.lgdId() : childRegion.departmentId();
            childById.put(
                    regionId,
                    NonSubmissionReasonSchemeCountResponse.ChildRegionNonSubmissionReasonSchemeCount.builder()
                            .lgdId(childRegion.lgdId())
                            .departmentId(childRegion.departmentId())
                            .title(childRegion.title())
                            .nonSubmissionReasonSchemeCount(new LinkedHashMap<>())
                            .build());
        }
        for (SchemeRegularityRepository.ChildRegionNonSubmissionReasonSchemeCount row : childRows) {
            Integer regionId = regionIdExtractor.apply(row);
            NonSubmissionReasonSchemeCountResponse.ChildRegionNonSubmissionReasonSchemeCount child = childById.get(regionId);
            if (child == null) {
                continue;
            }
            child.getNonSubmissionReasonSchemeCount().put(
                    row.nonSubmissionReason(),
                    row.schemeCount() == null ? 0 : row.schemeCount());
        }
        return childById.values().stream().toList();
    }

    private String getTenantStateCode(Integer tenantId) {
        DimTenant tenant = dimTenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found for tenant_id: " + tenantId));
        return tenant.getStateCode();
    }

    private JsonNode parseBoundaryGeoJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            log.warn("Failed to parse boundary GeoJSON: {}", e.getMessage());
            return null;
        }
    }

    private <T> T readFromCache(String cacheKey, Class<T> responseClass) {
        try {
            String payload = redisTemplate.opsForValue().get(cacheKey);
            if (payload == null || payload.isBlank()) {
                return null;
            }
            return objectMapper.readValue(payload, responseClass);
        } catch (Exception e) {
            log.warn("Failed to read scheme regularity cache [{}]: {}", cacheKey, e.getMessage());
            return null;
        }
    }

    private <T> T readFromCache(String cacheKey, TypeReference<T> typeReference) {
        try {
            String payload = redisTemplate.opsForValue().get(cacheKey);
            if (payload == null || payload.isBlank()) {
                return null;
            }
            return objectMapper.readValue(payload, typeReference);
        } catch (Exception e) {
            log.warn("Failed to read scheme regularity cache [{}]: {}", cacheKey, e.getMessage());
            return null;
        }
    }

    private void writeToCache(String cacheKey, Object response) {
        try {
            String payload = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(cacheKey, payload, Duration.ofHours(cacheTtlHours));
        } catch (Exception e) {
            log.warn("Failed to write scheme regularity cache [{}]: {}", cacheKey, e.getMessage());
        }
    }

    private void appendDebugLog(String hypothesisId, String location, String message, Map<String, Object> data) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("runId", "pre-fix");
            payload.put("hypothesisId", hypothesisId);
            payload.put("location", location);
            payload.put("message", message);
            payload.put("data", data);
            payload.put("timestamp", System.currentTimeMillis());
            Files.writeString(
                    Path.of(DEBUG_LOG_PATH),
                    objectMapper.writeValueAsString(payload) + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // Swallow debug logging failures.
        }
    }
}
