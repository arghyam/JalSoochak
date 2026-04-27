package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import org.arghyam.jalsoochak.analytics.dto.response.ChildRegionDetails;
import org.arghyam.jalsoochak.analytics.dto.response.TenantDetailsResponse;
import org.arghyam.jalsoochak.analytics.dto.response.AverageSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ReadingSubmissionRateResponse;
import org.arghyam.jalsoochak.analytics.dto.response.TenantPerformanceChildRegionDetails;
import org.arghyam.jalsoochak.analytics.dto.response.TenantPerformanceScoreResponse;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.repository.TenantBoundaryRepository;
import org.arghyam.jalsoochak.analytics.repository.TenantDepartmentBoundaryRepository;
import org.arghyam.jalsoochak.analytics.repository.SchemeRegularityRepository;
import org.arghyam.jalsoochak.analytics.service.TenantDetailsService;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDate;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class TenantDetailsServiceImpl implements TenantDetailsService {

    private static final Duration TENANT_DETAILS_CACHE_TTL = Duration.ofHours(24);
    private static final String TENANT_DETAILS_CACHE_PREFIX = "analytics-service:api-cache:get_tenant_details";
    private static final String TENANT_PERFORMANCE_CACHE_PREFIX = "analytics-service:api-cache:get_tenant_performance_score";
    private static final String DEBUG_LOG_PATH = "/home/beehyv/Desktop/Codes/jalSoochak/JalSoochak_New/.cursor/debug.log";

    private final DimTenantRepository dimTenantRepository;
    private final TenantBoundaryRepository tenantBoundaryRepository;
    private final TenantDepartmentBoundaryRepository tenantDepartmentBoundaryRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final SchemeRegularityService schemeRegularityService;

    @Override
    public TenantDetailsResponse getTenantDetails(Integer tenantId, Integer parentLgdId) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenant_id must be a positive integer");
        }

        String parentSegment = parentLgdId == null ? "all" : String.valueOf(parentLgdId);
        String cacheKey = TENANT_DETAILS_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":parent:" + parentSegment
                + ":v4";
        TenantDetailsResponse cached = readFromCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        DimTenant tenant = dimTenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found for tenant_id: " + tenantId));

        // #region agent log
        appendDebugLog(
                "H4",
                "TenantDetailsServiceImpl:getTenantDetails:tenant_resolved",
                "Resolved tenant for tenant_data request",
                Map.of(
                        "tenantId", tenantId,
                        "stateCode", String.valueOf(tenant.getStateCode()),
                        "parentLgdId", parentLgdId == null ? "null" : parentLgdId));
        // #endregion

        TenantDetailsResponse response;
        if (parentLgdId != null) {
            response = getTenantDetailsByParent(tenant, parentLgdId);
        } else {
            Map<String, Object> boundaryResult = tenantBoundaryRepository.getMergedBoundaryForTenant(tenantId);
            Integer boundaryCount = intFromQueryMap(boundaryResult, "boundary_count");
            String boundaryGeoJson = (String) boundaryResult.get("boundary_geojson");

            response = TenantDetailsResponse.builder()
                    .tenantId(tenant.getTenantId())
                    .stateCode(tenant.getStateCode())
                    .parentLgdLevel(null)
                    .parentDepartmentLevel(null)
                    .childBoundaryCount(boundaryCount)
                    .boundaryGeoJson(boundaryGeoJson)
                    .childRegions(List.of())
                    .build();
        }

        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public TenantDetailsResponse getTenantDetailsByParentDepartment(Integer tenantId, Integer parentDepartmentId) {
        if (tenantId == null || tenantId <= 0) {
            throw new IllegalArgumentException("tenant_id must be a positive integer");
        }
        if (parentDepartmentId == null || parentDepartmentId <= 0) {
            throw new IllegalArgumentException("parent_department_id must be a positive integer");
        }

        String cacheKey = TENANT_DETAILS_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":parent_department:" + parentDepartmentId
                + ":v4";
        TenantDetailsResponse cached = readFromCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        DimTenant tenant = dimTenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found for tenant_id: " + tenantId));

        Integer parentLevel = tenantDepartmentBoundaryRepository.getDepartmentLevel(tenantId, parentDepartmentId);
        if (parentLevel == null) {
            throw new IllegalArgumentException("parent_department_id not found for tenant: " + parentDepartmentId);
        }
        if (parentLevel >= 6) {
            throw new IllegalArgumentException("No child department level available for parent_department_id: " + parentDepartmentId);
        }

        List<Map<String, Object>> childRows = tenantDepartmentBoundaryRepository
                .getChildDepartmentsByParent(tenantId, parentDepartmentId, parentLevel);
        List<ChildRegionDetails> childRegions = childRows.stream()
                .map(row -> ChildRegionDetails.builder()
                        .departmentId((Integer) row.get("department_id"))
                        .parentLgdId(null)
                        .parentDepartmentId((Integer) row.get("parent_department_id"))
                        .lgdLevel((Integer) row.get("child_level"))
                        .schemeCount(row.get("scheme_count") instanceof Number number ? number.intValue() : 0)
                        .title((String) row.get("title"))
                        .lgdCode((String) row.get("lgd_code"))
                        .boundaryGeoJson((String) row.get("boundary_geojson"))
                        .build())
                .toList();

        Map<String, Object> mergedBoundaryResult = tenantDepartmentBoundaryRepository
                .getMergedBoundaryByParentDepartment(tenantId, parentDepartmentId, parentLevel);

        String parentBoundaryGeoJson =
                tenantDepartmentBoundaryRepository.getBoundaryGeoJsonByDepartmentId(tenantId, parentDepartmentId);
        String boundaryGeoJson = (parentBoundaryGeoJson != null && !parentBoundaryGeoJson.isBlank())
                ? parentBoundaryGeoJson
                : (String) mergedBoundaryResult.get("boundary_geojson");

        TenantDetailsResponse response = TenantDetailsResponse.builder()
                .tenantId(tenant.getTenantId())
                .stateCode(tenant.getStateCode())
                .parentLgdLevel(null)
                .parentDepartmentLevel(parentLevel)
                .childBoundaryCount(intFromQueryMap(mergedBoundaryResult, "child_count"))
                .boundaryGeoJson(boundaryGeoJson)
                .childRegions(childRegions)
                .build();

        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public TenantDetailsResponse getTenantDetailsWithAggregatedMetrics(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        String cacheKey = TENANT_DETAILS_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":parent:" + (parentLgdId == null ? "all" : parentLgdId)
                + ":from:" + startDate
                + ":to:" + endDate
                + ":v4";

        TenantDetailsResponse cached = readFromCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        // Base boundary + child list
        TenantDetailsResponse response = getTenantDetails(tenantId, parentLgdId);

        // Scheme regularity and reading submission are scope/period based
        AverageSchemeRegularityResponse averageRegularity =
                schemeRegularityService.getAverageSchemeRegularity(tenantId, parentLgdId, startDate, endDate);
        ReadingSubmissionRateResponse submissionRate =
                schemeRegularityService.getReadingSubmissionRateByLgd(tenantId, parentLgdId, startDate, endDate);

        response.setAverageSchemeRegularity(averageRegularity.getAverageRegularity());
        response.setReadingSubmissionRate(submissionRate.getReadingSubmissionRate());

        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public TenantDetailsResponse getTenantDetailsByParentDepartmentWithAggregatedMetrics(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        String cacheKey = TENANT_DETAILS_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":parent_department:" + parentDepartmentId
                + ":from:" + startDate
                + ":to:" + endDate
                + ":v4";

        TenantDetailsResponse cached = readFromCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        TenantDetailsResponse response = getTenantDetailsByParentDepartment(tenantId, parentDepartmentId);

        AverageSchemeRegularityResponse averageRegularity =
                schemeRegularityService.getAverageSchemeRegularityByDepartment(
                        tenantId, parentDepartmentId, startDate, endDate);
        ReadingSubmissionRateResponse submissionRate =
                schemeRegularityService.getReadingSubmissionRateByDepartment(
                        tenantId, parentDepartmentId, startDate, endDate);

        response.setAverageSchemeRegularity(averageRegularity.getAverageRegularity());
        response.setReadingSubmissionRate(submissionRate.getReadingSubmissionRate());

        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public TenantPerformanceScoreResponse getTenantPerformanceScoreByParentLgd(
            Integer tenantId, Integer parentLgdId, LocalDate startDate, LocalDate endDate) {
        String cacheKey = TENANT_PERFORMANCE_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":parent:" + (parentLgdId == null ? "all" : parentLgdId)
                + ":from:" + startDate
                + ":to:" + endDate
                + ":v1";

        TenantPerformanceScoreResponse cached = readFromCache(cacheKey, TenantPerformanceScoreResponse.class);
        if (cached != null) {
            return cached;
        }

        // Reuse validated tenant resolution + child region selection logic from the boundary API.
        TenantDetailsResponse base = getTenantDetails(tenantId, parentLgdId);

        List<SchemeRegularityRepository.ChildRegionPerformanceScore> childPerformance =
                schemeRegularityService.getChildAveragePerformanceScoreByLgd(parentLgdId, startDate, endDate);
        Map<Integer, BigDecimal> childPerformanceByLgdId = childPerformance.stream()
                .filter(row -> row.lgdId() != null)
                .collect(Collectors.toMap(
                        SchemeRegularityRepository.ChildRegionPerformanceScore::lgdId,
                        SchemeRegularityRepository.ChildRegionPerformanceScore::averagePerformanceScore,
                        (a, b) -> b));

        List<TenantPerformanceChildRegionDetails> shapedChildren = base.getChildRegions() == null
                ? List.of()
                : base.getChildRegions().stream()
                .map(child -> TenantPerformanceChildRegionDetails.builder()
                        .lgdId(child.getLgdId())
                        .departmentId(null)
                        .parentLgdId(child.getParentLgdId())
                        .parentDepartmentId(null)
                        .lgdLevel(child.getLgdLevel())
                        .lgdCode(child.getLgdCode())
                        .averagePerformanceScore(scale3(
                                childPerformanceByLgdId.getOrDefault(child.getLgdId(), BigDecimal.ZERO)))
                        .build())
                .toList();

        TenantPerformanceScoreResponse response = TenantPerformanceScoreResponse.builder()
                .tenantId(base.getTenantId())
                .stateCode(base.getStateCode())
                .parentLgdLevel(base.getParentLgdLevel())
                .parentDepartmentLevel(null)
                .averagePerformanceScore(scale3(
                        schemeRegularityService.getAveragePerformanceScoreByLgd(parentLgdId, startDate, endDate)))
                .childRegions(shapedChildren)
                .build();

        writeToCache(cacheKey, response);
        return response;
    }

    @Override
    public TenantPerformanceScoreResponse getTenantPerformanceScoreByParentDepartment(
            Integer tenantId, Integer parentDepartmentId, LocalDate startDate, LocalDate endDate) {
        String cacheKey = TENANT_PERFORMANCE_CACHE_PREFIX
                + ":tenant:" + tenantId
                + ":parent_department:" + parentDepartmentId
                + ":from:" + startDate
                + ":to:" + endDate
                + ":v1";

        TenantPerformanceScoreResponse cached = readFromCache(cacheKey, TenantPerformanceScoreResponse.class);
        if (cached != null) {
            return cached;
        }

        TenantDetailsResponse base = getTenantDetailsByParentDepartment(tenantId, parentDepartmentId);

        List<SchemeRegularityRepository.ChildRegionPerformanceScore> childPerformance =
                schemeRegularityService.getChildAveragePerformanceScoreByDepartment(parentDepartmentId, startDate, endDate);
        Map<Integer, BigDecimal> childPerformanceByDepartmentId = childPerformance.stream()
                .filter(row -> row.departmentId() != null)
                .collect(Collectors.toMap(
                        SchemeRegularityRepository.ChildRegionPerformanceScore::departmentId,
                        SchemeRegularityRepository.ChildRegionPerformanceScore::averagePerformanceScore,
                        (a, b) -> b));

        List<TenantPerformanceChildRegionDetails> shapedChildren = base.getChildRegions() == null
                ? List.of()
                : base.getChildRegions().stream()
                .map(child -> TenantPerformanceChildRegionDetails.builder()
                        .lgdId(null)
                        .departmentId(child.getDepartmentId())
                        .parentLgdId(null)
                        .parentDepartmentId(child.getParentDepartmentId())
                        .lgdLevel(child.getLgdLevel())
                        .lgdCode(child.getLgdCode())
                        .averagePerformanceScore(scale3(
                                childPerformanceByDepartmentId.getOrDefault(child.getDepartmentId(), BigDecimal.ZERO)))
                        .build())
                .toList();

        TenantPerformanceScoreResponse response = TenantPerformanceScoreResponse.builder()
                .tenantId(base.getTenantId())
                .stateCode(base.getStateCode())
                .parentLgdLevel(null)
                .parentDepartmentLevel(base.getParentDepartmentLevel())
                .averagePerformanceScore(scale3(
                        schemeRegularityService.getAveragePerformanceScoreByDepartment(parentDepartmentId, startDate, endDate)))
                .childRegions(shapedChildren)
                .build();

        writeToCache(cacheKey, response);
        return response;
    }

    private TenantDetailsResponse getTenantDetailsByParent(
            DimTenant tenant,
            Integer parentLgdId
    ) {
        if (parentLgdId <= 0) {
            throw new IllegalArgumentException("parent_lgd_id must be a positive integer");
        }

        Integer parentLevel = tenantBoundaryRepository.getLocationLevel(parentLgdId);
        if (parentLevel == null) {
            throw new IllegalArgumentException("parent_lgd_id not found in dim_lgd_location_table: " + parentLgdId);
        }

        List<Map<String, Object>> childRows =
                tenantBoundaryRepository.getChildLevelByParent(tenant.getTenantId(), parentLgdId, parentLevel);
        List<ChildRegionDetails> childRegions = childRows.stream()
                .map(row -> ChildRegionDetails.builder()
                        .lgdId((Integer) row.get("lgd_id"))
                        .parentLgdId((Integer) row.get("parent_lgd_id"))
                        .parentDepartmentId(null)
                        .lgdLevel((Integer) row.get("child_level"))
                        .schemeCount(row.get("scheme_count") instanceof Number number ? number.intValue() : 0)
                        .title((String) row.get("title"))
                        .lgdCode((String) row.get("lgd_code"))
                        .boundaryGeoJson((String) row.get("boundary_geojson"))
                        .build())
                .toList();

        Map<String, Object> mergedBoundaryResult =
                tenantBoundaryRepository.getMergedBoundaryByParent(tenant.getTenantId(), parentLgdId, parentLevel);

        String parentBoundaryGeoJson =
                tenantBoundaryRepository.getBoundaryGeoJsonByLgdId(tenant.getTenantId(), parentLgdId);
        String boundaryGeoJson = (parentBoundaryGeoJson != null && !parentBoundaryGeoJson.isBlank())
                ? parentBoundaryGeoJson
                : (String) mergedBoundaryResult.get("boundary_geojson");

        return TenantDetailsResponse.builder()
                .tenantId(tenant.getTenantId())
                .stateCode(tenant.getStateCode())
                .parentLgdLevel(parentLevel)
                .parentDepartmentLevel(null)
                .childBoundaryCount(intFromQueryMap(mergedBoundaryResult, "child_count"))
                .boundaryGeoJson(boundaryGeoJson)
                .childRegions(childRegions)
                .build();
    }

    private static Integer intFromQueryMap(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("Expected numeric column " + key + " in query result");
    }

    private TenantDetailsResponse readFromCache(String cacheKey) {
        try {
            String payload = redisTemplate.opsForValue().get(cacheKey);
            if (payload == null || payload.isBlank()) {
                return null;
            }
            return objectMapper.readValue(payload, TenantDetailsResponse.class);
        } catch (Exception e) {
            log.warn("Failed to read tenant details cache [{}]: {}", cacheKey, e.getMessage());
            return null;
        }
    }

    private <T> T readFromCache(String cacheKey, Class<T> type) {
        try {
            String payload = redisTemplate.opsForValue().get(cacheKey);
            if (payload == null || payload.isBlank()) {
                return null;
            }
            return objectMapper.readValue(payload, type);
        } catch (Exception e) {
            log.warn("Failed to read cache [{}]: {}", cacheKey, e.getMessage());
            return null;
        }
    }

    private void writeToCache(String cacheKey, TenantDetailsResponse response) {
        try {
            String payload = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(cacheKey, payload, TENANT_DETAILS_CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to write tenant details cache [{}]: {}", cacheKey, e.getMessage());
        }
    }

    private void writeToCache(String cacheKey, TenantPerformanceScoreResponse response) {
        try {
            String payload = objectMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(cacheKey, payload, TENANT_DETAILS_CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to write tenant performance cache [{}]: {}", cacheKey, e.getMessage());
        }
    }

    private static BigDecimal scale3(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(3, RoundingMode.HALF_UP);
        }
        return value.setScale(3, RoundingMode.HALF_UP);
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
