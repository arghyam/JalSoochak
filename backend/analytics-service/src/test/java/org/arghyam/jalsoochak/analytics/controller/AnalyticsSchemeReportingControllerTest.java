package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.dto.response.SchemeRegularityListResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SchemeStatusAndTopReportingResponse;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.repository.FactSchemePerformanceRepository;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.arghyam.jalsoochak.analytics.service.EscalationQueryService;
import org.arghyam.jalsoochak.analytics.dto.response.AnomalyListItemDto;
import org.arghyam.jalsoochak.analytics.dto.response.EscalationListItemDto;
import org.arghyam.jalsoochak.analytics.service.AnomalyQueryService;
import org.arghyam.jalsoochak.analytics.service.OperatorAttendanceQueryService;
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
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsSchemeReportingController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsSchemeReportingControllerTest {

    private static final String BASE = "/api/v1/analytics";
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

    @ParameterizedTest
    @MethodSource("schemeStatusValidRoutes")
    void getSchemeStatusCount_validRoutes(String idParam, String idValue, boolean lgdRoute) throws Exception {
        if (lgdRoute) {
            when(schemeRegularityService.getSchemeStatusCountByLgd(Integer.parseInt(idValue)))
                    .thenReturn(Map.of("active_schemes_count", 5, "inactive_schemes_count", 1));
        } else {
            when(schemeRegularityService.getSchemeStatusCountByDepartment(Integer.parseInt(idValue)))
                    .thenReturn(Map.of("active_schemes_count", 5, "inactive_schemes_count", 1));
        }

        mockMvc.perform(get(BASE + "/schemes/status-count").param(idParam, idValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.active_schemes_count").value(5))
                .andExpect(jsonPath("$.data.inactive_schemes_count").value(1));
    }

    @Test
    void getSchemeStatusCount_withBothIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/schemes/status-count")
                        .param("lgd_id", "101")
                        .param("department_id", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getSchemeStatusCount_withNoId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/schemes/status-count"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getSchemesDashboard_withParentLgdId_returnsParentLgdCName() throws Exception {
        when(schemeRegularityService.getSchemeStatusAndTopReportingByLgd(101, START, END, 5))
                .thenReturn(SchemeStatusAndTopReportingResponse.builder()
                        .parentLgdId(101)
                        .parentLgdCName("Parent")
                        .parentLgdTitle("Parent LGD")
                        .activeSchemeCount(1)
                        .inactiveSchemeCount(1)
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
                                .build()))
                        .build());

        mockMvc.perform(get(BASE + "/schemes/dashboard")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101")
                        .param("scheme_count", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.parentLgdId").value(101))
                .andExpect(jsonPath("$.data.parentLgdCName").value("Parent"))
                .andExpect(jsonPath("$.data.parentLgdTitle").value("Parent LGD"))
                .andExpect(jsonPath("$.data.topSchemes[0].totalWaterSupplied").value(150))
                .andExpect(jsonPath("$.data.topSchemes[0].immediateParentLgdId").value(100))
                .andExpect(jsonPath("$.data.topSchemes[0].immediateParentLgdCName").value("Parent"))
                .andExpect(jsonPath("$.data.topSchemes[0].immediateParentLgdTitle").value("Parent LGD"));
    }

    @Test
    void getSchemesDashboard_withParentDepartmentId_returnsParentDepartmentCName() throws Exception {
        when(schemeRegularityService.getSchemeStatusAndTopReportingByDepartment(201, START, END, 5))
                .thenReturn(SchemeStatusAndTopReportingResponse.builder()
                        .parentDepartmentId(201)
                        .parentDepartmentCName("Parent Dept")
                        .parentDepartmentTitle("Parent Dept")
                        .activeSchemeCount(1)
                        .inactiveSchemeCount(1)
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
                                .build()))
                        .build());

        mockMvc.perform(get(BASE + "/schemes/dashboard")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_department_id", "201")
                        .param("scheme_count", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.parentDepartmentId").value(201))
                .andExpect(jsonPath("$.data.parentDepartmentCName").value("Parent Dept"))
                .andExpect(jsonPath("$.data.parentDepartmentTitle").value("Parent Dept"))
                .andExpect(jsonPath("$.data.topSchemes[0].totalWaterSupplied").value(80))
                .andExpect(jsonPath("$.data.topSchemes[0].immediateParentDepartmentId").value(200))
                .andExpect(jsonPath("$.data.topSchemes[0].immediateParentDepartmentCName").value("Parent Dept"))
                .andExpect(jsonPath("$.data.topSchemes[0].immediateParentDepartmentTitle").value("Parent Dept"));
    }

    @Test
    void getSchemeRegionReport_withParentLgdId_routesToLgdService() throws Exception {
        when(schemeRegularityService.getSchemeRegionReportByLgd(101, START, END, null, null))
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
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.parentLgdId").value(101))
                .andExpect(jsonPath("$.data.schemes[0].schemeId").value(1));

        verify(schemeRegularityService, times(1))
                .getSchemeRegionReportByLgd(101, START, END, null, null);
        verify(schemeRegularityService, never()).getSchemeRegionReportByDepartment(any(), any(), any(), any(), any());
    }

    @Test
    void getSchemeRegionReport_withParentDepartmentId_routesToDepartmentService() throws Exception {
        when(schemeRegularityService.getSchemeRegionReportByDepartment(201, START, END, null, null))
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
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_department_id", "201"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.parentDepartmentId").value(201))
                .andExpect(jsonPath("$.data.schemes[0].schemeId").value(2));

        verify(schemeRegularityService, times(1))
                .getSchemeRegionReportByDepartment(201, START, END, null, null);
        verify(schemeRegularityService, never()).getSchemeRegionReportByLgd(any(), any(), any(), any(), any());
    }

    @Test
    void getSchemeRegionReport_withBothParentIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/schemes/region-report")
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
        when(schemeRegularityService.getSchemeRegionReportByLgd(101, START, END, 2, 1))
                .thenReturn(SchemeRegularityListResponse.builder()
                        .parentLgdId(101)
                        .schemeCountInResponse(0)
                        .schemes(List.of())
                        .build());

        mockMvc.perform(get(BASE + "/schemes/region-report")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101")
                        .param("page_number", "2")
                        .param("count", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.parentLgdId").value(101));

        verify(schemeRegularityService, times(1))
                .getSchemeRegionReportByLgd(101, START, END, 2, 1);
    }

    @Test
    void getSchemeRegionReport_withCsvOutputFormat_returnsCsvAttachmentForParentLgd() throws Exception {
        when(schemeRegularityService.getSchemeRegionReportByLgd(101, START, END, null, null))
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
        when(schemeRegularityService.getSchemeRegionReportByDepartment(201, START, END, null, null))
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
        when(schemeRegularityService.getSchemeRegionReportByLgd(101, START, END, null, null))
                .thenReturn(SchemeRegularityListResponse.builder()
                        .parentLgdId(101)
                        .schemes(List.of())
                        .build());

        mockMvc.perform(get(BASE + "/schemes/region-report")
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
    void getSchemePerformance_schemePreferredOverTenant() throws Exception {
        when(schemePerformanceRepository.findBySchemeId(300)).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/scheme-performance")
                        .param("tenantId", "10")
                        .param("schemeId", "300"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        verify(schemePerformanceRepository, times(1)).findBySchemeId(300);
        verify(schemePerformanceRepository, never()).findByTenantId(any());
    }

    @Test
    void getSchemePerformance_tenantOnly_routesToTenantBranch() throws Exception {
        when(schemePerformanceRepository.findByTenantId(10)).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/scheme-performance").param("tenantId", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        verify(schemePerformanceRepository, times(1)).findByTenantId(10);
    }

    @Test
    void getSchemePerformance_noFilters_returnsAll() throws Exception {
        when(schemePerformanceRepository.findAll()).thenReturn(List.of());

        mockMvc.perform(get(BASE + "/scheme-performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        verify(schemePerformanceRepository, times(1)).findAll();
    }

    @Test
    void getEscalationsPaginated_returnsExpectedShape() throws Exception {
        LocalDate start = LocalDate.of(2026, 2, 1);
        LocalDate end = LocalDate.of(2026, 3, 1);

        EscalationListItemDto e1 = EscalationListItemDto.builder()
                .id(1L)
                .tenantId(10)
                .schemeId(101)
                .userId(9001)
                .escalationType("2")
                .message("test")
                .createdAt(LocalDateTime.of(2026, 2, 15, 10, 0))
                .schemeName("Test Scheme")
                .build();

        Page<EscalationListItemDto> page = new PageImpl<>(List.of(e1), PageRequest.of(0, 5), 12);
        when(escalationQueryService.getEscalations(
                eq(10),
                eq(9001),
                eq("2"),
                eq(101),
                eq(start),
                eq(end),
                any(Pageable.class)
        )).thenReturn(page);

        mockMvc.perform(get(BASE + "/escalations")
                        .param("tenant_id", "10")
                        .param("user_id", "9001")
                        .param("page_number", "1")
                        .param("limit", "5")
                        .param("escalation_type", "2")
                        .param("scheme_id", "101")
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
                .andExpect(jsonPath("$.escalations[0].scheme_name").value("Test Scheme"));
    }

    @Test
    void getEscalationsPaginated_withoutPageAndLimit_defaultsApplied() throws Exception {
        LocalDate start = LocalDate.of(2026, 2, 1);
        LocalDate end = LocalDate.of(2026, 3, 1);

        Page<EscalationListItemDto> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(escalationQueryService.getEscalations(
                eq(10),
                eq(9001),
                eq("2"),
                eq(101),
                eq(start),
                eq(end),
                any(Pageable.class)
        )).thenReturn(page);

        mockMvc.perform(get(BASE + "/escalations")
                        .param("tenant_id", "10")
                        .param("user_id", "9001")
                        .param("escalation_type", "2")
                        .param("scheme_id", "101")
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

        AnomalyListItemDto a1 = AnomalyListItemDto.builder()
                .id(11L)
                .uuid("uuid-1")
                .type("2")
                .userId(999) // note: not the same as input mapped user id
                .schemeId(101)
                .tenantId(10)
                .status(1)
                .createdAt(OffsetDateTime.of(2026, 3, 15, 10, 0, 0, 0, ZoneOffset.UTC))
                .schemeName("Mapped Scheme")
                .build();

        Page<AnomalyListItemDto> anomalyPage = new PageImpl<>(List.of(a1), PageRequest.of(0, 10), 25);
        when(anomalyQueryService.getAnomaliesForUserSchemes(
                eq(9001), eq(start), eq(end), eq("2"), any(Pageable.class)))
                .thenReturn(anomalyPage);

        mockMvc.perform(get(BASE + "/anomalies")
                        .param("user_id", "9001")
                        .param("start_date", start.toString())
                        .param("end_date", end.toString())
                        .param("anomaly_type", "2")
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
                .andExpect(jsonPath("$.anomalies[0].scheme_name").value("Mapped Scheme"));

        verify(anomalyQueryService, times(1)).getAnomaliesForUserSchemes(
                eq(9001), eq(start), eq(end), eq("2"), any(Pageable.class));
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
        Page<AnomalyListItemDto> empty = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(anomalyQueryService.getAnomaliesForUserSchemes(
                eq(9001), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(empty);

        mockMvc.perform(get(BASE + "/anomalies")
                        .param("user_id", "9001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.total_count").value(0))
                .andExpect(jsonPath("$.anomalies").isArray());

        verify(anomalyQueryService, times(1)).getAnomaliesForUserSchemes(
                eq(9001), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    private static Stream<Arguments> schemeStatusValidRoutes() {
        return Stream.of(
                Arguments.of("lgd_id", "101", true),
                Arguments.of("department_id", "201", false)
        );
    }
}

