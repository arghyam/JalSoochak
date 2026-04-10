package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.dto.response.AverageWaterSupplyResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardBoundaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalDashboardResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicNationalSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.service.DateDimensionService;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsWaterSupplyNationalController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsWaterSupplyNationalControllerTest {

    private static final String BASE = "/api/v1/analytics";
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 31);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SchemeRegularityService schemeRegularityService;
    @MockBean
    private DateDimensionService dateDimensionService;

    @ParameterizedTest
    @MethodSource("waterSupplyCombinationMatrix")
    void getAverageWaterSupplyPerRegion_combinationMatrix(
            String scope,
            String tenantId,
            String parentLgdId,
            String parentDepartmentId,
            int expectedStatus) throws Exception {
        when(schemeRegularityService.getAverageWaterSupplyPerCurrentRegionForCurrentScope(any(), any(), any()))
                .thenReturn(averageWaterSupplyResponse());
        when(schemeRegularityService.getAverageWaterSupplyPerNationForChildScope(any(), any()))
                .thenReturn(averageWaterSupplyResponse());
        when(schemeRegularityService.getAverageWaterSupplyPerCurrentRegionByLgdForChildScope(any(), any(), any(), any()))
                .thenReturn(averageWaterSupplyResponse());
        when(schemeRegularityService.getAverageWaterSupplyPerCurrentRegionByDepartmentForChildScope(any(), any(), any(), any()))
                .thenReturn(averageWaterSupplyResponse());

        MockHttpServletRequestBuilder request = get(BASE + "/water-supply/average-per-region")
                .param("scope", scope)
                .param("start_date", START.toString())
                .param("end_date", END.toString());
        if (tenantId != null) {
            request.param("tenant_id", tenantId);
        }
        if (parentLgdId != null) {
            request.param("parent_lgd_id", parentLgdId);
        }
        if (parentDepartmentId != null) {
            request.param("parent_department_id", parentDepartmentId);
        }

        if (expectedStatus >= 200 && expectedStatus < 300) {
            mockMvc.perform(request)
                    .andExpect(status().is(expectedStatus))
                    .andExpect(jsonPath("$.success").value(true))
                    .andExpect(jsonPath("$.data").exists());
        } else if (tenantId == null) {
            // Missing required request param is rejected by Spring before controller,
            // so response body is not our ApiResponse wrapper.
            mockMvc.perform(request)
                    .andExpect(status().is(expectedStatus));
        } else {
            mockMvc.perform(request)
                    .andExpect(status().is(expectedStatus))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.data").value(nullValue()));
        }
    }

    @Test
    void getAverageWaterSupplyPerRegion_invalidScope_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/water-supply/average-per-region")
                        .param("scope", "invalid")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("tenant_id", "10"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

//     @Test
//     void populateDateDimension_validDateRange_returnsOkAndCallsService() throws Exception {
//         mockMvc.perform(post(BASE + "/date-dimension")
//                         .param("startDate", START.toString())
//                         .param("endDate", END.toString()))
//                 .andExpect(status().isOk())
//                 .andExpect(jsonPath("$.success").value(true))
//                 .andExpect(jsonPath("$.data").value("Date dimension populated from " + START + " to " + END));

//         verify(dateDimensionService, times(1)).populateDateRange(START, END);
//     }

    @ParameterizedTest
    @MethodSource("periodicNationalSchemeRegularityValidRoutes")
    void getPeriodicNationalSchemeRegularity_validRoutes(String scale) throws Exception {
        when(schemeRegularityService.getPeriodicSchemeRegularityForNationForApi(
                START, END, PeriodScale.fromValue(scale)))
                .thenReturn(periodicNationalSchemeRegularityResponse());

        mockMvc.perform(get(BASE + "/scheme-regularity/periodic/national")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", scale))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    void getNationalDashboard_validDateRange_returnsOk() throws Exception {
        when(schemeRegularityService.getNationalDashboardForApi(START, END))
                .thenReturn(NationalDashboardResponse.builder()
                        .startDate(START)
                        .endDate(END)
                        .daysInRange(31)
                        .stateWiseQuantityPerformance(List.of())
                        .stateWiseRegularity(List.of())
                        .stateWiseReadingSubmissionRate(List.of())
                        .overallOutageReasonDistribution(Map.of())
                        .build());

        mockMvc.perform(get(BASE + "/national/dashboard")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.startDate").value("2026-01-01"));

        verify(schemeRegularityService, times(1)).getNationalDashboardForApi(START, END);
    }

    @Test
    void getNationalDashboardBoundaries_returnsOk() throws Exception {
        when(schemeRegularityService.getNationalDashboardBoundariesForApi())
                .thenReturn(NationalDashboardBoundaryResponse.builder()
                        .nationalBoundary(OBJECT_MAPPER.readTree("""
                                {"type":"Polygon","coordinates":[[[78.1,22.9],[78.2,22.9],[78.2,23.0],[78.1,22.9]]]}
                                """))
                        .stateWiseBoundaries(List.of())
                        .build());

        mockMvc.perform(get(BASE + "/national/dashboard/boundary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.nationalBoundary").exists())
                .andExpect(jsonPath("$.data.stateWiseBoundaries").isArray());

        verify(schemeRegularityService, times(1)).getNationalDashboardBoundariesForApi();
    }

    @Test
    void getPeriodicNationalSchemeRegularity_withUnsupportedScale_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/scheme-regularity/periodic/national")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", "year"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    private static Stream<Arguments> periodicNationalSchemeRegularityValidRoutes() {
        return Stream.of(
                Arguments.of("day"),
                Arguments.of("week"),
                Arguments.of("month"));
    }

    private static Stream<Arguments> waterSupplyCombinationMatrix() {
        return Stream.of(
                Arguments.of("current", "10", null, null, 200),
                Arguments.of("current", null, null, null, 400),
                Arguments.of("current", "10", "101", "201", 400),
                Arguments.of("child", null, null, null, 400),
                Arguments.of("child", "10", "101", null, 200),
                Arguments.of("child", "10", null, null, 400),
                Arguments.of("child", "10", "101", "201", 400)
        );
    }

    private static AverageWaterSupplyResponse averageWaterSupplyResponse() {
        return AverageWaterSupplyResponse.builder()
                .schemeCount(0)
                .childRegionCount(0)
                .schemes(List.of())
                .childRegions(List.of())
                .build();
    }

    private static PeriodicNationalSchemeRegularityResponse periodicNationalSchemeRegularityResponse() {
        return PeriodicNationalSchemeRegularityResponse.builder()
                .schemeCount(0)
                .periodCount(0)
                .metrics(List.of())
                .build();
    }
}

