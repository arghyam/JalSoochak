package org.arghyam.jalsoochak.analytics.controller.scheme;

import org.arghyam.jalsoochak.analytics.dto.response.SchemeRegularityListResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SchemeStatusAndTopReportingResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SchemeStatusBreakdownResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SchemeStatusCountDTO;
import org.arghyam.jalsoochak.analytics.dto.response.SchemeStatusDTO;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.repository.DimSchemeRepository;
import org.arghyam.jalsoochak.analytics.repository.FactSchemePerformanceRepository;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsSchemeStatusController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsSchemeStatusControllerTest {

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
    private DimSchemeRepository dimSchemeRepository;

    @ParameterizedTest
    @MethodSource("schemeStatusValidRoutes")
    void getSchemeStatusCount_validRoutes(String idParam, String idValue, boolean lgdRoute) throws Exception {
        SchemeStatusBreakdownResponse breakdown = SchemeStatusBreakdownResponse.builder()
                .total(6)
                .workStatusCounts(List.of(statusCount(1, "Ongoing", 6)))
                .operatingStatusCounts(List.of(
                        statusCount(0, "Non-Operative", 1),
                        statusCount(2, "Partially Operative", 5)))
                .build();
        if (lgdRoute) {
            when(schemeRegularityService.getSchemeStatusCountByLgd(TENANT_ID, Integer.parseInt(idValue)))
                    .thenReturn(breakdown);
        } else {
            when(schemeRegularityService.getSchemeStatusCountByDepartment(TENANT_ID, Integer.parseInt(idValue)))
                    .thenReturn(breakdown);
        }

        mockMvc.perform(get(BASE + "/schemes/status-count")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param(idParam, idValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.total").value(6))
                .andExpect(jsonPath("$.data.workStatusCounts[0].code").value(1))
                .andExpect(jsonPath("$.data.workStatusCounts[0].label").value("Ongoing"))
                .andExpect(jsonPath("$.data.operatingStatusCounts[1].code").value(2))
                .andExpect(jsonPath("$.data.operatingStatusCounts[1].label").value("Partially Operative"))
                .andExpect(jsonPath("$.data.operatingStatusCounts[1].count").value(5));

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
        when(schemeRegularityService.getSchemeStatusAndTopReportingByLgd(TENANT_ID, 101, START, END, 1, 5, "reportingRate", "desc"))
                .thenReturn(SchemeStatusAndTopReportingResponse.builder()
                        .parentLgdId(101)
                        .parentLgdCName("Parent")
                        .parentLgdTitle("Parent LGD")
                        .parentLgdLevel(2)
                        .workStatusCounts(List.of(statusCount(1, "Ongoing", 2)))
                        .operatingStatusCounts(List.of(statusCount(1, "Operative", 2)))
                        .totalCount(42L)
                        .topSchemeCount(1)
                        .topSchemes(List.of(SchemeStatusAndTopReportingResponse.TopReportingScheme.builder()
                                .schemeId(1)
                                .schemeName("Scheme A")
                                .workStatus(schemeStatus(1, "Ongoing"))
                                .operatingStatus(schemeStatus(2, "Partially Operative"))
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
        when(schemeRegularityService.getSchemeStatusAndTopReportingByDepartment(TENANT_ID, 201, START, END, 1, 5, "reportingRate", "desc"))
                .thenReturn(SchemeStatusAndTopReportingResponse.builder()
                        .parentDepartmentId(201)
                        .parentDepartmentCName("Parent Dept")
                        .parentDepartmentTitle("Parent Dept")
                        .parentDepartmentLevel(4)
                        .workStatusCounts(List.of(statusCount(1, "Ongoing", 2)))
                        .operatingStatusCounts(List.of(statusCount(1, "Operative", 2)))
                        .totalCount(7L)
                        .topSchemeCount(1)
                        .topSchemes(List.of(SchemeStatusAndTopReportingResponse.TopReportingScheme.builder()
                                .schemeId(2)
                                .schemeName("Scheme B")
                                .workStatus(schemeStatus(1, "Ongoing"))
                                .operatingStatus(schemeStatus(2, "Partially Operative"))
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
    void getSchemesDashboard_withExplicitSort_routesSortArguments() throws Exception {
        when(schemeRegularityService.getSchemeStatusAndTopReportingByLgd(TENANT_ID, 101, START, END, 1, 5, "schemeName", "asc"))
                .thenReturn(SchemeStatusAndTopReportingResponse.builder()
                        .parentLgdId(101)
                        .workStatusCounts(List.of())
                        .operatingStatusCounts(List.of())
                        .totalCount(0L)
                        .topSchemeCount(0)
                        .topSchemes(List.of())
                        .build());

        mockMvc.perform(get(BASE + "/schemes/dashboard")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101")
                        .param("page_number", "1")
                        .param("limit", "5")
                        .param("sort_by", "schemeName")
                        .param("sort_dir", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void downloadSchemesDashboard_withParentLgdId_returnsCsvAttachment() throws Exception {
        mockMvc.perform(get(BASE + "/schemes/dashboard/download")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101")
                        .param("sort_by", "totalWaterSupplied")
                        .param("sort_dir", "desc"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("attachment; filename=\"scheme-dashboard_lgd_101_")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"));
    }

    @Test
    void getSchemeRegionReport_withParentLgdId_routesToLgdService() throws Exception {
        when(schemeRegularityService.getSchemeRegionReportByLgd(TENANT_ID, 101, START, END, null, null))
                .thenReturn(SchemeRegularityListResponse.builder()
                        .parentLgdId(101)
                        .totalSchemeCount(1)
                        .workStatusCounts(List.of(statusCount(1, "Ongoing", 1)))
                        .operatingStatusCounts(List.of(statusCount(1, "Operative", 1)))
                        .schemeCountInResponse(1)
                        .schemes(List.of(
                                SchemeRegularityListResponse.SchemeMetrics.builder()
                                        .schemeId(1)
                                        .schemeName("Scheme A")
                                        .stateSchemeId(10001)
                                        .centreSchemeId(20001)
                                        .workStatus(schemeStatus(1, "Ongoing"))
                                        .operatingStatus(schemeStatus(2, "Partially Operative"))
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
                .andExpect(jsonPath("$.data.schemes[0].schemeId").value(1))
                .andExpect(jsonPath("$.data.schemes[0].stateSchemeId").value(10001))
                .andExpect(jsonPath("$.data.schemes[0].centreSchemeId").value(20001));

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
                        .workStatusCounts(List.of(statusCount(4, "Handed Over", 1)))
                        .operatingStatusCounts(List.of(statusCount(0, "Non-Operative", 1)))
                        .schemeCountInResponse(1)
                        .schemes(List.of(
                                SchemeRegularityListResponse.SchemeMetrics.builder()
                                        .schemeId(2)
                                        .schemeName("Scheme B")
                                        .stateSchemeId(10002)
                                        .centreSchemeId(20002)
                                        .workStatus(schemeStatus(4, "Handed Over"))
                                        .operatingStatus(schemeStatus(0, "Non-Operative"))
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
                .andExpect(jsonPath("$.data.schemes[0].schemeId").value(2))
                .andExpect(jsonPath("$.data.schemes[0].stateSchemeId").value(10002))
                .andExpect(jsonPath("$.data.schemes[0].centreSchemeId").value(20002));

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
                                        .stateSchemeId(10001)
                                        .centreSchemeId(20001)
                                        .workStatus(schemeStatus(1, "Ongoing"))
                                        .operatingStatus(schemeStatus(2, "Partially Operative"))
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
                        "scheme_id,scheme_name,state_scheme_id,centre_scheme_id,"
                                + "work_status_code,work_status,operating_status_code,operating_status,"
                                + "supply_days,average_regularity,submission_days,submission_rate")))
                .andExpect(content().string(containsString(
                        "1,Scheme A,10001,20001,1,Ongoing,2,Partially Operative,2,0.6667,3,1.0")));
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
                                        .stateSchemeId(10002)
                                        .centreSchemeId(20002)
                                        .workStatus(schemeStatus(4, "Handed Over"))
                                        .operatingStatus(schemeStatus(0, "Non-Operative"))
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
                .andExpect(content().string(containsString(
                        "2,\"Scheme, B\",10002,20002,4,Handed Over,0,Non-Operative,0,0,1,0.3333")));
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
    void getSchemeStatusCount_whenServiceThrows_returnsInternalServerError() throws Exception {
        when(schemeRegularityService.getSchemeStatusCountByLgd(any(), any()))
                .thenThrow(new RuntimeException("db error"));

        mockMvc.perform(get(BASE + "/schemes/status-count")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("lgd_id", "101"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getSchemesDashboard_whenServiceThrowsIllegalArg_returnsBadRequest() throws Exception {
        when(schemeRegularityService.getSchemeStatusAndTopReportingByLgd(
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("invalid"));

        mockMvc.perform(get(BASE + "/schemes/dashboard")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("parent_lgd_id", "101")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getSchemesDashboard_whenServiceThrows_returnsInternalServerError() throws Exception {
        when(schemeRegularityService.getSchemeStatusAndTopReportingByLgd(
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("unexpected"));

        mockMvc.perform(get(BASE + "/schemes/dashboard")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("parent_lgd_id", "101")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getSchemeRegionReport_whenServiceThrows_returnsInternalServerError() throws Exception {
        when(schemeRegularityService.getSchemeRegionReportByLgd(any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db error"));

        mockMvc.perform(get(BASE + "/schemes/region-report")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("parent_lgd_id", "101")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void getSchemePerformance_whenServiceThrows_returnsInternalServerError() throws Exception {
        when(schemePerformanceRepository.findByTenantIdAndSchemeId(any(), any()))
                .thenThrow(new RuntimeException("db error"));

        mockMvc.perform(get(BASE + "/scheme-performance")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("schemeId", "11"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));
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

    private static SchemeStatusDTO schemeStatus(Integer code, String label) {
        return SchemeStatusDTO.builder().code(code).label(label).build();
    }

    private static SchemeStatusCountDTO statusCount(Integer code, String label, Integer count) {
        return SchemeStatusCountDTO.builder().code(code).label(label).count(count).build();
    }

    private static Stream<Arguments> schemeStatusValidRoutes() {
        return Stream.of(
                Arguments.of("lgd_id", "101", true),
                Arguments.of("department_id", "201", false)
        );
    }
}
