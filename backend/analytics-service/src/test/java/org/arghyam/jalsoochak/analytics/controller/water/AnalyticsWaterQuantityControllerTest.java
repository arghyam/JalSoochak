package org.arghyam.jalsoochak.analytics.controller.water;

import org.arghyam.jalsoochak.analytics.dto.response.AverageWaterSupplyResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicWaterQuantityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.RegionWiseWaterQuantityResponse;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsWaterQuantityController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsWaterQuantityControllerTest {

    private static final String BASE = "/api/v1/analytics";
    private static final int TENANT_ID = 12;
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 31);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SchemeRegularityService schemeRegularityService;

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

    @Test
    void getAverageWaterSupplyPerRegion_whenServiceThrows_returnsInternalServerErrorWrapper() throws Exception {
        when(schemeRegularityService.getAverageWaterSupplyPerCurrentRegionForCurrentScope(any(), any(), any()))
                .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get(BASE + "/water-supply/average-per-region")
                        .param("scope", "current")
                        .param("tenant_id", "10")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getAverageWaterSupplyPerRegion_scopeChildWithDepartmentId_routesToDepartmentService() throws Exception {
        when(schemeRegularityService.getAverageWaterSupplyPerCurrentRegionByDepartmentForChildScope(any(), any(), any(), any()))
                .thenReturn(averageWaterSupplyResponse());

        mockMvc.perform(get(BASE + "/water-supply/average-per-region")
                        .param("scope", "child")
                        .param("tenant_id", "10")
                        .param("parent_department_id", "201")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").exists());

        verify(schemeRegularityService, times(1))
                .getAverageWaterSupplyPerCurrentRegionByDepartmentForChildScope(10, 201, START, END);
    }

    @ParameterizedTest
    @MethodSource("regionWiseValidRoutes")
    void getWaterQuantityRegionWise_validRoutes(String paramName, String paramValue, boolean lgdRoute) throws Exception {
        if (lgdRoute) {
            when(schemeRegularityService.getRegionWiseWaterQuantityByLgd(TENANT_ID, Integer.parseInt(paramValue), START, END))
                    .thenReturn(regionWiseWaterQuantityResponse());
        } else {
            when(schemeRegularityService.getRegionWiseWaterQuantityByDepartment(TENANT_ID, Integer.parseInt(paramValue), START, END))
                    .thenReturn(regionWiseWaterQuantityResponse());
        }

        mockMvc.perform(get(BASE + "/water-quantity/region-wise")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param(paramName, paramValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").exists());

        if (lgdRoute) {
            verify(schemeRegularityService, times(1))
                    .getRegionWiseWaterQuantityByLgd(TENANT_ID, Integer.parseInt(paramValue), START, END);
        } else {
            verify(schemeRegularityService, times(1))
                    .getRegionWiseWaterQuantityByDepartment(TENANT_ID, Integer.parseInt(paramValue), START, END);
        }
    }

    @Test
    void getWaterQuantityRegionWise_withBothParentIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/water-quantity/region-wise")
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
    void getWaterQuantityRegionWise_withNoParentId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/water-quantity/region-wise")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getWaterQuantityRegionWise_withoutTenantId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/water-quantity/region-wise")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getWaterQuantityRegionWise_whenServiceThrows_returnsInternalServerErrorWrapper() throws Exception {
        when(schemeRegularityService.getRegionWiseWaterQuantityByLgd(TENANT_ID, 101, START, END))
                .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get(BASE + "/water-quantity/region-wise")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @ParameterizedTest
    @MethodSource("periodicValidRoutes")
    void getPeriodicWaterQuantity_validRoutes(String idParam, String idValue, String scale, boolean lgdRoute) throws Exception {
        if (lgdRoute) {
            when(schemeRegularityService.getPeriodicWaterQuantityByLgdId(
                    Integer.parseInt(idValue), START, END, PeriodScale.fromValue(scale)))
                    .thenReturn(periodicWaterQuantityResponse());
        } else {
            when(schemeRegularityService.getPeriodicWaterQuantityByDepartment(
                    Integer.parseInt(idValue), START, END, PeriodScale.fromValue(scale)))
                    .thenReturn(periodicWaterQuantityResponse());
        }

        mockMvc.perform(get(BASE + "/water-quantity/periodic")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", scale)
                        .param(idParam, idValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").exists());
    }

    @Test
    void getPeriodicWaterQuantity_withBothIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/water-quantity/periodic")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", "day")
                        .param("lgd_id", "101")
                        .param("department_id", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getPeriodicWaterQuantity_withNoId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/water-quantity/periodic")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", "day"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getPeriodicWaterQuantity_withUnsupportedScale_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/water-quantity/periodic")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", "decade")
                        .param("lgd_id", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getPeriodicWaterQuantity_whenServiceThrows_returnsInternalServerErrorWrapper() throws Exception {
        when(schemeRegularityService.getPeriodicWaterQuantityByLgdId(eq(101), eq(START), eq(END), any()))
                .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get(BASE + "/water-quantity/periodic")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", "day")
                        .param("lgd_id", "101"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    private static Stream<Arguments> regionWiseValidRoutes() {
        return Stream.of(
                Arguments.of("parent_lgd_id", "101", true),
                Arguments.of("parent_department_id", "201", false)
        );
    }

    private static Stream<Arguments> periodicValidRoutes() {
        return Stream.of(
                Arguments.of("lgd_id", "101", "day", true),
                Arguments.of("lgd_id", "101", "week", true),
                Arguments.of("lgd_id", "101", "month", true),
                Arguments.of("department_id", "201", "day", false),
                Arguments.of("department_id", "201", "week", false),
                Arguments.of("department_id", "201", "month", false)
        );
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

    private static RegionWiseWaterQuantityResponse regionWiseWaterQuantityResponse() {
        return RegionWiseWaterQuantityResponse.builder()
                .childRegionCount(0)
                .childRegions(List.of())
                .build();
    }

    private static PeriodicWaterQuantityResponse periodicWaterQuantityResponse() {
        return PeriodicWaterQuantityResponse.builder()
                .periodCount(0)
                .metrics(List.of())
                .build();
    }
}
