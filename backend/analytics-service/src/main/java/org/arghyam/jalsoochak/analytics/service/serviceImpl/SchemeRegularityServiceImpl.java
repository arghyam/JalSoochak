package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import org.arghyam.jalsoochak.analytics.dto.response.AverageSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.AverageWaterSupplyResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NonSubmissionReasonSchemeCountResponse;
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
import org.arghyam.jalsoochak.analytics.dto.response.UserNonSubmissionReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserOutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SubmissionStatusSummaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserSubmissionStatusResponse;
import org.arghyam.jalsoochak.analytics.entity.DimUser;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.enums.RegularityScope;
import org.arghyam.jalsoochak.analytics.enums.SchemeStatus;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.repository.DimUserRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.repository.SchemeRegularityRepository;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SchemeRegularityServiceImpl implements SchemeRegularityService {

    private static final Duration SCHEME_REGULARITY_CACHE_TTL = Duration.ofHours(24);
    private static final String SCHEME_REGULARITY_CACHE_PREFIX = ":scheme_regularity";
    private static final String READING_SUBMISSION_RATE_CACHE_PREFIX = ":reading_submission_rate";
    private static final String NATIONAL_DASHBOARD_CACHE_PREFIX = ":national:dashboard";
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

    private final SchemeRegularityRepository schemeRegularityRepository;
    private final DimTenantRepository dimTenantRepository;
    private final DimUserRepository dimUserRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    public AverageSchemeRegularityResponse getAverageSchemeRegularity(Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);
        // #region agent log
        appendDebugLog(
                "H1",
                "SchemeRegularityServiceImpl:getAverageSchemeRegularity:entry",
                "Regularity request entry",
                Map.of("parentLgdId", parentLgdId, "startDate", String.valueOf(startDate), "endDate", String.valueOf(endDate)));
        // #endregion

        String cacheKey = SCHEME_REGULARITY_CACHE_PREFIX
                + ":lgd:" + parentLgdId
                + ":start:" + startDate
                + ":end:" + endDate;
        AverageSchemeRegularityResponse cached = readFromCache(cacheKey, AverageSchemeRegularityResponse.class);
        if (cached != null) {
            return cached;
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        SchemeRegularityRepository.SchemeRegularityMetrics metrics;
        try {
            metrics = schemeRegularityRepository.getSchemeRegularityMetrics(parentLgdId, startDate, endDate);
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
                Map.of("daysInRange", daysInRange, "schemeCount", metrics.schemeCount(), "totalSupplyDays", metrics.totalSupplyDays()));
        // #endregion

        BigDecimal averageRegularity = BigDecimal.ZERO;
        if (metrics.schemeCount() > 0 && daysInRange > 0) {
            BigDecimal denominator = BigDecimal.valueOf((long) metrics.schemeCount() * daysInRange);
            averageRegularity = BigDecimal.valueOf(metrics.totalSupplyDays())
                    .divide(denominator, 4, RoundingMode.HALF_UP);
        }

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
                .averageRegularity(averageRegularity)
                .childRegionCount(0)
                .childRegions(List.of())
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public ReadingSubmissionRateResponse getReadingSubmissionRateByLgd(Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);
        // #region agent log
        appendDebugLog(
                "H3",
                "SchemeRegularityServiceImpl:getReadingSubmissionRateByLgd:entry",
                "Submission rate request entry",
                Map.of("parentLgdId", parentLgdId, "startDate", String.valueOf(startDate), "endDate", String.valueOf(endDate)));
        // #endregion

        String cacheKey = READING_SUBMISSION_RATE_CACHE_PREFIX
                + ":lgd:" + parentLgdId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v3";
        ReadingSubmissionRateResponse cached = readFromCache(cacheKey, ReadingSubmissionRateResponse.class);
        if (cached != null) {
            return cached;
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        Integer parentLgdLevel = schemeRegularityRepository.getLgdLevel(parentLgdId);
        SchemeRegularityRepository.SchemeRegularityMetrics metrics;
        try {
            metrics = schemeRegularityRepository.getReadingSubmissionRateMetricsByLgd(parentLgdId, startDate, endDate);
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
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);

        String cacheKey = SCHEME_REGULARITY_CACHE_PREFIX
                + ":department:" + parentDepartmentId
                + ":start:" + startDate
                + ":end:" + endDate;
        AverageSchemeRegularityResponse cached = readFromCache(cacheKey, AverageSchemeRegularityResponse.class);
        if (cached != null) {
            return cached;
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        SchemeRegularityRepository.SchemeRegularityMetrics metrics =
                schemeRegularityRepository.getSchemeRegularityMetricsByDepartment(parentDepartmentId, startDate, endDate);

        BigDecimal averageRegularity = BigDecimal.ZERO;
        if (metrics.schemeCount() > 0 && daysInRange > 0) {
            BigDecimal denominator = BigDecimal.valueOf((long) metrics.schemeCount() * daysInRange);
            averageRegularity = BigDecimal.valueOf(metrics.totalSupplyDays())
                    .divide(denominator, 4, RoundingMode.HALF_UP);
        }

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
                .averageRegularity(averageRegularity)
                .childRegionCount(0)
                .childRegions(List.of())
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public AverageSchemeRegularityResponse getAverageSchemeRegularityForChildRegions(
            Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);

        String cacheKey = SCHEME_REGULARITY_CACHE_PREFIX
                + ":lgd:" + parentLgdId
                + ":scope:child"
                + ":start:" + startDate
                + ":end:" + endDate;
        AverageSchemeRegularityResponse cached = readFromCache(cacheKey, AverageSchemeRegularityResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentLgdLevel = schemeRegularityRepository.getLgdLevel(parentLgdId);
        if (parentLgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        if (parentLgdLevel >= 6) {
            throw new IllegalArgumentException("No child LGD level available for parent_lgd_id: " + parentLgdId);
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        List<SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics> metrics =
                schemeRegularityRepository.getChildSchemeRegularityMetricsByLgd(parentLgdId, startDate, endDate);

        List<AverageSchemeRegularityResponse.ChildRegionRegularity> childRegions = metrics.stream()
                .map(m -> AverageSchemeRegularityResponse.ChildRegionRegularity.builder()
                        .lgdId(m.lgdId())
                        .departmentId(null)
                        .title(m.title())
                        .schemeCount(m.schemeCount())
                        .totalSupplyDays(m.totalSupplyDays())
                        .averageRegularity(m.averageRegularity())
                        .build())
                .toList();

        int totalSchemeCount = metrics.stream()
                .map(SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics::schemeCount)
                .mapToInt(Integer::intValue)
                .sum();
        int totalSupplyDays = metrics.stream()
                .map(SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics::totalSupplyDays)
                .mapToInt(Integer::intValue)
                .sum();
        BigDecimal averageRegularity = BigDecimal.ZERO;
        if (totalSchemeCount > 0 && daysInRange > 0) {
            averageRegularity = BigDecimal.valueOf(totalSupplyDays)
                    .divide(BigDecimal.valueOf((long) totalSchemeCount * daysInRange), 4, RoundingMode.HALF_UP);
        }

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
                .averageRegularity(averageRegularity)
                .childRegionCount(childRegions.size())
                .childRegions(childRegions)
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public AverageSchemeRegularityResponse getAverageSchemeRegularityByDepartmentForChildRegions(
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);

        String cacheKey = SCHEME_REGULARITY_CACHE_PREFIX
                + ":department:" + parentDepartmentId
                + ":scope:child"
                + ":start:" + startDate
                + ":end:" + endDate;
        AverageSchemeRegularityResponse cached = readFromCache(cacheKey, AverageSchemeRegularityResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentDepartmentLevel = schemeRegularityRepository.getDepartmentLevel(parentDepartmentId);
        if (parentDepartmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        if (parentDepartmentLevel >= 6) {
            throw new IllegalArgumentException("No child department level available for parent_department_id: " + parentDepartmentId);
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        List<SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics> metrics =
                schemeRegularityRepository.getChildSchemeRegularityMetricsByDepartment(parentDepartmentId, startDate, endDate);

        List<AverageSchemeRegularityResponse.ChildRegionRegularity> childRegions = metrics.stream()
                .map(m -> AverageSchemeRegularityResponse.ChildRegionRegularity.builder()
                        .lgdId(null)
                        .departmentId(m.departmentId())
                        .title(m.title())
                        .schemeCount(m.schemeCount())
                        .totalSupplyDays(m.totalSupplyDays())
                        .averageRegularity(m.averageRegularity())
                        .build())
                .toList();

        int totalSchemeCount = metrics.stream()
                .map(SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics::schemeCount)
                .mapToInt(Integer::intValue)
                .sum();
        int totalSupplyDays = metrics.stream()
                .map(SchemeRegularityRepository.ChildRegionSchemeRegularityMetrics::totalSupplyDays)
                .mapToInt(Integer::intValue)
                .sum();
        BigDecimal averageRegularity = BigDecimal.ZERO;
        if (totalSchemeCount > 0 && daysInRange > 0) {
            averageRegularity = BigDecimal.valueOf(totalSupplyDays)
                    .divide(BigDecimal.valueOf((long) totalSchemeCount * daysInRange), 4, RoundingMode.HALF_UP);
        }

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
                .averageRegularity(averageRegularity)
                .childRegionCount(childRegions.size())
                .childRegions(childRegions)
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public ReadingSubmissionRateResponse getReadingSubmissionRateByDepartment(
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);

        String cacheKey = READING_SUBMISSION_RATE_CACHE_PREFIX
                + ":department:" + parentDepartmentId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v3";
        ReadingSubmissionRateResponse cached = readFromCache(cacheKey, ReadingSubmissionRateResponse.class);
        if (cached != null) {
            return cached;
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        Integer parentDepartmentLevel = schemeRegularityRepository.getDepartmentLevel(parentDepartmentId);
        SchemeRegularityRepository.SchemeRegularityMetrics metrics =
                schemeRegularityRepository.getReadingSubmissionRateMetricsByDepartment(parentDepartmentId, startDate, endDate);

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
            Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);

        String cacheKey = READING_SUBMISSION_RATE_CACHE_PREFIX
                + ":lgd:" + parentLgdId
                + ":scope:child"
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v3";
        ReadingSubmissionRateResponse cached = readFromCache(cacheKey, ReadingSubmissionRateResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentLgdLevel = schemeRegularityRepository.getLgdLevel(parentLgdId);
        if (parentLgdLevel == null) {
            throw new IllegalArgumentException("lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }
        if (parentLgdLevel >= 6) {
            throw new IllegalArgumentException("No child LGD level available for parent_lgd_id: " + parentLgdId);
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        List<SchemeRegularityRepository.ChildRegionReadingSubmissionMetrics> metrics =
                schemeRegularityRepository.getChildReadingSubmissionRateMetricsByLgd(parentLgdId, startDate, endDate);

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
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);

        String cacheKey = READING_SUBMISSION_RATE_CACHE_PREFIX
                + ":department:" + parentDepartmentId
                + ":scope:child"
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v3";
        ReadingSubmissionRateResponse cached = readFromCache(cacheKey, ReadingSubmissionRateResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentDepartmentLevel = schemeRegularityRepository.getDepartmentLevel(parentDepartmentId);
        if (parentDepartmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }
        if (parentDepartmentLevel >= 6) {
            throw new IllegalArgumentException("No child department level available for parent_department_id: " + parentDepartmentId);
        }

        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        List<SchemeRegularityRepository.ChildRegionReadingSubmissionMetrics> metrics =
                schemeRegularityRepository.getChildReadingSubmissionRateMetricsByDepartment(
                        parentDepartmentId, startDate, endDate);

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
        return schemeRegularityRepository.getChildAveragePerformanceScoreByLgd(parentLgdId, startDate, endDate);
    }

    @Override
    public List<SchemeRegularityRepository.ChildRegionPerformanceScore> getChildAveragePerformanceScoreByDepartment(
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);
        return schemeRegularityRepository.getChildAveragePerformanceScoreByDepartment(
                parentDepartmentId, startDate, endDate);
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
                schemeRegularityRepository.getAverageWaterSupplyPerCurrentRegion(tenantId, startDate, endDate);

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

    private String buildNationalDashboardCacheKey(LocalDate startDate, LocalDate endDate) {
        return NATIONAL_DASHBOARD_CACHE_PREFIX
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v3";
    }

    private NationalDashboardResponse buildAndCacheNationalDashboard(
            LocalDate startDate, LocalDate endDate, String cacheKey) {
        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        List<SchemeRegularityRepository.ChildRegionWaterSupplyMetrics> quantityMetrics =
                schemeRegularityRepository.getAverageWaterSupplyPerNation(startDate, endDate);
        List<SchemeRegularityRepository.StateSchemeRegularityMetrics> regularityMetrics =
                schemeRegularityRepository.getStateWiseRegularityMetrics(startDate, endDate);
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
                    SchemeRegularityRepository.NationalDashboardTenantStateMetadata geo =
                            tenantStateMetadataByTenantId.get(metric.tenantId());
                    return NationalDashboardResponse.StateQuantityPerformance.builder()
                        .tenantId(metric.tenantId())
                        .lgdId(geo != null ? geo.lgdId() : null)
                        .tenantStatus(geo != null ? geo.tenantStatus() : null)
                        .boundary(parseBoundaryGeoJson(geo != null ? geo.boundaryGeoJson() : null))
                        .stateCode(metric.stateCode())
                        .stateTitle(metric.title())
                        .schemeCount(metric.schemeCount())
                        .totalHouseholdCount(metric.totalHouseholdCount())
                        .totalAchievedFhtcCount(metric.totalAchievedFhtcCount())
                        .totalPlannedFhtcCount(metric.totalPlannedFhtcCount())
                        .totalWaterSuppliedLiters(metric.totalWaterSuppliedLiters())
                        .avgWaterSupplyPerScheme(metric.avgWaterSupplyPerScheme())
                        .build();
                })
                .toList();

        List<NationalDashboardResponse.StateRegularity> stateWiseRegularity = regularityMetrics.stream()
                .map(metric -> {
                    SchemeRegularityRepository.NationalDashboardTenantStateMetadata geo =
                            tenantStateMetadataByTenantId.get(metric.tenantId());
                    BigDecimal averageRegularity = BigDecimal.ZERO;
                    if (metric.schemeCount() > 0 && daysInRange > 0) {
                        averageRegularity = BigDecimal.valueOf(metric.totalSupplyDays())
                                .divide(BigDecimal.valueOf((long) metric.schemeCount() * daysInRange), 4, RoundingMode.HALF_UP);
                    }
                    return NationalDashboardResponse.StateRegularity.builder()
                            .tenantId(metric.tenantId())
                            .lgdId(geo != null ? geo.lgdId() : null)
                            .tenantStatus(geo != null ? geo.tenantStatus() : null)
                            .boundary(parseBoundaryGeoJson(geo != null ? geo.boundaryGeoJson() : null))
                            .stateCode(metric.stateCode())
                            .stateTitle(metric.title())
                            .schemeCount(metric.schemeCount())
                            .totalSupplyDays(metric.totalSupplyDays())
                            .averageRegularity(averageRegularity)
                            .build();
                })
                .toList();

        List<NationalDashboardResponse.StateReadingSubmissionRate> stateWiseReadingSubmissionRate = submissionMetrics.stream()
                .map(metric -> {
                    SchemeRegularityRepository.NationalDashboardTenantStateMetadata geo =
                            tenantStateMetadataByTenantId.get(metric.tenantId());
                    BigDecimal readingSubmissionRate = BigDecimal.ZERO;
                    if (metric.schemeCount() > 0 && daysInRange > 0) {
                        readingSubmissionRate = BigDecimal.valueOf(metric.totalSubmissionDays())
                                .divide(BigDecimal.valueOf((long) metric.schemeCount() * daysInRange), 4, RoundingMode.HALF_UP);
                    }
                    return NationalDashboardResponse.StateReadingSubmissionRate.builder()
                            .tenantId(metric.tenantId())
                            .lgdId(geo != null ? geo.lgdId() : null)
                            .tenantStatus(geo != null ? geo.tenantStatus() : null)
                            .boundary(parseBoundaryGeoJson(geo != null ? geo.boundaryGeoJson() : null))
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
                .stateWiseQuantityPerformance(stateWiseQuantityPerformance)
                .stateWiseRegularity(stateWiseRegularity)
                .stateWiseReadingSubmissionRate(stateWiseReadingSubmissionRate)
                .overallOutageReasonDistribution(overallOutageReasonDistribution)
                .build();
        writeToCache(cacheKey, response);
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
                + ":v4";
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
            metrics = schemeRegularityRepository.getAverageWaterSupplyPerCurrentRegionByLgd(tenantId, lgdId, startDate, endDate);
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
                .build();
        writeToCache(cacheKey, response);
        return response;
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
                + ":v4";
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
            metrics = schemeRegularityRepository.getAverageWaterSupplyPerCurrentRegionByDepartment(tenantId, parentDepartmentId, startDate, endDate);
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
            Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);

        String cacheKey = REGION_WISE_WATER_QUANTITY_CACHE_PREFIX
                + ":parent_lgd:" + parentLgdId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v4";
        RegionWiseWaterQuantityResponse cached = readFromCache(cacheKey, RegionWiseWaterQuantityResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentLgdLevel = schemeRegularityRepository.getLgdLevel(parentLgdId);
        if (parentLgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }

        List<SchemeRegularityRepository.ChildRegionWaterQuantityMetrics> metrics =
                schemeRegularityRepository.getRegionWiseWaterQuantityByLgd(parentLgdId, startDate, endDate);

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
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);

        String cacheKey = REGION_WISE_WATER_QUANTITY_CACHE_PREFIX
                + ":parent_department:" + parentDepartmentId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v2";
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
                schemeRegularityRepository.getRegionWiseWaterQuantityByDepartment(
                        parentDepartmentId, startDate, endDate);

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
            Integer lgdId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        validateLgdInput(lgdId);
        validateDateRange(startDate, endDate);
        validateScaleInput(scale);

        String cacheKey = PERIODIC_WATER_QUANTITY_CACHE_PREFIX
                + ":lgd:" + lgdId
                + ":scale:" + scale.name().toLowerCase()
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v1";
        PeriodicWaterQuantityResponse cached = readFromCache(cacheKey, PeriodicWaterQuantityResponse.class);
        if (cached != null) {
            return cached;
        }

        List<SchemeRegularityRepository.PeriodicWaterQuantityMetrics> metrics =
                schemeRegularityRepository.getPeriodicWaterQuantityByLgdId(lgdId, startDate, endDate, scale);

        PeriodicWaterQuantityResponse response =
                buildPeriodicWaterQuantityResponse(lgdId, null, startDate, endDate, scale, metrics);
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public PeriodicWaterQuantityResponse getPeriodicWaterQuantityByDepartment(
            Integer departmentId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        validateDepartmentInput(departmentId);
        validateDateRange(startDate, endDate);
        validateScaleInput(scale);

        List<SchemeRegularityRepository.PeriodicWaterQuantityMetrics> metrics =
                schemeRegularityRepository.getPeriodicWaterQuantityByDepartment(departmentId, startDate, endDate, scale);

        return buildPeriodicWaterQuantityResponse(null, departmentId, startDate, endDate, scale, metrics);
    }

    @Override
    public PeriodicSchemeRegularityResponse getPeriodicSchemeRegularityByLgdId(
            Integer lgdId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        validateLgdInput(lgdId);
        validateDateRange(startDate, endDate);
        validateScaleInput(scale);

        String cacheKey = PERIODIC_SCHEME_REGULARITY_CACHE_PREFIX
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
                schemeRegularityRepository.getPeriodicSchemeRegularityByLgdId(lgdId, startDate, endDate, scale);

        PeriodicSchemeRegularityResponse response =
                buildPeriodicSchemeRegularityResponse(lgdId, null, startDate, endDate, scale, metrics);
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public PeriodicSchemeRegularityResponse getPeriodicSchemeRegularityByDepartment(
            Integer departmentId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        validateDepartmentInput(departmentId);
        validateDateRange(startDate, endDate);
        validateScaleInput(scale);

        List<SchemeRegularityRepository.PeriodicSchemeRegularityMetrics> metrics =
                schemeRegularityRepository.getPeriodicSchemeRegularityByDepartment(departmentId, startDate, endDate, scale);

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
                + ":v1";
    }

    @Override
    public PeriodicOutageReasonSchemeCountResponse getPeriodicOutageReasonSchemeCountByLgdId(
            Integer lgdId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        validateLgdInput(lgdId);
        validateDateRange(startDate, endDate);
        validateScaleInput(scale);

        String cacheKey = PERIODIC_OUTAGE_REASON_SCHEME_COUNT_CACHE_PREFIX
                + ":lgd:" + lgdId
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
                schemeRegularityRepository.getPeriodicOutageReasonSchemeCountByLgdId(
                        lgdId, startDate, endDate, scale);

        PeriodicOutageReasonSchemeCountResponse response =
                buildPeriodicOutageReasonSchemeCountResponse(lgdId, null, startDate, endDate, scale, rows);
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public PeriodicOutageReasonSchemeCountResponse getPeriodicOutageReasonSchemeCountByDepartment(
            Integer departmentId, LocalDate startDate, LocalDate endDate, PeriodScale scale) {
        validateDepartmentInput(departmentId);
        validateDateRange(startDate, endDate);
        validateScaleInput(scale);

        List<SchemeRegularityRepository.PeriodicOutageReasonSchemeCountRow> rows =
                schemeRegularityRepository.getPeriodicOutageReasonSchemeCountByDepartment(
                        departmentId, startDate, endDate, scale);

        return buildPeriodicOutageReasonSchemeCountResponse(null, departmentId, startDate, endDate, scale, rows);
    }

    @Override
    public OutageReasonSchemeCountResponse getOutageReasonSchemeCountByLgd(
            Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);

        String cacheKey = OUTAGE_REASON_SCHEME_COUNT_CACHE_PREFIX
                + ":parent_lgd:" + parentLgdId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v1";
        OutageReasonSchemeCountResponse cached = readFromCache(cacheKey, OutageReasonSchemeCountResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentLgdLevel = schemeRegularityRepository.getLgdLevel(parentLgdId);
        if (parentLgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }

        List<SchemeRegularityRepository.OutageReasonSchemeCount> rows =
                schemeRegularityRepository.getOutageReasonSchemeCountByLgd(parentLgdId, startDate, endDate);
        List<SchemeRegularityRepository.ChildRegionRef> childRegions =
                schemeRegularityRepository.getChildRegionsByLgd(parentLgdId);
        List<SchemeRegularityRepository.ChildRegionOutageReasonSchemeCount> childRows =
                schemeRegularityRepository.getChildOutageReasonSchemeCountByLgd(parentLgdId, startDate, endDate);

        OutageReasonSchemeCountResponse response = OutageReasonSchemeCountResponse.builder()
                .lgdId(parentLgdId)
                .departmentId(null)
                .startDate(startDate)
                .endDate(endDate)
                .parentLgdLevel(parentLgdLevel)
                .parentDepartmentLevel(null)
                .outageReasonSchemeCount(buildReasonCountMap(rows))
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
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);
        Integer parentDepartmentLevel = schemeRegularityRepository.getDepartmentLevel(parentDepartmentId);
        if (parentDepartmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }

        List<SchemeRegularityRepository.OutageReasonSchemeCount> rows =
                schemeRegularityRepository.getOutageReasonSchemeCountByDepartment(
                        parentDepartmentId, startDate, endDate);
        List<SchemeRegularityRepository.ChildRegionRef> childRegions =
                schemeRegularityRepository.getChildRegionsByDepartment(parentDepartmentId);
        List<SchemeRegularityRepository.ChildRegionOutageReasonSchemeCount> childRows =
                schemeRegularityRepository.getChildOutageReasonSchemeCountByDepartment(
                        parentDepartmentId, startDate, endDate);

        return OutageReasonSchemeCountResponse.builder()
                .lgdId(null)
                .departmentId(parentDepartmentId)
                .startDate(startDate)
                .endDate(endDate)
                .parentLgdLevel(null)
                .parentDepartmentLevel(parentDepartmentLevel)
                .outageReasonSchemeCount(buildReasonCountMap(rows))
                .childRegionCount(childRegions.size())
                .childRegions(buildChildOutageRegions(
                        childRegions,
                        childRows,
                        SchemeRegularityRepository.ChildRegionOutageReasonSchemeCount::departmentId))
                .build();
    }

    @Override
    public UserOutageReasonSchemeCountResponse getOutageReasonSchemeCountByUser(
            Integer userId, LocalDate startDate, LocalDate endDate) {
        validateUserInput(userId);
        validateDateRange(startDate, endDate);

        List<SchemeRegularityRepository.OutageReasonSchemeCount> rows =
                schemeRegularityRepository.getOutageReasonSchemeCountByUser(userId, startDate, endDate);
        List<SchemeRegularityRepository.DailyOutageReasonSchemeCount> dailyRows =
                schemeRegularityRepository.getDailyOutageReasonSchemeCountByUser(userId, startDate, endDate);
        Integer schemeCount = schemeRegularityRepository.getSchemeCountByUser(userId);

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
            UUID userUuid, LocalDate startDate, LocalDate endDate) {
        return getOutageReasonSchemeCountByUser(resolveUserIdByUuid(userUuid), startDate, endDate);
    }

    @Override
    public NonSubmissionReasonSchemeCountResponse getNonSubmissionReasonSchemeCountByLgd(
            Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);

        String cacheKey = NON_SUBMISSION_REASON_SCHEME_COUNT_CACHE_PREFIX
                + ":parent_lgd:" + parentLgdId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v1";
        NonSubmissionReasonSchemeCountResponse cached =
                readFromCache(cacheKey, NonSubmissionReasonSchemeCountResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer parentLgdLevel = schemeRegularityRepository.getLgdLevel(parentLgdId);
        if (parentLgdLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }

        List<SchemeRegularityRepository.NonSubmissionReasonSchemeCount> rows =
                schemeRegularityRepository.getNonSubmissionReasonSchemeCountByLgd(
                        parentLgdId, startDate, endDate);
        List<SchemeRegularityRepository.ChildRegionRef> childRegions =
                schemeRegularityRepository.getChildRegionsByLgd(parentLgdId);
        List<SchemeRegularityRepository.ChildRegionNonSubmissionReasonSchemeCount> childRows =
                schemeRegularityRepository.getChildNonSubmissionReasonSchemeCountByLgd(
                        parentLgdId, startDate, endDate);

        NonSubmissionReasonSchemeCountResponse response = NonSubmissionReasonSchemeCountResponse.builder()
                .lgdId(parentLgdId)
                .departmentId(null)
                .startDate(startDate)
                .endDate(endDate)
                .parentLgdLevel(parentLgdLevel)
                .parentDepartmentLevel(null)
                .nonSubmissionReasonSchemeCount(buildNonSubmissionReasonCountMap(rows))
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
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);
        Integer parentDepartmentLevel = schemeRegularityRepository.getDepartmentLevel(parentDepartmentId);
        if (parentDepartmentLevel == null) {
            throw new IllegalArgumentException(
                    "parent_department_id not found in dim_department_location_table: " + parentDepartmentId);
        }

        List<SchemeRegularityRepository.NonSubmissionReasonSchemeCount> rows =
                schemeRegularityRepository.getNonSubmissionReasonSchemeCountByDepartment(
                        parentDepartmentId, startDate, endDate);
        List<SchemeRegularityRepository.ChildRegionRef> childRegions =
                schemeRegularityRepository.getChildRegionsByDepartment(parentDepartmentId);
        List<SchemeRegularityRepository.ChildRegionNonSubmissionReasonSchemeCount> childRows =
                schemeRegularityRepository.getChildNonSubmissionReasonSchemeCountByDepartment(
                        parentDepartmentId, startDate, endDate);

        return NonSubmissionReasonSchemeCountResponse.builder()
                .lgdId(null)
                .departmentId(parentDepartmentId)
                .startDate(startDate)
                .endDate(endDate)
                .parentLgdLevel(null)
                .parentDepartmentLevel(parentDepartmentLevel)
                .nonSubmissionReasonSchemeCount(buildNonSubmissionReasonCountMap(rows))
                .childRegionCount(childRegions.size())
                .childRegions(buildChildNonSubmissionRegions(
                        childRegions,
                        childRows,
                        SchemeRegularityRepository.ChildRegionNonSubmissionReasonSchemeCount::departmentId))
                .build();
    }

    @Override
    public UserNonSubmissionReasonSchemeCountResponse getNonSubmissionReasonSchemeCountByUser(
            Integer userId, LocalDate startDate, LocalDate endDate) {
        validateUserInput(userId);
        validateDateRange(startDate, endDate);

        List<SchemeRegularityRepository.NonSubmissionReasonSchemeCount> rows =
                schemeRegularityRepository.getNonSubmissionReasonSchemeCountByUser(userId, startDate, endDate);
        List<SchemeRegularityRepository.DailyNonSubmissionReasonSchemeCount> dailyRows =
                schemeRegularityRepository.getDailyNonSubmissionReasonSchemeCountByUser(userId, startDate, endDate);
        Integer schemeCount = schemeRegularityRepository.getSchemeCountByUser(userId);

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
            UUID userUuid, LocalDate startDate, LocalDate endDate) {
        return getNonSubmissionReasonSchemeCountByUser(resolveUserIdByUuid(userUuid), startDate, endDate);
    }

    @Override
    public UserSubmissionStatusResponse getSubmissionStatusByUser(
            Integer userId, LocalDate startDate, LocalDate endDate) {
        validateUserInput(userId);
        validateDateRange(startDate, endDate);

        Integer schemeCount = schemeRegularityRepository.getSchemeCountByUser(userId);
        SchemeRegularityRepository.SubmissionStatusCount submissionStatusCount =
                schemeRegularityRepository.getSubmissionStatusCountByUser(userId, startDate, endDate);
        List<SchemeRegularityRepository.DailySubmissionSchemeCount> dailyRows =
                schemeRegularityRepository.getDailySubmissionSchemeCountByUser(userId, startDate, endDate);

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
            UUID userUuid, LocalDate startDate, LocalDate endDate) {
        return getSubmissionStatusByUser(resolveUserIdByUuid(userUuid), startDate, endDate);
    }

    @Override
    public SubmissionStatusSummaryResponse getSubmissionStatusSummaryByLgd(
            Integer lgdId, LocalDate startDate, LocalDate endDate) {
        validateLgdInput(lgdId);
        validateDateRange(startDate, endDate);

        String cacheKey = SUBMISSION_STATUS_SUMMARY_CACHE_PREFIX
                + ":lgd:" + lgdId
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v1";
        SubmissionStatusSummaryResponse cached =
                readFromCache(cacheKey, SubmissionStatusSummaryResponse.class);
        if (cached != null) {
            return cached;
        }

        Integer schemeCount = schemeRegularityRepository.getSchemeCountByLgd(lgdId);
        SchemeRegularityRepository.SubmissionStatusCount submissionStatusCount =
                schemeRegularityRepository.getSubmissionStatusCountByLgd(lgdId, startDate, endDate);

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
            Integer departmentId, LocalDate startDate, LocalDate endDate) {
        validateDepartmentInput(departmentId);
        validateDateRange(startDate, endDate);

        Integer schemeCount = schemeRegularityRepository.getSchemeCountByDepartment(departmentId);
        SchemeRegularityRepository.SubmissionStatusCount submissionStatusCount =
                schemeRegularityRepository.getSubmissionStatusCountByDepartment(departmentId, startDate, endDate);

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
    public Map<String, Integer> getSchemeStatusCountByLgd(Integer lgdId) {
        validateLgdInput(lgdId);

        String cacheKey = SCHEME_STATUS_COUNT_CACHE_PREFIX
                + ":lgd:" + lgdId
                + ":v1";
        @SuppressWarnings("unchecked")
        Map<String, Integer> cached = (Map<String, Integer>) (Map<?, ?>) readFromCache(cacheKey, Map.class);
        if (cached != null) {
            return cached;
        }

        SchemeRegularityRepository.SchemeStatusCount count =
                schemeRegularityRepository.getSchemeStatusCountByLgd(lgdId);
        Map<String, Integer> response = Map.of(
                SchemeStatus.ACTIVE.name().toLowerCase() + "_schemes_count",
                count.activeSchemeCount() == null ? 0 : count.activeSchemeCount(),
                SchemeStatus.INACTIVE.name().toLowerCase() + "_schemes_count",
                count.inactiveSchemeCount() == null ? 0 : count.inactiveSchemeCount());
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public Map<String, Integer> getSchemeStatusCountByDepartment(Integer departmentId) {
        validateDepartmentInput(departmentId);
        SchemeRegularityRepository.SchemeStatusCount count =
                schemeRegularityRepository.getSchemeStatusCountByDepartment(departmentId);
        return Map.of(
                SchemeStatus.ACTIVE.name().toLowerCase() + "_schemes_count",
                count.activeSchemeCount() == null ? 0 : count.activeSchemeCount(),
                SchemeStatus.INACTIVE.name().toLowerCase() + "_schemes_count",
                count.inactiveSchemeCount() == null ? 0 : count.inactiveSchemeCount());
    }

    @Override
    public SchemeStatusAndTopReportingResponse getSchemeStatusAndTopReportingByLgd(
            Integer parentLgdId, LocalDate startDate, LocalDate endDate, Integer topSchemeCount) {
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);
        topSchemeCount = topSchemeCount == null ? DEFAULT_TOP_SCHEME_COUNT : topSchemeCount;
        validateTopSchemeCount(topSchemeCount);

        String cacheKey = SCHEME_STATUS_TOP_REPORTING_CACHE_PREFIX
                + ":parent_lgd:" + parentLgdId
                + ":scheme_count:" + topSchemeCount
                + ":start:" + startDate
                + ":end:" + endDate
                + ":v1";
        SchemeStatusAndTopReportingResponse cached =
                readFromCache(cacheKey, SchemeStatusAndTopReportingResponse.class);
        if (cached != null) {
            return cached;
        }
        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        Integer parentLgdLevel = schemeRegularityRepository.getLgdLevel(parentLgdId);

        SchemeRegularityRepository.SchemeStatusCount statusCount =
                schemeRegularityRepository.getSchemeStatusCountByLgd(parentLgdId);
        String parentLgdCName = schemeRegularityRepository.getParentLgdCNameByLgd(parentLgdId);
        String parentLgdTitle = schemeRegularityRepository.getParentLgdTitleByLgd(parentLgdId);
        List<SchemeRegularityRepository.SchemeSubmissionMetrics> topSchemes =
                schemeRegularityRepository.getTopSchemeSubmissionMetricsByLgd(
                        parentLgdId, startDate, endDate, topSchemeCount);

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
                .topSchemeCount(topSchemes.size())
                .topSchemes(topSchemes.stream()
                        .map(metric -> SchemeStatusAndTopReportingResponse.TopReportingScheme.builder()
                                .schemeId(metric.schemeId())
                                .schemeName(metric.schemeName())
                                .statusCode(metric.status())
                                .status(resolveSchemeStatus(metric.status()))
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
                                .build())
                        .toList())
                .build();
        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public SchemeStatusAndTopReportingResponse getSchemeStatusAndTopReportingByDepartment(
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate, Integer topSchemeCount) {
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);
        topSchemeCount = topSchemeCount == null ? DEFAULT_TOP_SCHEME_COUNT : topSchemeCount;
        validateTopSchemeCount(topSchemeCount);
        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        Integer parentDepartmentLevel = schemeRegularityRepository.getDepartmentLevel(parentDepartmentId);

        SchemeRegularityRepository.SchemeStatusCount statusCount =
                schemeRegularityRepository.getSchemeStatusCountByDepartment(parentDepartmentId);
        String parentDepartmentCName =
                schemeRegularityRepository.getParentDepartmentCNameByDepartment(parentDepartmentId);
        String parentDepartmentTitle =
                schemeRegularityRepository.getParentDepartmentTitleByDepartment(parentDepartmentId);
        List<SchemeRegularityRepository.SchemeSubmissionMetrics> topSchemes =
                schemeRegularityRepository.getTopSchemeSubmissionMetricsByDepartment(
                        parentDepartmentId, startDate, endDate, topSchemeCount);

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
                .topSchemeCount(topSchemes.size())
                .topSchemes(topSchemes.stream()
                        .map(metric -> SchemeStatusAndTopReportingResponse.TopReportingScheme.builder()
                                .schemeId(metric.schemeId())
                                .schemeName(metric.schemeName())
                                .statusCode(metric.status())
                                .status(resolveSchemeStatus(metric.status()))
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
                                .build())
                        .toList())
                .build();
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

    @Override
    public SchemeRegularityListResponse getSchemeRegionReportByLgd(
            Integer parentLgdId, LocalDate startDate, LocalDate endDate, Integer pageNumber, Integer count) {
        validateLgdInput(parentLgdId);
        validateDateRange(startDate, endDate);
        validatePaginationInput(pageNumber, count);

        String cacheKey = SCHEME_REGION_REPORT_CACHE_PREFIX
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
                schemeRegularityRepository.getSchemeRegionReportByLgd(parentLgdId, startDate, endDate);
        String parentLgdCName = schemeRegularityRepository.getParentLgdCNameByLgd(parentLgdId);
        String parentLgdTitle = schemeRegularityRepository.getParentLgdTitleByLgd(parentLgdId);

        int activeCount = (int) schemes.stream()
                .filter(s -> s.status() != null && s.status() == SchemeStatus.ACTIVE.getCode())
                .count();
        int inactiveCount = (int) schemes.stream()
                .filter(s -> s.status() != null && s.status() == SchemeStatus.INACTIVE.getCode())
                .count();

        List<SchemeRegularityListResponse.SchemeMetrics> allSchemeMetrics = schemes.stream()
                .map(metric -> SchemeRegularityListResponse.SchemeMetrics.builder()
                        .schemeId(metric.schemeId())
                        .schemeName(metric.schemeName())
                        .statusCode(metric.status())
                        .status(resolveSchemeStatus(metric.status()))
                        .supplyDays(metric.supplyDays())
                        .averageRegularity(calculateReportingRate(metric.supplyDays(), daysInRange))
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
            Integer parentDepartmentId, LocalDate startDate, LocalDate endDate, Integer pageNumber, Integer count) {
        validateDepartmentInput(parentDepartmentId);
        validateDateRange(startDate, endDate);
        validatePaginationInput(pageNumber, count);
        int daysInRange = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;

        List<SchemeRegularityRepository.SchemeRegularityListMetrics> schemes =
                schemeRegularityRepository.getSchemeRegionReportByDepartment(parentDepartmentId, startDate, endDate);
        String parentDepartmentCName =
                schemeRegularityRepository.getParentDepartmentCNameByDepartment(parentDepartmentId);
        String parentDepartmentTitle =
                schemeRegularityRepository.getParentDepartmentTitleByDepartment(parentDepartmentId);

        int activeCount = (int) schemes.stream()
                .filter(s -> s.status() != null && s.status() == SchemeStatus.ACTIVE.getCode())
                .count();
        int inactiveCount = (int) schemes.stream()
                .filter(s -> s.status() != null && s.status() == SchemeStatus.INACTIVE.getCode())
                .count();

        List<SchemeRegularityListResponse.SchemeMetrics> allSchemeMetrics = schemes.stream()
                .map(metric -> SchemeRegularityListResponse.SchemeMetrics.builder()
                        .schemeId(metric.schemeId())
                        .schemeName(metric.schemeName())
                        .statusCode(metric.status())
                        .status(resolveSchemeStatus(metric.status()))
                        .supplyDays(metric.supplyDays())
                        .averageRegularity(calculateReportingRate(metric.supplyDays(), daysInRange))
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
        for (SchemeStatus value : SchemeStatus.values()) {
            if (value.getCode() == statusCode) {
                return value.name().toLowerCase();
            }
        }
        return "unknown";
    }

    private void validateScaleInput(PeriodScale scale) {
        if (scale == null) {
            throw new IllegalArgumentException("scale is required and must be one of: day, week, month");
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
                            LocalDate cappedPeriodStart =
                                    metric.periodStartDate().isBefore(startDate) ? startDate : metric.periodStartDate();
                            LocalDate cappedPeriodEnd =
                                    metric.periodEndDate().isAfter(endDate) ? endDate : metric.periodEndDate();

                            long periodDays =
                                    ChronoUnit.DAYS.between(cappedPeriodStart, cappedPeriodEnd) + 1;

                            BigDecimal averageRegularity = BigDecimal.ZERO;
                            if (metric.schemeCount() > 0 && periodDays > 0) {
                                BigDecimal denominator =
                                        BigDecimal.valueOf((long) metric.schemeCount() * periodDays);
                                averageRegularity = BigDecimal.valueOf(metric.totalSupplyDays())
                                        .divide(denominator, 4, RoundingMode.HALF_UP);
                            }

                            return PeriodicSchemeRegularityResponse.PeriodicSchemeRegularityPeriodMetric.builder()
                                    .periodStartDate(cappedPeriodStart)
                                    .periodEndDate(cappedPeriodEnd)
                                    .totalSupplyDays(metric.totalSupplyDays())
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
        List<PeriodicNationalSchemeRegularityResponse.PeriodicNationalSchemeRegularityPeriodMetric> periodicMetrics =
                metrics.stream()
                        .map(metric -> {
                            LocalDate cappedPeriodStart =
                                    metric.periodStartDate().isBefore(startDate) ? startDate : metric.periodStartDate();
                            LocalDate cappedPeriodEnd =
                                    metric.periodEndDate().isAfter(endDate) ? endDate : metric.periodEndDate();

                            long periodDays =
                                    ChronoUnit.DAYS.between(cappedPeriodStart, cappedPeriodEnd) + 1;

                            BigDecimal averageRegularity = BigDecimal.ZERO;
                            if (metric.schemeCount() > 0 && periodDays > 0) {
                                BigDecimal denominator =
                                        BigDecimal.valueOf((long) metric.schemeCount() * periodDays);
                                averageRegularity = BigDecimal.valueOf(metric.totalSupplyDays())
                                        .divide(denominator, 4, RoundingMode.HALF_UP);
                            }

                            return PeriodicNationalSchemeRegularityResponse.PeriodicNationalSchemeRegularityPeriodMetric
                                    .builder()
                                    .periodStartDate(cappedPeriodStart)
                                    .periodEndDate(cappedPeriodEnd)
                                    .totalSupplyDays(metric.totalSupplyDays())
                                    .totalWaterQuantity(metric.totalWaterQuantity())
                                    .averageRegularity(averageRegularity)
                                    .build();
                        })
                        .toList();

        return PeriodicNationalSchemeRegularityResponse.builder()
                .schemeCount(schemeCount)
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

    private void writeToCache(String cacheKey, Object response) {
        try {
            String payload = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(cacheKey, payload, SCHEME_REGULARITY_CACHE_TTL);
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
