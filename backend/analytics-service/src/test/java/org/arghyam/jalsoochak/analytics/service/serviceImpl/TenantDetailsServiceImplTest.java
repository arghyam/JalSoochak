package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.analytics.dto.response.AverageSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.TenantDetailsResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ReadingSubmissionRateResponse;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.repository.SchemeRegularityRepository;
import org.arghyam.jalsoochak.analytics.repository.TenantBoundaryRepository;
import org.arghyam.jalsoochak.analytics.repository.TenantDepartmentBoundaryRepository;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantDetailsServiceImplTest {

    @Mock
    private DimTenantRepository dimTenantRepository;
    @Mock
    private TenantBoundaryRepository tenantBoundaryRepository;
    @Mock
    private TenantDepartmentBoundaryRepository tenantDepartmentBoundaryRepository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private SchemeRegularityService schemeRegularityService;

    @InjectMocks
    private TenantDetailsServiceImpl service;

    @Test
    void getTenantDetails_invalidTenant_throws() {
        assertThatThrownBy(() -> service.getTenantDetails(0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant_id must be a positive integer");
    }

    @Test
    void getTenantDetails_nullTenantId_throws() {
        assertThatThrownBy(() -> service.getTenantDetails(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant_id must be a positive integer");
    }

    @Test
    void getTenantDetails_tenantNotFound_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(dimTenantRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTenantDetails(99, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tenant not found for tenant_id: 99");
    }

    @Test
    void getTenantDetails_parentLgdIdNotFound_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(dimTenantRepository.findById(1)).thenReturn(Optional.of(tenant(1, "mp")));
        when(tenantBoundaryRepository.getLocationLevel(999)).thenReturn(null);

        assertThatThrownBy(() -> service.getTenantDetails(1, 999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent_lgd_id not found in dim_lgd_location_table");
    }

    @Test
    void getTenantDetails_parentLgdIdNonPositive_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(dimTenantRepository.findById(1)).thenReturn(Optional.of(tenant(1, "mp")));

        assertThatThrownBy(() -> service.getTenantDetails(1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent_lgd_id must be a positive integer");
    }

    @Test
    void getTenantDetails_cacheHit_returnsCachedResponse() throws Exception {
        mockRedisValueOps();
        String key = "analytics-service:api-cache:get_tenant_details:tenant:1:parent:all:v3";
        TenantDetailsResponse cached = TenantDetailsResponse.builder().tenantId(1).stateCode("mp").build();
        when(valueOperations.get(key)).thenReturn("cached");
        when(objectMapper.readValue("cached", TenantDetailsResponse.class)).thenReturn(cached);

        TenantDetailsResponse response = service.getTenantDetails(1, null);

        assertThat(response.getTenantId()).isEqualTo(1);
        verify(dimTenantRepository, never()).findById(any());
    }

    @Test
    void getTenantDetails_withoutParent_returnsTenantMergedBoundary() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(dimTenantRepository.findById(1)).thenReturn(Optional.of(tenant(1, "mp")));
        when(tenantBoundaryRepository.getMergedBoundaryForTenant(1))
                .thenReturn(Map.of("boundary_count", 3L, "boundary_geojson", "{\"type\":\"MultiPolygon\"}"));

        TenantDetailsResponse response = service.getTenantDetails(1, null);

        assertThat(response.getTenantId()).isEqualTo(1);
        assertThat(response.getChildBoundaryCount()).isEqualTo(3);
        assertThat(response.getChildRegions()).isEmpty();
        verify(tenantBoundaryRepository).getMergedBoundaryForTenant(1);
    }

    @Test
    void getTenantDetails_withParent_returnsChildRowsAndBoundary() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(dimTenantRepository.findById(1)).thenReturn(Optional.of(tenant(1, "mp")));
        when(tenantBoundaryRepository.getLocationLevel(100)).thenReturn(1);
        when(tenantBoundaryRepository.getChildLevelByParent(1, 100, 1))
                .thenReturn(List.of(Map.of(
                        "lgd_id", 101,
                        "parent_lgd_id", 100,
                        "child_level", 2,
                        "scheme_count", 2,
                        "title", "Child A",
                        "lgd_code", "C101",
                        "boundary_geojson", "{\"type\":\"Polygon\"}"
                )));
        when(tenantBoundaryRepository.getMergedBoundaryByParent(1, 100, 1))
                .thenReturn(Map.of("child_count", 1, "boundary_geojson", "{\"type\":\"MultiPolygon\"}"));
        when(tenantBoundaryRepository.getBoundaryGeoJsonByLgdId(1, 100))
                .thenReturn("{\"type\":\"MultiPolygon\",\"source\":\"parent\"}");

        TenantDetailsResponse response = service.getTenantDetails(1, 100);

        assertThat(response.getChildBoundaryCount()).isEqualTo(1);
        assertThat(response.getBoundaryGeoJson()).isEqualTo("{\"type\":\"MultiPolygon\",\"source\":\"parent\"}");
        assertThat(response.getChildRegions()).hasSize(1);
        assertThat(response.getChildRegions().getFirst().getLgdId()).isEqualTo(101);
        verify(tenantBoundaryRepository).getLocationLevel(100);
        verify(tenantBoundaryRepository).getChildLevelByParent(1, 100, 1);
        verify(tenantBoundaryRepository).getMergedBoundaryByParent(1, 100, 1);
        verify(tenantBoundaryRepository).getBoundaryGeoJsonByLgdId(1, 100);
    }

    @Test
    void getTenantDetailsByParentDepartment_invalidParent_throws() {
        assertThatThrownBy(() -> service.getTenantDetailsByParentDepartment(1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent_department_id must be a positive integer");
    }

    @Test
    void getTenantDetailsByParentDepartment_invalidTenantId_throws() {
        assertThatThrownBy(() -> service.getTenantDetailsByParentDepartment(0, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant_id must be a positive integer");
    }

    @Test
    void getTenantDetailsByParentDepartment_tenantNotFound_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(dimTenantRepository.findById(99)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTenantDetailsByParentDepartment(99, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tenant not found for tenant_id: 99");
    }

    @Test
    void getTenantDetailsByParentDepartment_parentDepartmentNotFound_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(dimTenantRepository.findById(1)).thenReturn(Optional.of(tenant(1, "mp")));
        when(tenantDepartmentBoundaryRepository.getDepartmentLevel(1, 999)).thenReturn(null);

        assertThatThrownBy(() -> service.getTenantDetailsByParentDepartment(1, 999))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent_department_id not found for tenant");
    }

    @Test
    void getTenantDetailsByParentDepartment_parentAtLevel6_throws() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(dimTenantRepository.findById(1)).thenReturn(Optional.of(tenant(1, "mp")));
        when(tenantDepartmentBoundaryRepository.getDepartmentLevel(1, 200)).thenReturn(6);

        assertThatThrownBy(() -> service.getTenantDetailsByParentDepartment(1, 200))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No child department level available");
    }

    @Test
    void getTenantDetailsWithAggregatedMetrics_parentLgd_mergesPerformanceIntoChildRows() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);

        Integer tenantId = 1;
        Integer parentLgdId = 100;
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 3);

        when(dimTenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant(tenantId, "mp")));

        when(tenantBoundaryRepository.getLocationLevel(parentLgdId)).thenReturn(1);
        when(tenantBoundaryRepository.getChildLevelByParent(tenantId, parentLgdId, 1))
                .thenReturn(List.of(Map.of(
                        "lgd_id", 101,
                        "parent_lgd_id", 100,
                        "child_level", 2,
                        "scheme_count", 2,
                        "title", "Child A",
                        "lgd_code", "C101",
                        "boundary_geojson", "{\"type\":\"Polygon\"}"
                )));
        when(tenantBoundaryRepository.getMergedBoundaryByParent(tenantId, parentLgdId, 1))
                .thenReturn(Map.of("child_count", 1, "boundary_geojson", "{\"type\":\"MultiPolygon\"}"));
        when(tenantBoundaryRepository.getBoundaryGeoJsonByLgdId(tenantId, parentLgdId))
                .thenReturn("{\"type\":\"MultiPolygon\",\"source\":\"parent\"}");

        when(schemeRegularityService.getAverageSchemeRegularity(tenantId, parentLgdId, start, end))
                .thenReturn(AverageSchemeRegularityResponse.builder()
                        .averageRegularity(new BigDecimal("0.75"))
                        .build());
        when(schemeRegularityService.getReadingSubmissionRateByLgd(tenantId, parentLgdId, start, end))
                .thenReturn(ReadingSubmissionRateResponse.builder()
                        .readingSubmissionRate(new BigDecimal("0.84"))
                        .build());
        when(schemeRegularityService.getChildAveragePerformanceScoreByLgd(parentLgdId, start, end))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionPerformanceScore(
                                101, null, new BigDecimal("0.9"))));
        when(schemeRegularityService.getAveragePerformanceScoreByLgd(parentLgdId, start, end))
                .thenReturn(new BigDecimal("0.5"));

        TenantDetailsResponse response =
                service.getTenantDetailsWithAggregatedMetrics(tenantId, parentLgdId, start, end);

        assertThat(response.getAverageSchemeRegularity()).isEqualByComparingTo("0.75");
        assertThat(response.getReadingSubmissionRate()).isEqualByComparingTo("0.84");
        assertThat(response.getAveragePerformanceScore()).isEqualByComparingTo("0.5");
        assertThat(response.getBoundaryGeoJson()).isEqualTo("{\"type\":\"MultiPolygon\",\"source\":\"parent\"}");
        assertThat(response.getChildRegions()).hasSize(1);
        assertThat(response.getChildRegions().getFirst().getAveragePerformanceScore())
                .isEqualByComparingTo("0.9");
    }

    @Test
    void getTenantDetailsWithAggregatedMetrics_nullParentLgdId_requiresLgdForMetrics() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(dimTenantRepository.findById(1)).thenReturn(Optional.of(tenant(1, "mp")));
        when(tenantBoundaryRepository.getMergedBoundaryForTenant(1))
                .thenReturn(Map.of("boundary_count", 2, "boundary_geojson", "{}"));
        when(schemeRegularityService.getAverageSchemeRegularity(any(), isNull(), any(), any()))
                .thenThrow(new IllegalArgumentException("lgd_id must be a positive integer"));

        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 3);

        assertThatThrownBy(() -> service.getTenantDetailsWithAggregatedMetrics(1, null, start, end))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lgd_id must be a positive integer");
    }

    @Test
    void getTenantDetailsByParentDepartmentWithAggregatedMetrics_mergesPerformanceByDepartment() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);

        Integer tenantId = 1;
        Integer parentDepartmentId = 200;
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 3);

        when(dimTenantRepository.findById(tenantId)).thenReturn(Optional.of(tenant(tenantId, "mp")));
        when(tenantDepartmentBoundaryRepository.getDepartmentLevel(tenantId, parentDepartmentId)).thenReturn(2);
        when(tenantDepartmentBoundaryRepository.getChildDepartmentsByParent(tenantId, parentDepartmentId, 2))
                .thenReturn(List.of(Map.of(
                        "department_id", 201,
                        "parent_department_id", 200,
                        "child_level", 3,
                        "scheme_count", 5,
                        "title", "Dept Child",
                        "lgd_code", "D201",
                        "boundary_geojson", "{\"type\":\"Polygon\"}"
                )));
        when(tenantDepartmentBoundaryRepository.getMergedBoundaryByParentDepartment(tenantId, parentDepartmentId, 2))
                .thenReturn(Map.of("child_count", 1, "boundary_geojson", "{\"type\":\"MultiPolygon\"}"));
        when(tenantDepartmentBoundaryRepository.getBoundaryGeoJsonByDepartmentId(tenantId, parentDepartmentId))
                .thenReturn("{\"type\":\"MultiPolygon\",\"source\":\"parent_dept\"}");

        when(schemeRegularityService.getAverageSchemeRegularityByDepartment(tenantId, parentDepartmentId, start, end))
                .thenReturn(AverageSchemeRegularityResponse.builder()
                        .averageRegularity(new BigDecimal("0.66"))
                        .build());
        when(schemeRegularityService.getReadingSubmissionRateByDepartment(tenantId, parentDepartmentId, start, end))
                .thenReturn(ReadingSubmissionRateResponse.builder()
                        .readingSubmissionRate(new BigDecimal("0.77"))
                        .build());
        when(schemeRegularityService.getChildAveragePerformanceScoreByDepartment(parentDepartmentId, start, end))
                .thenReturn(List.of(
                        new SchemeRegularityRepository.ChildRegionPerformanceScore(
                                null, 201, new BigDecimal("0.88"))));
        when(schemeRegularityService.getAveragePerformanceScoreByDepartment(parentDepartmentId, start, end))
                .thenReturn(new BigDecimal("0.55"));

        TenantDetailsResponse response = service.getTenantDetailsByParentDepartmentWithAggregatedMetrics(
                tenantId, parentDepartmentId, start, end);

        assertThat(response.getAverageSchemeRegularity()).isEqualByComparingTo("0.66");
        assertThat(response.getReadingSubmissionRate()).isEqualByComparingTo("0.77");
        assertThat(response.getAveragePerformanceScore()).isEqualByComparingTo("0.55");
        assertThat(response.getBoundaryGeoJson()).isEqualTo("{\"type\":\"MultiPolygon\",\"source\":\"parent_dept\"}");
        assertThat(response.getChildRegions()).hasSize(1);
        assertThat(response.getChildRegions().getFirst().getAveragePerformanceScore())
                .isEqualByComparingTo("0.88");
    }

    @Test
    void getTenantDetailsByParentDepartmentWithAggregatedMetrics_cacheHit_returnsCachedResponse() throws Exception {
        mockRedisValueOps();

        Integer tenantId = 1;
        Integer parentDepartmentId = 200;
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 3);

        String cacheKey = "analytics-service:api-cache:get_tenant_details"
                + ":tenant:" + tenantId
                + ":parent_department:" + parentDepartmentId
                + ":from:" + start
                + ":to:" + end
                + ":v3";

        TenantDetailsResponse cached = TenantDetailsResponse.builder()
                .tenantId(tenantId)
                .stateCode("mp")
                .averageSchemeRegularity(new BigDecimal("0.11"))
                .readingSubmissionRate(new BigDecimal("0.22"))
                .averagePerformanceScore(new BigDecimal("0.33"))
                .build();

        when(valueOperations.get(cacheKey)).thenReturn("cached");
        when(objectMapper.readValue("cached", TenantDetailsResponse.class)).thenReturn(cached);

        TenantDetailsResponse response =
                service.getTenantDetailsByParentDepartmentWithAggregatedMetrics(tenantId, parentDepartmentId, start, end);

        assertThat(response).isEqualTo(cached);

        // On cache hit none of the expensive downstream calls should run.
        verify(dimTenantRepository, never()).findById(any());
        verify(tenantDepartmentBoundaryRepository, never()).getDepartmentLevel(any(), any());
        verify(tenantDepartmentBoundaryRepository, never()).getChildDepartmentsByParent(any(), any(), any());
        verify(tenantDepartmentBoundaryRepository, never()).getMergedBoundaryByParentDepartment(any(), any(), any());
        verify(schemeRegularityService, never()).getAverageSchemeRegularityByDepartment(any(), any(), any(), any());
        verify(schemeRegularityService, never()).getReadingSubmissionRateByDepartment(any(), any(), any(), any());
        verify(schemeRegularityService, never()).getChildAveragePerformanceScoreByDepartment(any(), any(), any());
        verify(schemeRegularityService, never()).getAveragePerformanceScoreByDepartment(any(), any(), any());

        verify(valueOperations, never()).set(any(), any(), any());
    }

    @Test
    void getTenantDetailsByParentDepartment_valid_returnsChildRowsAndBoundary() {
        mockRedisValueOps();
        when(valueOperations.get(any())).thenReturn(null);
        when(dimTenantRepository.findById(1)).thenReturn(Optional.of(tenant(1, "mp")));
        when(tenantDepartmentBoundaryRepository.getDepartmentLevel(1, 200)).thenReturn(2);
        when(tenantDepartmentBoundaryRepository.getChildDepartmentsByParent(1, 200, 2))
                .thenReturn(List.of(Map.of(
                        "department_id", 201,
                        "parent_department_id", 200,
                        "child_level", 3,
                        "scheme_count", 5,
                        "title", "Dept Child",
                        "lgd_code", "D201",
                        "boundary_geojson", "{\"type\":\"Polygon\"}"
                )));
        when(tenantDepartmentBoundaryRepository.getMergedBoundaryByParentDepartment(1, 200, 2))
                .thenReturn(Map.of("child_count", 1, "boundary_geojson", "{\"type\":\"MultiPolygon\"}"));
        when(tenantDepartmentBoundaryRepository.getBoundaryGeoJsonByDepartmentId(1, 200))
                .thenReturn("{\"type\":\"MultiPolygon\",\"source\":\"parent_dept\"}");

        TenantDetailsResponse response = service.getTenantDetailsByParentDepartment(1, 200);

        assertThat(response.getChildBoundaryCount()).isEqualTo(1);
        assertThat(response.getBoundaryGeoJson()).isEqualTo("{\"type\":\"MultiPolygon\",\"source\":\"parent_dept\"}");
        assertThat(response.getChildRegions()).hasSize(1);
        assertThat(response.getChildRegions().getFirst().getDepartmentId()).isEqualTo(201);
    }

    private static DimTenant tenant(Integer id, String stateCode) {
        DimTenant tenant = new DimTenant();
        tenant.setTenantId(id);
        tenant.setStateCode(stateCode);
        return tenant;
    }

    private void mockRedisValueOps() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }
}
