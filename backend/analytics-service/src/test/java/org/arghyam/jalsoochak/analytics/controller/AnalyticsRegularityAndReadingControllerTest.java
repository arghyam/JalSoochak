package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.dto.response.AverageSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ReadingSubmissionRateResponse;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsRegularityAndReadingController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsRegularityAndReadingControllerTest {

    private static final String BASE = "/api/v1/analytics";
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
        when(schemeRegularityService.getAverageSchemeRegularity(any(), any(), any())).thenReturn(averageRegularityResponse());
        when(schemeRegularityService.getAverageSchemeRegularityByDepartment(any(), any(), any())).thenReturn(averageRegularityResponse());
        when(schemeRegularityService.getAverageSchemeRegularityForChildRegions(any(), any(), any())).thenReturn(averageRegularityResponse());
        when(schemeRegularityService.getAverageSchemeRegularityByDepartmentForChildRegions(any(), any(), any()))
                .thenReturn(averageRegularityResponse());

        mockMvc.perform(get(BASE + "/scheme-regularity/average")
                        .param("scope", scope)
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param(idParam, idValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.averageRegularity").exists());

        int value = Integer.parseInt(idValue);
        if (expectedServiceCall == 1) {
            verify(schemeRegularityService, times(1)).getAverageSchemeRegularity(value, START, END);
        } else if (expectedServiceCall == 2) {
            verify(schemeRegularityService, times(1)).getAverageSchemeRegularityByDepartment(value, START, END);
        } else if (expectedServiceCall == 3) {
            verify(schemeRegularityService, times(1)).getAverageSchemeRegularityForChildRegions(value, START, END);
        } else {
            verify(schemeRegularityService, times(1)).getAverageSchemeRegularityByDepartmentForChildRegions(value, START, END);
        }
    }

    @Test
    void getAverageSchemeRegularity_withBothParentIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/scheme-regularity/average")
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
        when(schemeRegularityService.getAverageSchemeRegularity(101, START, END))
                .thenThrow(new IllegalArgumentException("end_date must be on or after start_date"));

        mockMvc.perform(get(BASE + "/scheme-regularity/average")
                        .param("scope", "current")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @ParameterizedTest
    @MethodSource("readingSubmissionValidRoutes")
    void getReadingSubmissionRate_validScopeAndIdCombinations(
            String scope,
            String idParam,
            String idValue,
            int expectedServiceCall) throws Exception {
        Mockito.reset(schemeRegularityService);
        when(schemeRegularityService.getReadingSubmissionRateByLgd(any(), any(), any())).thenReturn(readingSubmissionResponse());
        when(schemeRegularityService.getReadingSubmissionRateByDepartment(any(), any(), any())).thenReturn(readingSubmissionResponse());
        when(schemeRegularityService.getReadingSubmissionRateByLgdForChildRegions(any(), any(), any()))
                .thenReturn(readingSubmissionResponse());
        when(schemeRegularityService.getReadingSubmissionRateByDepartmentForChildRegions(any(), any(), any()))
                .thenReturn(readingSubmissionResponse());

        mockMvc.perform(get(BASE + "/reading-submission-rate")
                        .param("scope", scope)
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param(idParam, idValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.readingSubmissionRate").exists());

        int value = Integer.parseInt(idValue);
        if (expectedServiceCall == 1) {
            verify(schemeRegularityService, times(1)).getReadingSubmissionRateByLgd(value, START, END);
        } else if (expectedServiceCall == 2) {
            verify(schemeRegularityService, times(1)).getReadingSubmissionRateByDepartment(value, START, END);
        } else if (expectedServiceCall == 3) {
            verify(schemeRegularityService, times(1)).getReadingSubmissionRateByLgdForChildRegions(value, START, END);
        } else {
            verify(schemeRegularityService, times(1)).getReadingSubmissionRateByDepartmentForChildRegions(value, START, END);
        }
    }

    @Test
    void getReadingSubmissionRate_withBothParentIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/reading-submission-rate")
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
    void getReadingSubmissionRate_invalidScope_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/reading-submission-rate")
                        .param("scope", "invalid")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getPeriodicSchemeRegularity_withLgdId_wrapsResponse() throws Exception {
        when(schemeRegularityService.getPeriodicSchemeRegularityByLgdId(101, START, END, PeriodScale.DAY))
                .thenReturn(periodicSchemeRegularityResponse());

        mockMvc.perform(get(BASE + "/scheme-regularity/periodic")
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
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", "year")
                        .param("lgd_id", "101"))
                .andExpect(status().isBadRequest())
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

    private static Stream<Arguments> readingSubmissionValidRoutes() {
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

    private static ReadingSubmissionRateResponse readingSubmissionResponse() {
        return ReadingSubmissionRateResponse.builder()
                .readingSubmissionRate(BigDecimal.valueOf(0.84))
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
}

