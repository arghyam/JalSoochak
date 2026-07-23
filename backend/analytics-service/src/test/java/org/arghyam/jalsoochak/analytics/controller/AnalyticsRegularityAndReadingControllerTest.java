package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.dto.response.AverageSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.HourlySubmissionActivityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicSchemeRegularityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ReadingSubmissionRateResponse;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.arghyam.jalsoochak.analytics.service.SubmissionActivityService;
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
    private static final int TENANT_ID = 12;
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 31);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SchemeRegularityService schemeRegularityService;

    @MockBean
    private SubmissionActivityService submissionActivityService;

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

    @ParameterizedTest
    @MethodSource("readingSubmissionValidRoutes")
    void getReadingSubmissionRate_validScopeAndIdCombinations(
            String scope,
            String idParam,
            String idValue,
            int expectedServiceCall) throws Exception {
        Mockito.reset(schemeRegularityService);
        when(schemeRegularityService.getReadingSubmissionRateByLgd(any(), any(), any(), any()))
                .thenReturn(readingSubmissionResponse());
        when(schemeRegularityService.getReadingSubmissionRateByDepartment(any(), any(), any(), any()))
                .thenReturn(readingSubmissionResponse());
        when(schemeRegularityService.getReadingSubmissionRateByLgdForChildRegions(any(), any(), any(), any()))
                .thenReturn(readingSubmissionResponse());
        when(schemeRegularityService.getReadingSubmissionRateByDepartmentForChildRegions(any(), any(), any(), any()))
                .thenReturn(readingSubmissionResponse());

        mockMvc.perform(get(BASE + "/reading-submission-rate")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("scope", scope)
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param(idParam, idValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.readingSubmissionRate").exists());

        int value = Integer.parseInt(idValue);
        if (expectedServiceCall == 1) {
            verify(schemeRegularityService, times(1)).getReadingSubmissionRateByLgd(TENANT_ID, value, START, END);
        } else if (expectedServiceCall == 2) {
            verify(schemeRegularityService, times(1)).getReadingSubmissionRateByDepartment(TENANT_ID, value, START, END);
        } else if (expectedServiceCall == 3) {
            verify(schemeRegularityService, times(1)).getReadingSubmissionRateByLgdForChildRegions(TENANT_ID, value, START, END);
        } else {
            verify(schemeRegularityService, times(1))
                    .getReadingSubmissionRateByDepartmentForChildRegions(TENANT_ID, value, START, END);
        }
    }

    @Test
    void getReadingSubmissionRate_withBothParentIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/reading-submission-rate")
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
    void getReadingSubmissionRate_invalidScope_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/reading-submission-rate")
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
    void getReadingSubmissionRate_withoutTenantId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/reading-submission-rate")
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

    @Test
    void getReadingSubmissionRate_serviceThrows_returnsServerError() throws Exception {
        when(schemeRegularityService.getReadingSubmissionRateByLgd(TENANT_ID, 101, START, END))
                .thenThrow(new RuntimeException("unexpected"));

        mockMvc.perform(get(BASE + "/reading-submission-rate")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("scope", "current")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getHourlySubmissionActivity_tenantWide_wrapsResponse() throws Exception {
        when(submissionActivityService.getHourlySubmissionActivity(TENANT_ID, null, null, START, END))
                .thenReturn(hourlyResponse());

        mockMvc.perform(get(BASE + "/submission-activity/hourly")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenantId").value(TENANT_ID))
                .andExpect(jsonPath("$.data.hourlyActivity[0].submissionCount").value(3))
                .andExpect(jsonPath("$.data.hourlyActivity[0].distinctSchemeCount").value(2));

        verify(submissionActivityService, times(1))
                .getHourlySubmissionActivity(TENANT_ID, null, null, START, END);
    }

    @Test
    void getHourlySubmissionActivity_withLgdId_routesToRegionScope() throws Exception {
        when(submissionActivityService.getHourlySubmissionActivity(TENANT_ID, 101, null, START, END))
                .thenReturn(hourlyResponse());

        mockMvc.perform(get(BASE + "/submission-activity/hourly")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("lgd_id", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(submissionActivityService, times(1))
                .getHourlySubmissionActivity(TENANT_ID, 101, null, START, END);
    }

    @Test
    void getHourlySubmissionActivity_serviceValidationFailure_returnsBadRequest() throws Exception {
        when(submissionActivityService.getHourlySubmissionActivity(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("Provide either lgd_id or department_id, not both"));

        mockMvc.perform(get(BASE + "/submission-activity/hourly")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("lgd_id", "101")
                        .param("department_id", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getHourlySubmissionActivity_withoutTenantId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/submission-activity/hourly")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getHourlySubmissionActivity_serviceThrows_returnsServerError() throws Exception {
        when(submissionActivityService.getHourlySubmissionActivity(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("unexpected"));

        mockMvc.perform(get(BASE + "/submission-activity/hourly")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    private static HourlySubmissionActivityResponse hourlyResponse() {
        return HourlySubmissionActivityResponse.builder()
                .tenantId(TENANT_ID)
                .startDate(START)
                .endDate(END)
                .hourlyActivity(List.of(
                        HourlySubmissionActivityResponse.HourlyBucket.builder()
                                .hourStart(java.time.LocalDateTime.of(2026, 1, 1, 9, 0))
                                .submissionCount(3L)
                                .distinctSchemeCount(2)
                                .build()))
                .build();
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

