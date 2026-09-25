package org.arghyam.jalsoochak.analytics.controller.tenant;

import org.arghyam.jalsoochak.analytics.dto.response.TenantDetailsResponse;
import org.arghyam.jalsoochak.analytics.dto.response.TenantPerformanceChildRegionDetails;
import org.arghyam.jalsoochak.analytics.dto.response.TenantPerformanceScoreResponse;
import org.arghyam.jalsoochak.analytics.entity.DimLgdLocation;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.helper.DefaultAnalyticsDateWindowProvider;
import org.arghyam.jalsoochak.analytics.repository.DimLgdLocationRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.arghyam.jalsoochak.analytics.service.TenantDetailsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsTenantController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsTenantControllerTest {

    private static final String BASE = "/api/v1/analytics";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DimTenantRepository dimTenantRepository;
    @MockBean
    private DimLgdLocationRepository dimLgdLocationRepository;
    @MockBean
    private TenantDetailsService tenantDetailsService;

    @MockBean
    private DefaultAnalyticsDateWindowProvider defaultAnalyticsDateWindowProvider;

    // not used by this controller, but present in older combined test; keep explicit no-interaction checks
    @MockBean
    private SchemeRegularityService schemeRegularityService;

    @BeforeEach
    void stubDefaultWindow() {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Kolkata");
        LocalDate end = LocalDate.now(zone).minusDays(1);
        LocalDate start = end.minusDays(29);
        when(defaultAnalyticsDateWindowProvider.defaultWindow())
                .thenReturn(new DefaultAnalyticsDateWindowProvider.DateWindow(start, end));
    }

    @Test
    void getTenants_wrapsSuccessAndData() throws Exception {
        DimTenant tenant = new DimTenant();
        tenant.setTenantId(1);
        tenant.setStateCode("MP");
        tenant.setTitle("Madhya Pradesh");
        tenant.setCountryCode("IN");
        tenant.setStatus(1);
        tenant.setRequiredLpcd(55);
        tenant.setCreatedAt(LocalDateTime.of(2026, 4, 1, 10, 15, 30));
        tenant.setUpdatedAt(LocalDateTime.of(2026, 4, 1, 10, 15, 30));

        when(dimTenantRepository.findByTenantIdGreaterThan(0)).thenReturn(List.of(tenant));

        mockMvc.perform(get(BASE + "/tenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].tenantId").value(1))
                .andExpect(jsonPath("$.data[0].stateCode").value("MP"));
    }

    @Test
    void getTenants_onException_returnsFailureWrapper() throws Exception {
        when(dimTenantRepository.findByTenantIdGreaterThan(0)).thenThrow(new RuntimeException("db down"));

        mockMvc.perform(get(BASE + "/tenants"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getTenantDetails_withParentLgdId_routesToLgdServices() throws Exception {
        when(tenantDetailsService.getTenantDetailsWithAggregatedMetrics(
                eq(10),
                eq(101),
                any(LocalDate.class),
                any(LocalDate.class)
        )).thenReturn(TenantDetailsResponse.builder().tenantId(10).build());

        mockMvc.perform(get(BASE + "/tenant_data")
                        .param("tenant_id", "10")
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenantId").value(10));

        verify(tenantDetailsService, times(1))
                .getTenantDetailsWithAggregatedMetrics(eq(10), eq(101), any(LocalDate.class), any(LocalDate.class));
        verifyNoInteractions(schemeRegularityService);
        verify(tenantDetailsService, never()).getTenantDetailsByParentDepartment(any(), any());
    }

    @Test
    void getTenantDetails_withParentDepartmentId_routesToDepartmentServices() throws Exception {
        when(tenantDetailsService.getTenantDetailsByParentDepartmentWithAggregatedMetrics(
                eq(10),
                eq(201),
                any(LocalDate.class),
                any(LocalDate.class)
        )).thenReturn(TenantDetailsResponse.builder().tenantId(10).build());

        mockMvc.perform(get(BASE + "/tenant_data")
                        .param("tenant_id", "10")
                        .param("parent_department_id", "201"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenantId").value(10));

        verify(tenantDetailsService, times(1))
                .getTenantDetailsByParentDepartmentWithAggregatedMetrics(eq(10), eq(201), any(LocalDate.class), any(LocalDate.class));
        verifyNoInteractions(schemeRegularityService);
    }

    @Test
    void getTenantDetails_withBothParentIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/tenant_data")
                        .param("tenant_id", "10")
                        .param("parent_lgd_id", "101")
                        .param("parent_department_id", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verifyNoInteractions(tenantDetailsService, schemeRegularityService);
    }

    @Test
    void getTenantDetails_withNoParentIds_routesToTenantLevelLgd() throws Exception {
        when(dimLgdLocationRepository.findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(10, 1))
                .thenReturn(Optional.of(DimLgdLocation.builder().lgdId(101).tenantId(10).lgdLevel(1).build()));
        when(tenantDetailsService.getTenantDetailsWithAggregatedMetrics(
                eq(10),
                eq(101),
                any(LocalDate.class),
                any(LocalDate.class)
        )).thenReturn(TenantDetailsResponse.builder().tenantId(10).build());

        mockMvc.perform(get(BASE + "/tenant_data").param("tenant_id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenantId").value(10));

        verify(dimLgdLocationRepository, times(1))
                .findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(10, 1);
        verify(tenantDetailsService, times(1))
                .getTenantDetailsWithAggregatedMetrics(eq(10), eq(101), any(LocalDate.class), any(LocalDate.class));
        verifyNoInteractions(schemeRegularityService);
    }

    @Test
    void getTenantDetails_withNoParentIdsAndNoTenantLevelLgd_returnsBadRequest() throws Exception {
        when(dimLgdLocationRepository.findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(10, 1))
                .thenReturn(Optional.empty());

        mockMvc.perform(get(BASE + "/tenant_data").param("tenant_id", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(dimLgdLocationRepository, times(1))
                .findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(10, 1);
        verifyNoInteractions(tenantDetailsService, schemeRegularityService);
    }

    @Test
    void getTenantPerformanceScore_withParentLgdId_routesToLgdPerformanceService() throws Exception {
        when(tenantDetailsService.getTenantPerformanceScoreByParentLgd(
                eq(10),
                eq(101),
                any(LocalDate.class),
                any(LocalDate.class)
        )).thenReturn(TenantPerformanceScoreResponse.builder()
                .tenantId(10)
                .childRegions(List.of(TenantPerformanceChildRegionDetails.builder()
                        .lgdId(110)
                        .departmentId(null)
                        .parentLgdId(101)
                        .parentDepartmentId(null)
                        .lgdLevel(2)
                        .lgdCode("C110")
                        .averagePerformanceScore(new java.math.BigDecimal("0.641"))
                        .build()))
                .build());

        mockMvc.perform(get(BASE + "/tenant_performance_score")
                        .param("tenant_id", "10")
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenantId").value(10))
                .andExpect(jsonPath("$.data.childRegions[0].lgdId").value(110))
                .andExpect(jsonPath("$.data.childRegions[0].lgdCode").value("C110"))
                .andExpect(jsonPath("$.data.childRegions[0].averagePerformanceScore").value(0.641))
                .andExpect(jsonPath("$.data.childRegions[0].title").doesNotExist())
                .andExpect(jsonPath("$.data.childRegions[0].boundaryGeoJson").doesNotExist());

        verify(tenantDetailsService, times(1))
                .getTenantPerformanceScoreByParentLgd(eq(10), eq(101), any(LocalDate.class), any(LocalDate.class));
        verifyNoInteractions(schemeRegularityService);
    }

    @Test
    void getTenantPerformanceScore_withParentDepartmentId_routesToDepartmentPerformanceService() throws Exception {
        when(tenantDetailsService.getTenantPerformanceScoreByParentDepartment(
                eq(10),
                eq(201),
                any(LocalDate.class),
                any(LocalDate.class)
        )).thenReturn(TenantPerformanceScoreResponse.builder()
                .tenantId(10)
                .childRegions(List.of(TenantPerformanceChildRegionDetails.builder()
                        .lgdId(null)
                        .departmentId(210)
                        .parentLgdId(null)
                        .parentDepartmentId(201)
                        .lgdLevel(3)
                        .lgdCode(null)
                        .averagePerformanceScore(new java.math.BigDecimal("0.888"))
                        .build()))
                .build());

        mockMvc.perform(get(BASE + "/tenant_performance_score")
                        .param("tenant_id", "10")
                        .param("parent_department_id", "201"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenantId").value(10))
                .andExpect(jsonPath("$.data.childRegions[0].departmentId").value(210))
                .andExpect(jsonPath("$.data.childRegions[0].averagePerformanceScore").value(0.888))
                .andExpect(jsonPath("$.data.childRegions[0].title").doesNotExist())
                .andExpect(jsonPath("$.data.childRegions[0].boundaryGeoJson").doesNotExist());

        verify(tenantDetailsService, times(1))
                .getTenantPerformanceScoreByParentDepartment(eq(10), eq(201), any(LocalDate.class), any(LocalDate.class));
        verifyNoInteractions(schemeRegularityService);
    }

    @Test
    void getTenantPerformanceScore_withBothParentIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/tenant_performance_score")
                        .param("tenant_id", "10")
                        .param("parent_lgd_id", "101")
                        .param("parent_department_id", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verifyNoInteractions(tenantDetailsService, schemeRegularityService);
    }
}
