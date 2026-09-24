package org.arghyam.jalsoochak.analytics.controller.regularity;

import org.arghyam.jalsoochak.analytics.dto.response.AverageSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicNationalSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.helper.SingleTenantModeGuard;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
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

@WebMvcTest(controllers = AnalyticsRegularityController.class)
@Import({GlobalExceptionHandler.class, SingleTenantModeGuard.class})
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsRegularityControllerTest {

    private static final String BASE = "/api/v1/analytics";
    private static final int TENANT_ID = 12;
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 31);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SchemeRegularityService schemeRegularityService;

    @ParameterizedTest
    @MethodSource("averageRegularityValidRoutes")
    void getAverageSchemeRegularity_validScopeAndIdCombinations(
            String scope,
            String idParam,
            String idValue,
            int expectedServiceCall) throws Exception {
        Mockito.reset(schemeRegularityService);
        when(schemeRegularityService.getAverageSchemeRegularity(any(), any(), any(), any())).thenReturn(averageRegularityResponse());
        when(schemeRegularityService.getAverageSchemeRegularityByDepartment(any(), any(), any(), any())).thenReturn(averageRegularityResponse());
        when(schemeRegularityService.getAverageSchemeRegularityForChildRegions(any(), any(), any(), any())).thenReturn(averageRegularityResponse());
        when(schemeRegularityService.getAverageSchemeRegularityByDepartmentForChildRegions(any(), any(), any(), any()))
                .thenReturn(averageRegularityResponse());

        mockMvc.perform(get(BASE + "/scheme-regularity/average")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("scope", scope)
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param(idParam, idValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.averageRegularity").exists());

        int value = Integer.parseInt(idValue);
        if (expectedServiceCall == 1) {
            verify(schemeRegularityService, times(1)).getAverageSchemeRegularity(TENANT_ID, value, START, END);
        } else if (expectedServiceCall == 2) {
            verify(schemeRegularityService, times(1)).getAverageSchemeRegularityByDepartment(TENANT_ID, value, START, END);
        } else if (expectedServiceCall == 3) {
            verify(schemeRegularityService, times(1)).getAverageSchemeRegularityForChildRegions(TENANT_ID, value, START, END);
        } else {
            verify(schemeRegularityService, times(1))
                    .getAverageSchemeRegularityByDepartmentForChildRegions(TENANT_ID, value, START, END);
        }
    }

    @Test
    void getAverageSchemeRegularity_withBothParentIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/scheme-regularity/average")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("scope", "current")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101")
                        .param("parent_department_id", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getAverageSchemeRegularity_invalidScope_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/scheme-regularity/average")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("scope", "invalid")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getAverageSchemeRegularity_serviceValidationFailure_returnsBadRequest() throws Exception {
        when(schemeRegularityService.getAverageSchemeRegularity(TENANT_ID, 101, START, END))
                .thenThrow(new IllegalArgumentException("end_date must be on or after start_date"));

        mockMvc.perform(get(BASE + "/scheme-regularity/average")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("scope", "current")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getAverageSchemeRegularity_withoutTenantId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/scheme-regularity/average")
                        .param("scope", "current")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getPeriodicSchemeRegularity_withLgdId_wrapsResponse() throws Exception {
        when(schemeRegularityService.getPeriodicSchemeRegularityByLgdId(1, 101, START, END, PeriodScale.DAY))
                .thenReturn(periodicSchemeRegularityResponse());

        mockMvc.perform(get(BASE + "/scheme-regularity/periodic")
                        .param("tenant_id", "1")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", "day")
                        .param("lgd_id", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.lgdId").value(101))
                .andExpect(jsonPath("$.data.scale").value("day"));
    }

    @Test
    void getPeriodicSchemeRegularity_withBothIds_returnsBadRequestWrapper() throws Exception {
        mockMvc.perform(get(BASE + "/scheme-regularity/periodic")
                        .param("tenant_id", "1")
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
    void getPeriodicSchemeRegularity_withNoId_returnsBadRequestWrapper() throws Exception {
        mockMvc.perform(get(BASE + "/scheme-regularity/periodic")
                        .param("tenant_id", "1")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", "day"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getPeriodicSchemeRegularity_withUnsupportedScale_returnsBadRequestWrapper() throws Exception {
        mockMvc.perform(get(BASE + "/scheme-regularity/periodic")
                        .param("tenant_id", "1")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", "decade")
                        .param("lgd_id", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getPeriodicSchemeRegularity_withoutTenantId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/scheme-regularity/periodic")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", "day")
                        .param("lgd_id", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getPeriodicSchemeRegularity_withDepartmentId_routesToDepartmentService() throws Exception {
        when(schemeRegularityService.getPeriodicSchemeRegularityByDepartment(1, 201, START, END, PeriodScale.WEEK))
                .thenReturn(periodicSchemeRegularityResponse());

        mockMvc.perform(get(BASE + "/scheme-regularity/periodic")
                        .param("tenant_id", "1")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", "week")
                        .param("department_id", "201"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(schemeRegularityService, times(1))
                .getPeriodicSchemeRegularityByDepartment(1, 201, START, END, PeriodScale.WEEK);
    }

    @Test
    void getPeriodicSchemeRegularity_serviceThrows_returnsServerError() throws Exception {
        when(schemeRegularityService.getPeriodicSchemeRegularityByLgdId(1, 101, START, END, PeriodScale.DAY))
                .thenThrow(new RuntimeException("unexpected"));

        mockMvc.perform(get(BASE + "/scheme-regularity/periodic")
                        .param("tenant_id", "1")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", "day")
                        .param("lgd_id", "101"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getAverageSchemeRegularity_serviceThrows_returnsServerError() throws Exception {
        when(schemeRegularityService.getAverageSchemeRegularity(TENANT_ID, 101, START, END))
                .thenThrow(new RuntimeException("unexpected db error"));

        mockMvc.perform(get(BASE + "/scheme-regularity/average")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("scope", "current")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

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
    void getPeriodicNationalSchemeRegularity_withUnsupportedScale_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/scheme-regularity/periodic/national")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", "decade"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getPeriodicNationalSchemeRegularity_whenServiceThrows_returnsInternalServerErrorWrapper() throws Exception {
        when(schemeRegularityService.getPeriodicSchemeRegularityForNationForApi(eq(START), eq(END), any()))
                .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get(BASE + "/scheme-regularity/periodic/national")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", "day"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    private static Stream<Arguments> averageRegularityValidRoutes() {
        return Stream.of(
                Arguments.of("current", "parent_lgd_id", "101", 1),
                Arguments.of("current", "parent_department_id", "201", 2),
                Arguments.of("child", "parent_lgd_id", "101", 3),
                Arguments.of("child", "parent_department_id", "201", 4)
        );
    }

    private static AverageSchemeRegularityResponse averageRegularityResponse() {
        return AverageSchemeRegularityResponse.builder()
                .averageRegularity(BigDecimal.valueOf(0.75))
                .build();
    }

    private static PeriodicSchemeRegularityResponse periodicSchemeRegularityResponse() {
        return PeriodicSchemeRegularityResponse.builder()
                .lgdId(101)
                .schemeCount(1)
                .scale("day")
                .startDate(START)
                .endDate(END)
                .periodCount(0)
                .metrics(List.of())
                .build();
    }

    private static Stream<Arguments> periodicNationalSchemeRegularityValidRoutes() {
        return Stream.of(
                Arguments.of("day"),
                Arguments.of("week"),
                Arguments.of("month"),
                Arguments.of("quarter"),
                Arguments.of("year"));
    }

    private static PeriodicNationalSchemeRegularityResponse periodicNationalSchemeRegularityResponse() {
        return PeriodicNationalSchemeRegularityResponse.builder()
                .schemeCount(0)
                .totalAchievedFhtcCount(0L)
                .periodCount(0)
                .metrics(List.of())
                .build();
    }
}
