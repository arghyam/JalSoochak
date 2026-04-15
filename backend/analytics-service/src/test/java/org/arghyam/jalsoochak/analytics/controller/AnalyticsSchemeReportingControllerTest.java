package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.dto.response.SchemeRegularityListResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SchemeStatusAndTopReportingResponse;
import org.arghyam.jalsoochak.analytics.entity.DimUser;
import org.arghyam.jalsoochak.analytics.entity.FactEscalation;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.repository.DimUserRepository;
import org.arghyam.jalsoochak.analytics.repository.FactEscalationRepository;
import org.arghyam.jalsoochak.analytics.repository.FactSchemePerformanceRepository;
import org.arghyam.jalsoochak.analytics.service.AuthenticatedRequestContextService;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.arghyam.jalsoochak.analytics.service.EscalationQueryService;
import org.arghyam.jalsoochak.analytics.dto.response.AnomalyListItemDto;
import org.arghyam.jalsoochak.analytics.dto.response.EscalationListItemDto;
import org.arghyam.jalsoochak.analytics.service.AnomalyQueryService;
import org.arghyam.jalsoochak.analytics.service.OperatorAttendanceQueryService;
import org.arghyam.jalsoochak.analytics.service.UserAlertTotalsService;
import org.arghyam.jalsoochak.analytics.dto.response.OperatorAttendanceDayItemDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsSchemeReportingController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsSchemeReportingControllerTest {

    private static final String BASE = "/api/v1/analytics";
    private static final int TENANT_ID = 12;
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 31);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FactSchemePerformanceRepository schemePerformanceRepository;
    @MockBean
    private SchemeRegularityService schemeRegularityService;
    @MockBean
    private EscalationQueryService escalationQueryService;
    @MockBean
    private AnomalyQueryService anomalyQueryService;
    @MockBean
    private OperatorAttendanceQueryService operatorAttendanceQueryService;
    @MockBean
    private UserAlertTotalsService userAlertTotalsService;
    @MockBean
    private AuthenticatedRequestContextService authenticatedRequestContextService;
    @MockBean
    private DimUserRepository dimUserRepository;
    @MockBean
    private FactEscalationRepository factEscalationRepository;

    @ParameterizedTest
    @MethodSource("schemeStatusValidRoutes")
    void getSchemeStatusCount_validRoutes(String idParam, String idValue, boolean lgdRoute) throws Exception {
        if (lgdRoute) {
            when(schemeRegularityService.getSchemeStatusCountByLgd(TENANT_ID, Integer.parseInt(idValue)))
                    .thenReturn(Map.of("active_schemes_count", 5, "inactive_schemes_count", 1));
        } else {
            when(schemeRegularityService.getSchemeStatusCountByDepartment(TENANT_ID, Integer.parseInt(idValue)))
                    .thenReturn(Map.of("active_schemes_count", 5, "inactive_schemes_count", 1));
        }

        mockMvc.perform(get(BASE + "/schemes/status-count")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param(idParam, idValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.active_schemes_count").value(5))
                .andExpect(jsonPath("$.data.inactive_schemes_count").value(1));

        if (lgdRoute) {
            verify(schemeRegularityService, times(1))
                    .getSchemeStatusCountByLgd(TENANT_ID, Integer.parseInt(idValue));
        } else {
            verify(schemeRegularityService, times(1))
                    .getSchemeStatusCountByDepartment(TENANT_ID, Integer.parseInt(idValue));
        }
    }

    @Test
    void getSchemeStatusCount_withBothIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/schemes/status-count")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("lgd_id", "101")
                        .param("department_id", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getSchemeStatusCount_withNoId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/schemes/status-count")
                        .param("tenant_id", String.valueOf(TENANT_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getSchemeStatusCount_withoutTenantId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/schemes/status-count")
                        .param("lgd_id", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSchemesDashboard_withParentLgdId_returnsParentLgdCName() throws Exception {
        when(schemeRegularityService.getSchemeStatusAndTopReportingByLgd(TENANT_ID, 101, START, END, 1, 5))
                .thenReturn(SchemeStatusAndTopReportingResponse.builder()
                        .parentLgdId(101)
                        .parentLgdCName("Parent")
                        .parentLgdTitle("Parent LGD")
                        .parentLgdLevel(2)
                        .activeSchemeCount(1)
                        .inactiveSchemeCount(1)
                        .totalCount(42L)
                        .topSchemeCount(1)
                        .topSchemes(List.of(SchemeStatusAndTopReportingResponse.TopReportingScheme.builder()
                                .schemeId(1)
                                .schemeName("Scheme A")
                                .statusCode(1)
                                .status("active")
                                .submissionDays(10)
                                .reportingRate(BigDecimal.valueOf(0.5))
                                .totalWaterSupplied(150L)
                                .immediateParentLgdId(100)
                                .immediateParentLgdCName("Parent")
                                .immediateParentLgdTitle("Parent LGD")
                                .immediateParentLgdLevel(3)
                                .lgdLadder(Map.of(
                                        "level_1", 10,
                                        "level_2", 50,
                                        "level_3", 100,
                                        "level_4", 101
                                ))
                                .departmentLadder(Map.of(
                                        "level_1", 2001,
                                        "level_2", 2002
                                ))
                                .build()))
                        .build());

        mockMvc.perform(get(BASE + "/schemes/dashboard")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101")
                        .param("page_number", "1")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalCount").value(42))
                .andExpect(jsonPath("$.data.parentLgdId").value(101))
                .andExpect(jsonPath("$.data.parentLgdCName").value("Parent"))
                .andExpect(jsonPath("$.data.parentLgdTitle").value("Parent LGD"))
                .andExpect(jsonPath("$.data.parentLgdLevel").value(2))
                .andExpect(jsonPath("$.data.topSchemes[0].totalWaterSupplied").value(150))
                .andExpect(jsonPath("$.data.topSchemes[0].immediateParentLgdId").value(100))
                .andExpect(jsonPath("$.data.topSchemes[0].immediateParentLgdCName").value("Parent"))
                .andExpect(jsonPath("$.data.topSchemes[0].immediateParentLgdTitle").value("Parent LGD"))
                .andExpect(jsonPath("$.data.topSchemes[0].immediateParentLgdLevel").value(3))
                .andExpect(jsonPath("$.data.topSchemes[0].lgdLadder.level_1").value(10))
                .andExpect(jsonPath("$.data.topSchemes[0].departmentLadder.level_2").value(2002));
    }

    @Test
    void getSchemesDashboard_withParentDepartmentId_returnsParentDepartmentCName() throws Exception {
        when(schemeRegularityService.getSchemeStatusAndTopReportingByDepartment(TENANT_ID, 201, START, END, 1, 5))
                .thenReturn(SchemeStatusAndTopReportingResponse.builder()
                        .parentDepartmentId(201)
                        .parentDepartmentCName("Parent Dept")
                        .parentDepartmentTitle("Parent Dept")
                        .parentDepartmentLevel(4)
                        .activeSchemeCount(1)
                        .inactiveSchemeCount(1)
                        .totalCount(7L)
                        .topSchemeCount(1)
                        .topSchemes(List.of(SchemeStatusAndTopReportingResponse.TopReportingScheme.builder()
                                .schemeId(2)
                                .schemeName("Scheme B")
                                .statusCode(1)
                                .status("active")
                                .submissionDays(8)
                                .reportingRate(BigDecimal.valueOf(0.4))
                                .totalWaterSupplied(80L)
                                .immediateParentDepartmentId(200)
                                .immediateParentDepartmentCName("Parent Dept")
                                .immediateParentDepartmentTitle("Parent Dept")
                                .immediateParentDepartmentLevel(5)
                                .lgdLadder(Map.of("level_1", 11, "level_2", 22, "level_3", 33))
                                .departmentLadder(Map.of("level_1", 900, "level_2", 901, "level_3", 902, "level_4", 903))
                                .build()))
                        .build());

        mockMvc.perform(get(BASE + "/schemes/dashboard")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_department_id", "201")
                        .param("page_number", "1")
                        .param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalCount").value(7))
                .andExpect(jsonPath("$.data.parentDepartmentId").value(201))
                .andExpect(jsonPath("$.data.parentDepartmentCName").value("Parent Dept"))
                .andExpect(jsonPath("$.data.parentDepartmentTitle").value("Parent Dept"))
                .andExpect(jsonPath("$.data.parentDepartmentLevel").value(4))
                .andExpect(jsonPath("$.data.topSchemes[0].totalWaterSupplied").value(80))
                .andExpect(jsonPath("$.data.topSchemes[0].immediateParentDepartmentId").value(200))
                .andExpect(jsonPath("$.data.topSchemes[0].immediateParentDepartmentCName").value("Parent Dept"))
                .andExpect(jsonPath("$.data.topSchemes[0].immediateParentDepartmentTitle").value("Parent Dept"))
                .andExpect(jsonPath("$.data.topSchemes[0].immediateParentDepartmentLevel").value(5))
                .andExpect(jsonPath("$.data.topSchemes[0].lgdLadder.level_2").value(22))
                .andExpect(jsonPath("$.data.topSchemes[0].departmentLadder.level_4").value(903));
    }

    @Test
    void getSchemesDashboard_withoutTenantId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/schemes/dashboard")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSchemeRegionReport_withParentLgdId_routesToLgdService() throws Exception {
        when(schemeRegularityService.getSchemeRegionReportByLgd(TENANT_ID, 101, START, END, null, null))
                .thenReturn(SchemeRegularityListResponse.builder()
                        .parentLgdId(101)
                        .totalSchemeCount(1)
                        .activeSchemeCount(1)
                        .inactiveSchemeCount(0)
                        .schemeCountInResponse(1)
                        .schemes(List.of(
                                SchemeRegularityListResponse.SchemeMetrics.builder()
                                        .schemeId(1)
                                        .schemeName("Scheme A")
                                        .statusCode(1)
                                        .status("active")
                                        .supplyDays(2)
                                        .averageRegularity(BigDecimal.valueOf(0.6667))
                                        .submissionDays(3)
                                        .submissionRate(BigDecimal.valueOf(1.0000))
                                        .build()))
                        .build());

        mockMvc.perform(get(BASE + "/schemes/region-report")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.parentLgdId").value(101))
                .andExpect(jsonPath("$.data.schemes[0].schemeId").value(1));

        verify(schemeRegularityService, times(1))
                .getSchemeRegionReportByLgd(TENANT_ID, 101, START, END, null, null);
        verify(schemeRegularityService, never()).getSchemeRegionReportByDepartment(any(), any(), any(), any(), any(), any());
    }

    @Test
    void getSchemeRegionReport_withParentDepartmentId_routesToDepartmentService() throws Exception {
        when(schemeRegularityService.getSchemeRegionReportByDepartment(TENANT_ID, 201, START, END, null, null))
                .thenReturn(SchemeRegularityListResponse.builder()
                        .parentDepartmentId(201)
                        .totalSchemeCount(1)
                        .activeSchemeCount(0)
                        .inactiveSchemeCount(1)
                        .schemeCountInResponse(1)
                        .schemes(List.of(
                                SchemeRegularityListResponse.SchemeMetrics.builder()
                                        .schemeId(2)
                                        .schemeName("Scheme B")
                                        .statusCode(0)
                                        .status("inactive")
                                        .supplyDays(0)
                                        .averageRegularity(BigDecimal.ZERO)
                                        .submissionDays(1)
                                        .submissionRate(BigDecimal.valueOf(0.3333))
                                        .build()))
                        .build());

        mockMvc.perform(get(BASE + "/schemes/region-report")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_department_id", "201"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.parentDepartmentId").value(201))
                .andExpect(jsonPath("$.data.schemes[0].schemeId").value(2));

        verify(schemeRegularityService, times(1))
                .getSchemeRegionReportByDepartment(TENANT_ID, 201, START, END, null, null);
        verify(schemeRegularityService, never()).getSchemeRegionReportByLgd(any(), any(), any(), any(), any(), any());
    }

    @Test
    void getSchemeRegionReport_withBothParentIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/schemes/region-report")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101")
                        .param("parent_department_id", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getSchemeRegionReport_withPaginationParams_passesPaginationToService() throws Exception {
        when(schemeRegularityService.getSchemeRegionReportByLgd(TENANT_ID, 101, START, END, 2, 1))
                .thenReturn(SchemeRegularityListResponse.builder()
                        .parentLgdId(101)
                        .schemeCountInResponse(0)
                        .schemes(List.of())
                        .build());

        mockMvc.perform(get(BASE + "/schemes/region-report")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101")
                        .param("page_number", "2")
                        .param("count", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.parentLgdId").value(101));

        verify(schemeRegularityService, times(1))
                .getSchemeRegionReportByLgd(TENANT_ID, 101, START, END, 2, 1);
    }

    @Test
    void getSchemeRegionReport_withCsvOutputFormat_returnsCsvAttachmentForParentLgd() throws Exception {
        when(schemeRegularityService.getSchemeRegionReportByLgd(TENANT_ID, 101, START, END, null, null))
                .thenReturn(SchemeRegularityListResponse.builder()
                        .parentLgdId(101)
                        .parentLgdCName("Parent LGD Name")
                        .schemes(List.of(
                                SchemeRegularityListResponse.SchemeMetrics.builder()
                                        .schemeId(1)
                                        .schemeName("Scheme A")
                                        .statusCode(1)
                                        .status("active")
                                        .supplyDays(2)
                                        .averageRegularity(BigDecimal.valueOf(0.6667))
                                        .submissionDays(3)
                                        .submissionRate(BigDecimal.valueOf(1.0000))
                                        .build()))
                        .build());

        mockMvc.perform(get(BASE + "/schemes/region-report")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101")
                        .param("output_format", "csv"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"scheme-region-report_parent_lgd_name_2026-01-01_to_2026-01-31.csv\""))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(startsWith(
                        "scheme_id,scheme_name,status_code,status,supply_days,average_regularity,submission_days,submission_rate")))
                .andExpect(content().string(containsString("1,Scheme A,1,active,2,0.6667,3,1.0")));
    }

    @Test
    void getSchemeRegionReport_withCsvOutputFormat_returnsCsvAttachmentForParentDepartment() throws Exception {
        when(schemeRegularityService.getSchemeRegionReportByDepartment(TENANT_ID, 201, START, END, null, null))
                .thenReturn(SchemeRegularityListResponse.builder()
                        .parentDepartmentId(201)
                        .parentDepartmentCName("Department (HQ)")
                        .schemes(List.of(
                                SchemeRegularityListResponse.SchemeMetrics.builder()
                                        .schemeId(2)
                                        .schemeName("Scheme, B")
                                        .statusCode(0)
                                        .status("inactive")
                                        .supplyDays(0)
                                        .averageRegularity(BigDecimal.ZERO)
                                        .submissionDays(1)
                                        .submissionRate(BigDecimal.valueOf(0.3333))
                                        .build()))
                        .build());

        mockMvc.perform(get(BASE + "/schemes/region-report")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_department_id", "201")
                        .param("output_format", "CSV"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"scheme-region-report_department_hq_2026-01-01_to_2026-01-31.csv\""))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(containsString("2,\"Scheme, B\",0,inactive,0,0,1,0.3333")));
    }

    @Test
    void getSchemeRegionReport_withoutCsvOutputFormat_behavesAsJson() throws Exception {
        when(schemeRegularityService.getSchemeRegionReportByLgd(TENANT_ID, 101, START, END, null, null))
                .thenReturn(SchemeRegularityListResponse.builder()
                        .parentLgdId(101)
                        .schemes(List.of())
                        .build());

        mockMvc.perform(get(BASE + "/schemes/region-report")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101")
                        .param("output_format", "json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.parentLgdId").value(101))
                .andExpect(header().doesNotExist("Content-Disposition"));
    }

    @Test
    void getSchemeRegionReport_withoutTenantId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/schemes/region-report")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSchemePerformance_schemePreferredOverTenant() throws Exception {
        when(schemePerformanceRepository.findByTenantIdAndSchemeId(10, 300)).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/scheme-performance")
                        .param("tenant_id", "10")
                        .param("schemeId", "300"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        verify(schemePerformanceRepository, times(1)).findByTenantIdAndSchemeId(10, 300);
        verify(schemePerformanceRepository, never()).findByTenantId(any());
    }

    @Test
    void getSchemePerformance_tenantOnly_routesToTenantBranch() throws Exception {
        when(schemePerformanceRepository.findByTenantId(10)).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/scheme-performance").param("tenant_id", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        verify(schemePerformanceRepository, times(1)).findByTenantId(10);
    }

    @Test
    void getSchemePerformance_withoutTenantId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/scheme-performance"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getEscalationsPaginated_returnsExpectedShape() throws Exception {
        LocalDate start = LocalDate.of(2026, 2, 1);
        LocalDate end = LocalDate.of(2026, 3, 1);
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));

        EscalationListItemDto e1 = EscalationListItemDto.builder()
                .id(1L)
                .tenantId(10)
                .schemeId(101)
                .userId(9001)
                .escalationType("2")
                .message("test")
                .resolutionStatusCode(0)
                .createdAt(LocalDateTime.of(2026, 2, 15, 10, 0))
                .schemeName("Test Scheme")
                .build();

        Page<EscalationListItemDto> page = new PageImpl<>(List.of(e1), PageRequest.of(0, 5), 12);
        when(escalationQueryService.getEscalations(
                eq(10),
                eq(9001),
                eq("2"),
                eq(101),
                eq("Test"),
                eq(0),
                eq(start),
                eq(end),
                any(Pageable.class)
        )).thenReturn(page);

        mockMvc.perform(get(BASE + "/escalations")
                        .principal(buildJwtAuthentication())
                        .param("page_number", "1")
                        .param("limit", "5")
                        .param("escalation_type", "2")
                        .param("scheme_id", "101")
                        .param("scheme_name", "Test")
                        .param("resolution_status", "0")
                        .param("start_date", start.toString())
                        .param("end_date", end.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.limit").value(5))
                .andExpect(jsonPath("$.total_count").value(12))
                .andExpect(jsonPath("$.escalations").isArray())
                .andExpect(jsonPath("$.escalations[0].id").value(1))
                .andExpect(jsonPath("$.escalations[0].tenantId").value(10))
                .andExpect(jsonPath("$.escalations[0].userId").value(9001))
                .andExpect(jsonPath("$.escalations[0].schemeId").value(101))
                .andExpect(jsonPath("$.escalations[0].escalationType").value("2"))
                .andExpect(jsonPath("$.escalations[0].resolution_status").value("Unresolved"))
                .andExpect(jsonPath("$.escalations[0].scheme_name").value("Test Scheme"));
    }

    @Test
    void getEscalationsPaginated_withoutPageAndLimit_defaultsApplied() throws Exception {
        LocalDate start = LocalDate.of(2026, 2, 1);
        LocalDate end = LocalDate.of(2026, 3, 1);
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));

        Page<EscalationListItemDto> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(escalationQueryService.getEscalations(
                eq(10),
                eq(9001),
                eq("2"),
                eq(101),
                eq("Test"),
                eq(0),
                eq(start),
                eq(end),
                any(Pageable.class)
        )).thenReturn(page);

        mockMvc.perform(get(BASE + "/escalations")
                        .principal(buildJwtAuthentication())
                        .param("escalation_type", "2")
                        .param("scheme_id", "101")
                        .param("scheme_name", "Test")
                        .param("resolution_status", "0")
                        .param("start_date", start.toString())
                        .param("end_date", end.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.total_count").value(0))
                .andExpect(jsonPath("$.escalations").isArray());
    }

    @Test
    void getAnomalies_withExplicitDatesAndType_returnsExpectedShape() throws Exception {
        LocalDate start = LocalDate.of(2026, 3, 1);
        LocalDate end = LocalDate.of(2026, 3, 31);
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));

        AnomalyListItemDto a1 = AnomalyListItemDto.builder()
                .id(11L)
                .uuid("uuid-1")
                .type("2")
                .userId(999) // note: not the same as input mapped user id
                .schemeId(101)
                .tenantId(10)
                .statusCode(1)
                .createdAt(OffsetDateTime.of(2026, 3, 15, 10, 0, 0, 0, ZoneOffset.UTC))
                .schemeName("Mapped Scheme")
                .build();

        Page<AnomalyListItemDto> anomalyPage = new PageImpl<>(List.of(a1), PageRequest.of(0, 10), 25);
        when(anomalyQueryService.getAnomaliesForUserSchemes(
                eq(10), eq(9001), eq(start), eq(end), eq("2"), eq("Mapped"), eq(1), any(Pageable.class)))
                .thenReturn(anomalyPage);

        mockMvc.perform(get(BASE + "/anomalies")
                        .principal(buildJwtAuthentication())
                        .param("start_date", start.toString())
                        .param("end_date", end.toString())
                        .param("anomaly_type", "2")
                        .param("scheme_name", "Mapped")
                        .param("status", "1")
                        .param("page_number", "1")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.total_count").value(25))
                .andExpect(jsonPath("$.anomalies").isArray())
                .andExpect(jsonPath("$.anomalies[0].id").value(11))
                .andExpect(jsonPath("$.anomalies[0].schemeId").value(101))
                .andExpect(jsonPath("$.anomalies[0].type").value("2"))
                .andExpect(jsonPath("$.anomalies[0].status").value("In-Progress"))
                .andExpect(jsonPath("$.anomalies[0].scheme_name").value("Mapped Scheme"));

        verify(anomalyQueryService, times(1)).getAnomaliesForUserSchemes(
                eq(10), eq(9001), eq(start), eq(end), eq("2"), eq("Mapped"), eq(1), any(Pageable.class));
    }

    @Test
    void getOperatorAttendanceDayWise_returnsExpectedShape() throws Exception {
        UUID uuid = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11");
        LocalDate start = LocalDate.of(2026, 4, 1);
        LocalDate end = LocalDate.of(2026, 4, 7);

        OperatorAttendanceDayItemDto row = OperatorAttendanceDayItemDto.builder()
                .date(LocalDate.of(2026, 4, 2))
                .attendance(1)
                .build();

        when(operatorAttendanceQueryService.getDayWiseAttendance(eq(uuid), eq(start), eq(end)))
                .thenReturn(List.of(row));

        mockMvc.perform(get(BASE + "/operator-attendance")
                        .param("uuid", uuid.toString())
                        .param("start_date", start.toString())
                        .param("end_date", end.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].date").value("2026-04-02"))
                .andExpect(jsonPath("$.data[0].attendance").value(1));

        verify(operatorAttendanceQueryService, times(1)).getDayWiseAttendance(eq(uuid), eq(start), eq(end));
    }

    @Test
    void getOperatorAttendanceDayWise_whenServiceRejectsRange_returnsBadRequest() throws Exception {
        UUID uuid = UUID.fromString("b1eebc99-9c0b-4ef8-bb6d-6bb9bd380a12");
        LocalDate start = LocalDate.of(2026, 5, 10);
        LocalDate end = LocalDate.of(2026, 5, 1);

        when(operatorAttendanceQueryService.getDayWiseAttendance(eq(uuid), eq(start), eq(end)))
                .thenThrow(new IllegalArgumentException("start_date must be on or before end_date"));

        mockMvc.perform(get(BASE + "/operator-attendance")
                        .param("uuid", uuid.toString())
                        .param("start_date", start.toString())
                        .param("end_date", end.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(operatorAttendanceQueryService, times(1)).getDayWiseAttendance(eq(uuid), eq(start), eq(end));
    }

    @Test
    void getAnomalies_withoutDates_defaultsHandledInService() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));

        Page<AnomalyListItemDto> empty = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(anomalyQueryService.getAnomaliesForUserSchemes(
                eq(10), eq(9001), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(empty);

        mockMvc.perform(get(BASE + "/anomalies")
                        .principal(buildJwtAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.total_count").value(0))
                .andExpect(jsonPath("$.anomalies").isArray());

        verify(anomalyQueryService, times(1)).getAnomaliesForUserSchemes(
                eq(10), eq(9001), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void updateEscalationResolutionStatus_withEscalationId_updatesOnlyForSameUuid() throws Exception {
        UUID uuid = UUID.fromString("44444444-4444-4444-4444-444444444444");
        when(dimUserRepository.findTopByTenantIdAndUuidOrderByUpdatedAtDescCreatedAtDesc(eq(10), eq(uuid)))
                .thenReturn(Optional.of(DimUser.builder().userId(9001).tenantId(10).uuid(uuid).build()));

        FactEscalation escalation = FactEscalation.builder()
                .id(77L)
                .tenantId(10)
                .userId(9001)
                .schemeId(101)
                .resolutionStatus(0)
                .createdAt(LocalDateTime.of(2026, 2, 1, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 2, 1, 10, 0))
                .build();

        when(factEscalationRepository.findByIdAndTenantIdAndUserId(eq(77L), eq(10), eq(9001)))
                .thenReturn(Optional.of(escalation));
        when(factEscalationRepository.save(any(FactEscalation.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put(BASE + "/escalations/status")
                        .param("tenant_id", "10")
                        .param("uuid", uuid.toString())
                        .param("escalation_id", "77")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionStatus\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.escalation_id").value(77))
                .andExpect(jsonPath("$.data.resolution_status").value(2));

        verify(factEscalationRepository, times(1))
                .findByIdAndTenantIdAndUserId(eq(77L), eq(10), eq(9001));
        verify(factEscalationRepository, times(1)).save(any(FactEscalation.class));
    }

    @Test
    void updateEscalationResolutionStatus_whenNotOwnedByUuid_returnsBadRequest() throws Exception {
        UUID uuid = UUID.fromString("55555555-5555-5555-5555-555555555555");
        when(dimUserRepository.findTopByTenantIdAndUuidOrderByUpdatedAtDescCreatedAtDesc(eq(10), eq(uuid)))
                .thenReturn(Optional.of(DimUser.builder().userId(9001).tenantId(10).uuid(uuid).build()));

        when(factEscalationRepository.findByIdAndTenantIdAndUserId(eq(88L), eq(10), eq(9001)))
                .thenReturn(Optional.empty());

        mockMvc.perform(put(BASE + "/escalations/status")
                        .param("tenant_id", "10")
                        .param("uuid", uuid.toString())
                        .param("escalation_id", "88")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionStatus\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(factEscalationRepository, never()).save(any(FactEscalation.class));
    }

    @Test
    void updateEscalationResolutionStatus_withBothIdentifiers_returnsBadRequest() throws Exception {
        UUID uuid = UUID.fromString("66666666-6666-6666-6666-666666666666");
        when(dimUserRepository.findTopByTenantIdAndUuidOrderByUpdatedAtDescCreatedAtDesc(eq(10), eq(uuid)))
                .thenReturn(Optional.of(DimUser.builder().userId(9001).tenantId(10).uuid(uuid).build()));

        mockMvc.perform(put(BASE + "/escalations/status")
                        .param("tenant_id", "10")
                        .param("uuid", uuid.toString())
                        .param("escalation_id", "77")
                        .param("correlation_id", "esc-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionStatus\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verify(factEscalationRepository, never()).save(any(FactEscalation.class));
    }

    @Test
    void updateEscalationResolutionStatus_withoutResolutionStatus_returnsBadRequest() throws Exception {
        UUID uuid = UUID.fromString("77777777-7777-7777-7777-777777777777");

        mockMvc.perform(put(BASE + "/escalations/status")
                        .param("tenant_id", "10")
                        .param("uuid", uuid.toString())
                        .param("escalation_id", "77")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verify(factEscalationRepository, never()).save(any(FactEscalation.class));
    }

    @Test
    void updateEscalationResolutionStatus_withCorrelationId_updatesLatestRow() throws Exception {
        UUID uuid = UUID.fromString("88888888-8888-8888-8888-888888888888");
        when(dimUserRepository.findTopByTenantIdAndUuidOrderByUpdatedAtDescCreatedAtDesc(eq(10), eq(uuid)))
                .thenReturn(Optional.of(DimUser.builder().userId(9001).tenantId(10).uuid(uuid).build()));

        FactEscalation escalation = FactEscalation.builder()
                .id(99L)
                .tenantId(10)
                .userId(9001)
                .schemeId(101)
                .resolutionStatus(0)
                .createdAt(LocalDateTime.of(2026, 2, 2, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 2, 2, 10, 0))
                .build();

        when(factEscalationRepository.findFirstByTenantIdAndUserIdAndCorrelationIdOrderByCreatedAtDesc(eq(10), eq(9001), eq("esc-1")))
                .thenReturn(Optional.of(escalation));
        when(factEscalationRepository.save(any(FactEscalation.class))).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(put(BASE + "/escalations/status")
                        .param("tenant_id", "10")
                        .param("uuid", uuid.toString())
                        .param("correlation_id", "  esc-1  ")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionStatus\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.escalation_id").value(99))
                .andExpect(jsonPath("$.data.resolution_status").value(1));
    }

    @Test
    void updateEscalationResolutionStatus_whenUnexpectedError_returnsServerError() throws Exception {
        UUID uuid = UUID.fromString("99999999-9999-9999-9999-999999999999");
        when(dimUserRepository.findTopByTenantIdAndUuidOrderByUpdatedAtDescCreatedAtDesc(eq(10), eq(uuid)))
                .thenReturn(Optional.of(DimUser.builder().userId(9001).tenantId(10).uuid(uuid).build()));

        FactEscalation escalation = FactEscalation.builder()
                .id(77L)
                .tenantId(10)
                .userId(9001)
                .resolutionStatus(0)
                .build();
        when(factEscalationRepository.findByIdAndTenantIdAndUserId(eq(77L), eq(10), eq(9001)))
                .thenReturn(Optional.of(escalation));
        when(factEscalationRepository.save(any(FactEscalation.class)))
                .thenThrow(new RuntimeException("db down"));

        mockMvc.perform(put(BASE + "/escalations/status")
                        .param("tenant_id", "10")
                        .param("uuid", uuid.toString())
                        .param("escalation_id", "77")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionStatus\":2}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getEscalationsPaginated_whenPageNumberInvalid_returnsBadRequest() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));

        mockMvc.perform(get(BASE + "/escalations")
                        .principal(buildJwtAuthentication())
                        .param("page_number", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.total_count").value(0))
                .andExpect(jsonPath("$.escalations").isArray());
    }

    @Test
    void getEscalationsPaginated_whenLimitInvalid_returnsBadRequest() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));

        mockMvc.perform(get(BASE + "/escalations")
                        .principal(buildJwtAuthentication())
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.total_count").value(0))
                .andExpect(jsonPath("$.escalations").isArray());
    }

    @Test
    void getEscalationsPaginated_whenTenantIdMissingInAuthRef_returnsBadRequest() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, null));

        mockMvc.perform(get(BASE + "/escalations")
                        .principal(buildJwtAuthentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.total_count").value(0))
                .andExpect(jsonPath("$.escalations").isArray());
    }

    @Test
    void getEscalationsPaginated_whenUserIdNull_resolvesViaUuid() throws Exception {
        UUID uuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(null, uuid, 10));
        when(dimUserRepository.findTopByTenantIdAndUuidOrderByUpdatedAtDescCreatedAtDesc(eq(10), eq(uuid)))
                .thenReturn(Optional.of(DimUser.builder().userId(9001).tenantId(10).uuid(uuid).build()));

        Page<EscalationListItemDto> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(escalationQueryService.getEscalations(
                eq(10), eq(9001), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get(BASE + "/escalations")
                        .principal(buildJwtAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.total_count").value(0));

        verify(dimUserRepository, times(1))
                .findTopByTenantIdAndUuidOrderByUpdatedAtDescCreatedAtDesc(eq(10), eq(uuid));
    }

    @Test
    void getEscalationsPaginated_whenServiceThrows_returnsServerError() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));
        when(escalationQueryService.getEscalations(
                eq(10), eq(9001), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get(BASE + "/escalations")
                        .principal(buildJwtAuthentication()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.total_count").value(0))
                .andExpect(jsonPath("$.escalations").isArray());
    }

    @Test
    void getUserAlertTotals_returnsExpectedShape() throws Exception {
        LocalDate start = LocalDate.of(2026, 3, 1);
        LocalDate end = LocalDate.of(2026, 3, 31);

        when(userAlertTotalsService.getTotals(eq(10), eq(9001), eq(start), eq(end)))
                .thenReturn(org.arghyam.jalsoochak.analytics.dto.response.UserAlertTotalsResponse.builder()
                        .totalEscalationCount(12L)
                        .totalAnomalyCount(7L)
                        .totalMappedSchemeCount(5)
                        .totalWaterSupplied(143200L)
                        .build());

        mockMvc.perform(get(BASE + "/officer/dashboard")
                        .param("tenant_id", "10")
                        .param("user_id", "9001")
                        .param("start_date", start.toString())
                        .param("end_date", end.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalEscalationCount").value(12))
                .andExpect(jsonPath("$.data.totalAnomalyCount").value(7))
                .andExpect(jsonPath("$.data.totalMappedSchemeCount").value(5))
                .andExpect(jsonPath("$.data.totalWaterSupplied").value(143200));
    }

    private static Stream<Arguments> schemeStatusValidRoutes() {
        return Stream.of(
                Arguments.of("lgd_id", "101", true),
                Arguments.of("department_id", "201", false)
        );
    }

    private static JwtAuthenticationToken buildJwtAuthentication() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("9001")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("USER_TYPE_SECTION_OFFICER")));
    }
}

