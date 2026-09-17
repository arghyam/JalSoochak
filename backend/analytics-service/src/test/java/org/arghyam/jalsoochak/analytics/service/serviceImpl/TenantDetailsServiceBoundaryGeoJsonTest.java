package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.analytics.dto.response.TenantBoundaryGeoJsonResponse;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.repository.TenantBoundaryRepository;
import org.arghyam.jalsoochak.analytics.repository.TenantDepartmentBoundaryRepository;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The boundary GeoJSON endpoints behind the dashboard map: a tenant's merged outline, its children
 * under an LGD parent, and the same under a department parent. Responses are Redis-cached because
 * merging polygons is expensive.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TenantDetailsServiceImpl — boundary GeoJSON")
class TenantDetailsServiceBoundaryGeoJsonTest {

    private static final int TENANT = 1;

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

    private static DimTenant tenant(int id, String stateCode) {
        DimTenant tenant = new DimTenant();
        tenant.setTenantId(id);
        tenant.setStateCode(stateCode);
        return tenant;
    }

    @BeforeEach
    void stubCacheMiss() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(anyString())).thenReturn(null);
        lenient().when(dimTenantRepository.findById(TENANT)).thenReturn(Optional.of(tenant(TENANT, "mp")));
    }

    private static Map<String, Object> childRow(int lgdId, int parentLgdId, String title) {
        return Map.of(
                "lgd_id", lgdId,
                "parent_lgd_id", parentLgdId,
                "child_level", 2,
                "title", title,
                "lgd_code", "LGD-" + lgdId,
                "boundary_geojson", "{\"type\":\"Polygon\"}");
    }

    @Nested
    @DisplayName("validation")
    class Validation {

        @Test
        void rejectsANonPositiveTenantId() {
            assertThatThrownBy(() -> service.getTenantBoundaryGeoJson(0, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tenant_id must be a positive integer");
            assertThatThrownBy(() -> service.getTenantBoundaryGeoJson(null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void reportsAnUnknownTenant() {
            when(dimTenantRepository.findById(99)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTenantBoundaryGeoJson(99, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Tenant not found for tenant_id: 99");
        }

        @Test
        void rejectsANonPositiveParentLgdId() {
            assertThatThrownBy(() -> service.getTenantBoundaryGeoJson(TENANT, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("parent_lgd_id must be a positive integer");
        }

        @Test
        void reportsAnUnknownParentLgdId() {
            when(tenantBoundaryRepository.getLocationLevel(999)).thenReturn(null);

            assertThatThrownBy(() -> service.getTenantBoundaryGeoJson(TENANT, 999))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("parent_lgd_id not found");
        }
    }

    @Nested
    @DisplayName("whole-tenant outline")
    class WholeTenant {

        @Test
        void returnsTheMergedTenantBoundaryWithNoChildren() {
            when(tenantBoundaryRepository.getMergedBoundaryForTenant(TENANT)).thenReturn(Map.of(
                    "boundary_count", 52,
                    "boundary_geojson", "{\"type\":\"MultiPolygon\"}"));

            TenantBoundaryGeoJsonResponse response = service.getTenantBoundaryGeoJson(TENANT, null);

            assertThat(response.getTenantId()).isEqualTo(TENANT);
            assertThat(response.getStateCode()).isEqualTo("mp");
            assertThat(response.getParentLgdLevel()).isNull();
            assertThat(response.getParentDepartmentLevel()).isNull();
            assertThat(response.getChildBoundaryCount()).isEqualTo(52);
            assertThat(response.getChildRegionCount()).isZero();
            assertThat(response.getParentBoundaryGeoJson()).isEqualTo("{\"type\":\"MultiPolygon\"}");
            assertThat(response.getChildRegions()).isEmpty();
        }

        @Test
        void toleratesAnAbsentBoundaryCount() {
            when(tenantBoundaryRepository.getMergedBoundaryForTenant(TENANT))
                    .thenReturn(java.util.Collections.singletonMap("boundary_geojson", "{}"));

            assertThat(service.getTenantBoundaryGeoJson(TENANT, null).getChildBoundaryCount()).isNull();
        }

        @Test
        void rejectsANonNumericBoundaryCount() {
            when(tenantBoundaryRepository.getMergedBoundaryForTenant(TENANT))
                    .thenReturn(Map.of("boundary_count", "many", "boundary_geojson", "{}"));

            assertThatThrownBy(() -> service.getTenantBoundaryGeoJson(TENANT, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Expected numeric column boundary_count");
        }
    }

    @Nested
    @DisplayName("children under an LGD parent")
    class ByLgdParent {

        @BeforeEach
        void stubLgdParent() {
            when(tenantBoundaryRepository.getLocationLevel(101)).thenReturn(1);
            when(tenantBoundaryRepository.getChildLevelByParent(TENANT, 101, 1))
                    .thenReturn(List.of(childRow(201, 101, "District A"), childRow(202, 101, "District B")));
            when(tenantBoundaryRepository.getMergedBoundaryByParent(TENANT, 101, 1))
                    .thenReturn(Map.of("child_count", 2, "boundary_geojson", "{\"merged\":true}"));
        }

        @Test
        void mapsEveryChildRegion() {
            when(tenantBoundaryRepository.getBoundaryGeoJsonByLgdId(TENANT, 101)).thenReturn("{\"parent\":true}");

            TenantBoundaryGeoJsonResponse response = service.getTenantBoundaryGeoJson(TENANT, 101);

            assertThat(response.getParentLgdLevel()).isEqualTo(1);
            assertThat(response.getChildBoundaryCount()).isEqualTo(2);
            assertThat(response.getChildRegionCount()).isEqualTo(2);
            assertThat(response.getChildRegions()).hasSize(2);
            assertThat(response.getChildRegions().get(0).getLgdId()).isEqualTo(201);
            assertThat(response.getChildRegions().get(0).getParentLgdId()).isEqualTo(101);
            assertThat(response.getChildRegions().get(0).getTitle()).isEqualTo("District A");
            assertThat(response.getChildRegions().get(0).getLgdCode()).isEqualTo("LGD-201");
            assertThat(response.getChildRegions().get(0).getDepartmentId()).isNull();
        }

        @Test
        void prefersTheParentsOwnBoundaryOverTheMergedChildBoundary() {
            when(tenantBoundaryRepository.getBoundaryGeoJsonByLgdId(TENANT, 101)).thenReturn("{\"parent\":true}");

            assertThat(service.getTenantBoundaryGeoJson(TENANT, 101).getParentBoundaryGeoJson())
                    .isEqualTo("{\"parent\":true}");
        }

        @Test
        void fallsBackToTheMergedChildBoundaryWhenTheParentHasNone() {
            when(tenantBoundaryRepository.getBoundaryGeoJsonByLgdId(TENANT, 101)).thenReturn(null);
            assertThat(service.getTenantBoundaryGeoJson(TENANT, 101).getParentBoundaryGeoJson())
                    .isEqualTo("{\"merged\":true}");

            when(tenantBoundaryRepository.getBoundaryGeoJsonByLgdId(TENANT, 101)).thenReturn("   ");
            assertThat(service.getTenantBoundaryGeoJson(TENANT, 101).getParentBoundaryGeoJson())
                    .isEqualTo("{\"merged\":true}");
        }

        @Test
        void returnsAnEmptyChildListWhenTheParentHasNoChildren() {
            when(tenantBoundaryRepository.getChildLevelByParent(TENANT, 101, 1)).thenReturn(List.of());
            when(tenantBoundaryRepository.getBoundaryGeoJsonByLgdId(TENANT, 101)).thenReturn("{}");

            TenantBoundaryGeoJsonResponse response = service.getTenantBoundaryGeoJson(TENANT, 101);

            assertThat(response.getChildRegions()).isEmpty();
            assertThat(response.getChildRegionCount()).isZero();
        }
    }

    @Nested
    @DisplayName("children under a department parent")
    class ByDepartmentParent {

        private static Map<String, Object> departmentChildRow(int departmentId, String title) {
            return Map.of(
                    "department_id", departmentId,
                    "parent_department_id", 501,
                    "child_level", 3,
                    "title", title,
                    "lgd_code", "DEP-" + departmentId,
                    "boundary_geojson", "{\"type\":\"Polygon\"}");
        }

        @BeforeEach
        void stubDepartmentParent() {
            when(tenantDepartmentBoundaryRepository.getDepartmentLevel(TENANT, 501)).thenReturn(2);
            when(tenantDepartmentBoundaryRepository.getChildDepartmentsByParent(TENANT, 501, 2))
                    .thenReturn(List.of(departmentChildRow(601, "Division A")));
            when(tenantDepartmentBoundaryRepository.getMergedBoundaryByParentDepartment(TENANT, 501, 2))
                    .thenReturn(Map.of("child_count", 1, "boundary_geojson", "{\"merged\":true}"));
        }

        @Test
        void rejectsANonPositiveTenantOrParentDepartmentId() {
            assertThatThrownBy(() -> service.getTenantBoundaryGeoJsonByParentDepartment(0, 501))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tenant_id must be a positive integer");
            assertThatThrownBy(() -> service.getTenantBoundaryGeoJsonByParentDepartment(TENANT, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("parent_department_id must be a positive integer");
            assertThatThrownBy(() -> service.getTenantBoundaryGeoJsonByParentDepartment(TENANT, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void reportsAnUnknownParentDepartment() {
            when(tenantDepartmentBoundaryRepository.getDepartmentLevel(TENANT, 999)).thenReturn(null);

            assertThatThrownBy(() -> service.getTenantBoundaryGeoJsonByParentDepartment(TENANT, 999))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("parent_department_id not found");
        }

        @Test
        void reportsALeafDepartmentThatHasNoChildLevel() {
            when(tenantDepartmentBoundaryRepository.getDepartmentLevel(TENANT, 501)).thenReturn(6);

            assertThatThrownBy(() -> service.getTenantBoundaryGeoJsonByParentDepartment(TENANT, 501))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No child department level available");
        }

        @Test
        void reportsAnUnknownTenant() {
            when(dimTenantRepository.findById(99)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTenantBoundaryGeoJsonByParentDepartment(99, 501))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Tenant not found");
        }

        @Test
        void mapsEveryChildDepartment() {
            when(tenantDepartmentBoundaryRepository.getBoundaryGeoJsonByDepartmentId(TENANT, 501))
                    .thenReturn("{\"parent\":true}");

            TenantBoundaryGeoJsonResponse response =
                    service.getTenantBoundaryGeoJsonByParentDepartment(TENANT, 501);

            assertThat(response.getParentDepartmentLevel()).isEqualTo(2);
            assertThat(response.getParentLgdLevel()).isNull();
            assertThat(response.getChildRegionCount()).isEqualTo(1);
            assertThat(response.getChildRegions().get(0).getDepartmentId()).isEqualTo(601);
            assertThat(response.getChildRegions().get(0).getParentDepartmentId()).isEqualTo(501);
            assertThat(response.getChildRegions().get(0).getLgdId()).isNull();
            assertThat(response.getChildRegions().get(0).getTitle()).isEqualTo("Division A");
        }

        @Test
        void fallsBackToTheMergedChildBoundaryWhenTheParentDepartmentHasNone() {
            when(tenantDepartmentBoundaryRepository.getBoundaryGeoJsonByDepartmentId(TENANT, 501))
                    .thenReturn(null);

            assertThat(service.getTenantBoundaryGeoJsonByParentDepartment(TENANT, 501)
                    .getParentBoundaryGeoJson()).isEqualTo("{\"merged\":true}");
        }
    }

    @Nested
    @DisplayName("caching")
    class Caching {

        @Test
        void servesAWarmCacheEntryWithoutTouchingTheRepositories() throws Exception {
            TenantBoundaryGeoJsonResponse cached = TenantBoundaryGeoJsonResponse.builder()
                    .tenantId(TENANT).stateCode("mp").childRegions(List.of()).build();
            when(valueOperations.get(anyString())).thenReturn("{\"cached\":true}");
            when(objectMapper.readValue(anyString(), eq(TenantBoundaryGeoJsonResponse.class)))
                    .thenReturn(cached);

            assertThat(service.getTenantBoundaryGeoJson(TENANT, null)).isSameAs(cached);
            verify(tenantBoundaryRepository, never()).getMergedBoundaryForTenant(anyInt());
        }

        @Test
        void recomputesWhenTheCachedEntryCannotBeDeserialised() throws Exception {
            when(valueOperations.get(anyString())).thenReturn("{corrupt");
            when(objectMapper.readValue(anyString(), eq(TenantBoundaryGeoJsonResponse.class)))
                    .thenThrow(new com.fasterxml.jackson.core.JsonParseException(null, "corrupt"));
            when(tenantBoundaryRepository.getMergedBoundaryForTenant(TENANT))
                    .thenReturn(Map.of("boundary_count", 1, "boundary_geojson", "{}"));

            assertThat(service.getTenantBoundaryGeoJson(TENANT, null)).isNotNull();
        }

        @Test
        void keysTheWholeTenantAndParentScopedResponsesSeparately() {
            when(tenantBoundaryRepository.getMergedBoundaryForTenant(TENANT))
                    .thenReturn(Map.of("boundary_count", 1, "boundary_geojson", "{}"));
            when(tenantBoundaryRepository.getLocationLevel(101)).thenReturn(1);
            when(tenantBoundaryRepository.getChildLevelByParent(TENANT, 101, 1)).thenReturn(List.of());
            when(tenantBoundaryRepository.getMergedBoundaryByParent(TENANT, 101, 1))
                    .thenReturn(Map.of("child_count", 0, "boundary_geojson", "{}"));

            service.getTenantBoundaryGeoJson(TENANT, null);
            service.getTenantBoundaryGeoJson(TENANT, 101);

            ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
            verify(valueOperations, org.mockito.Mockito.atLeastOnce())
                    .get(keys.capture());
            assertThat(keys.getAllValues()).anyMatch(k -> k.contains(":parent:all"));
            assertThat(keys.getAllValues()).anyMatch(k -> k.contains(":parent:101"));
        }

        @Test
        void stillReturnsAResponseWhenWritingToTheCacheFails() {
            when(tenantBoundaryRepository.getMergedBoundaryForTenant(TENANT))
                    .thenReturn(Map.of("boundary_count", 1, "boundary_geojson", "{}"));
            org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                    .when(valueOperations).set(anyString(), anyString(), any(java.time.Duration.class));

            assertThat(service.getTenantBoundaryGeoJson(TENANT, null)).isNotNull();
        }
    }
}
