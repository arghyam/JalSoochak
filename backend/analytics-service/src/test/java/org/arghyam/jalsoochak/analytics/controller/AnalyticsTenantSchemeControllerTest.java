package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.dto.response.TenantDetailsResponse;
import org.arghyam.jalsoochak.analytics.entity.DimLgdLocation;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.repository.DimLgdLocationRepository;
import org.arghyam.jalsoochak.analytics.repository.DimSchemeRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.repository.FactMeterReadingRepository;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.arghyam.jalsoochak.analytics.service.TenantDetailsService;
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

@WebMvcTest(controllers = AnalyticsTenantSchemeController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsTenantSchemeControllerTest {

    private static final String BASE = "/api/v1/analytics";
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 31);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DimTenantRepository dimTenantRepository;
    @MockBean
    private DimLgdLocationRepository dimLgdLocationRepository;
    @MockBean
    private DimSchemeRepository dimSchemeRepository;
    @MockBean
    private FactMeterReadingRepository meterReadingRepository;
    @MockBean
    private TenantDetailsService tenantDetailsService;

    // not used by this controller, but present in older combined test; keep explicit no-interaction checks
    @MockBean
    private SchemeRegularityService schemeRegularityService;

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

        when(dimTenantRepository.findAll()).thenReturn(List.of(tenant));

        mockMvc.perform(get(BASE + "/tenants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].tenantId").value(1))
                .andExpect(jsonPath("$.data[0].stateCode").value("MP"));
    }

    @Test
    void getTenants_onException_returnsFailureWrapper() throws Exception {
        when(dimTenantRepository.findAll()).thenThrow(new RuntimeException("db down"));

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
    void getSchemes_withTenantId_routesToTenantFilter() throws Exception {
        when(dimSchemeRepository.findByTenantId(10)).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/schemes").param("tenantId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        verify(dimSchemeRepository, times(1)).findByTenantId(10);
        verify(dimSchemeRepository, never()).findAll();
    }

    @Test
    void getSchemes_withoutTenantId_returnsAll() throws Exception {
        when(dimSchemeRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/schemes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        verify(dimSchemeRepository, times(1)).findAll();
        verify(dimSchemeRepository, never()).findByTenantId(any());
    }

    @Test
    void getMeterReadings_schemeAndDates_routesToSchemeDateBranch() throws Exception {
        when(meterReadingRepository.findBySchemeIdAndReadingDateBetween(11, START, END)).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/meter-readings")
                        .param("schemeId", "11")
                        .param("startDate", START.toString())
                        .param("endDate", END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        verify(meterReadingRepository, times(1)).findBySchemeIdAndReadingDateBetween(11, START, END);
    }

    @Test
    void getMeterReadings_tenantAndDates_routesToTenantDateBranch() throws Exception {
        when(meterReadingRepository.findByTenantIdAndReadingDateBetween(12, START, END)).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/meter-readings")
                        .param("tenantId", "12")
                        .param("startDate", START.toString())
                        .param("endDate", END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        verify(meterReadingRepository, times(1)).findByTenantIdAndReadingDateBetween(12, START, END);
    }

    @Test
    void getMeterReadings_schemeOnly_routesToSchemeBranch() throws Exception {
        when(meterReadingRepository.findBySchemeId(13)).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/meter-readings").param("schemeId", "13"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        verify(meterReadingRepository, times(1)).findBySchemeId(13);
    }

    @Test
    void getMeterReadings_tenantOnly_routesToTenantBranch() throws Exception {
        when(meterReadingRepository.findByTenantId(14)).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/meter-readings").param("tenantId", "14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        verify(meterReadingRepository, times(1)).findByTenantId(14);
    }

    @Test
    void getMeterReadings_noFilters_returnsAll() throws Exception {
        when(meterReadingRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/meter-readings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        verify(meterReadingRepository, times(1)).findAll();
    }
}

