package org.arghyam.jalsoochak.analytics.controller.regularity;

import org.arghyam.jalsoochak.analytics.dto.response.NonSubmissionReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.ReadingSubmissionRateResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SubmissionStatusSummaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserNonSubmissionReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserSubmissionStatusResponse;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper;
import org.arghyam.jalsoochak.analytics.helper.DefaultAnalyticsDateWindowProvider;
import org.arghyam.jalsoochak.analytics.repository.FactMeterReadingRepository;
import org.arghyam.jalsoochak.analytics.service.AuthenticatedRequestContextService;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsSubmissionController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsSubmissionControllerTest {

    private static final String BASE = "/api/v1/analytics";
    private static final int TENANT_ID = 12;
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 31);
    private static final UUID USER_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SchemeRegularityService schemeRegularityService;
    @MockBean
    private FactMeterReadingRepository meterReadingRepository;

    @MockBean
    private DefaultAnalyticsDateWindowProvider defaultAnalyticsDateWindowProvider;

    @MockBean
    private AuthenticatedRequestContextService authenticatedRequestContextService;

    @BeforeEach
    void stubDefaultWindow() {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Kolkata");
        LocalDate end = LocalDate.now(zone).minusDays(1);
        LocalDate start = end.minusDays(29);
        when(defaultAnalyticsDateWindowProvider.defaultWindow())
                .thenReturn(new DefaultAnalyticsDateWindowProvider.DateWindow(start, end));
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
    void getMeterReadings_withTenantAndScheme_andDates_routesToTenantSchemeDateBranch() throws Exception {
        when(meterReadingRepository.findByTenantIdAndSchemeIdAndReadingDateBetween(10, 11, END, START))
                .thenReturn(List.of());

        mockMvc.perform(get(BASE + "/meter-readings")
                        .param("tenant_id", "10")
                        .param("scheme_id", "11")
                        .param("start_date", END.toString())
                        .param("end_date", START.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        verify(meterReadingRepository, times(1))
                .findByTenantIdAndSchemeIdAndReadingDateBetween(10, 11, END, START);
    }

    @Test
    void getMeterReadings_withTenantAndScheme_withoutDates_defaultsToYesterdayAnd30DayWindow() throws Exception {
        // Controller defaults are anchored to "yesterday" in the configured zone (Asia/Kolkata by default)
        // to keep the window stable across the daily (midnight) warm-cache cycle.
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Kolkata");
        LocalDate defaultStart = LocalDate.now(zone).minusDays(1);
        LocalDate defaultEnd = defaultStart.minusDays(29);
        when(meterReadingRepository.findByTenantIdAndSchemeIdAndReadingDateBetween(10, 11, defaultStart, defaultEnd))
                .thenReturn(List.of());

        mockMvc.perform(get(BASE + "/meter-readings")
                        .param("tenant_id", "10")
                        .param("scheme_id", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());

        verify(meterReadingRepository, times(1))
                .findByTenantIdAndSchemeIdAndReadingDateBetween(10, 11, defaultStart, defaultEnd);
    }

    @Test
    void getMeterReadings_missingTenantId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/meter-readings").param("scheme_id", "11"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(meterReadingRepository);
    }

    @Test
    void getMeterReadings_missingSchemeId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/meter-readings").param("tenant_id", "10"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(meterReadingRepository);
    }

    @Test
    void getMeterReadings_onlyOneDateProvided_returnsBadRequestWrapper() throws Exception {
        mockMvc.perform(get(BASE + "/meter-readings")
                        .param("tenant_id", "10")
                        .param("scheme_id", "11")
                        .param("start_date", START.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));

        verifyNoInteractions(meterReadingRepository);
    }

    @ParameterizedTest
    @MethodSource("nonSubmissionValidRoutes")
    void getNonSubmissionReasons_validRoutes(String paramName, String paramValue, boolean lgdRoute) throws Exception {
        if (lgdRoute) {
            when(schemeRegularityService.getNonSubmissionReasonSchemeCountByLgd(TENANT_ID, Integer.parseInt(paramValue), START, END))
                    .thenReturn(nonSubmissionReasonResponse());
        } else {
            when(schemeRegularityService.getNonSubmissionReasonSchemeCountByDepartment(TENANT_ID, Integer.parseInt(paramValue), START, END))
                    .thenReturn(nonSubmissionReasonResponse());
        }

        mockMvc.perform(get(BASE + "/non-submission-reasons")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param(paramName, paramValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").exists());

        if (lgdRoute) {
            verify(schemeRegularityService, times(1))
                    .getNonSubmissionReasonSchemeCountByLgd(TENANT_ID, Integer.parseInt(paramValue), START, END);
        } else {
            verify(schemeRegularityService, times(1))
                    .getNonSubmissionReasonSchemeCountByDepartment(TENANT_ID, Integer.parseInt(paramValue), START, END);
        }
    }

    @Test
    void getNonSubmissionReasons_withBothIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/non-submission-reasons")
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
    void getNonSubmissionReasons_withNoId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/non-submission-reasons")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getNonSubmissionReasons_withoutTenantId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/non-submission-reasons")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getNonSubmissionReasons_whenServiceThrows_returnsInternalServerErrorWrapper() throws Exception {
        when(schemeRegularityService.getNonSubmissionReasonSchemeCountByLgd(TENANT_ID, 101, START, END))
                .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get(BASE + "/non-submission-reasons")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getNonSubmissionReasonsByUser_validRequest_routesToUserService() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new AnalyticsControllerHelper.AuthenticatedUserRef(null, USER_UUID, 12));
        when(schemeRegularityService.getNonSubmissionReasonSchemeCountByUserUuid(12, USER_UUID, START, END))
                .thenReturn(userNonSubmissionReasonResponse());

        mockMvc.perform(get(BASE + "/non-submission-reasons/user")
                        .principal(buildJwtAuthentication())
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(11));

        verify(schemeRegularityService, times(1)).getNonSubmissionReasonSchemeCountByUserUuid(12, USER_UUID, START, END);
    }

    @Test
    void getNonSubmissionReasonsByUser_withNumericSubject_routesToUserIdService() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new AnalyticsControllerHelper.AuthenticatedUserRef(11, null, 12));
        when(schemeRegularityService.getNonSubmissionReasonSchemeCountByUser(12, 11, START, END))
                .thenReturn(userNonSubmissionReasonResponse());

        mockMvc.perform(get(BASE + "/non-submission-reasons/user")
                        .principal(buildJwtAuthenticationWithNumericSubject())
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(11));

        verify(schemeRegularityService, times(1)).getNonSubmissionReasonSchemeCountByUser(12, 11, START, END);
    }

    @Test
    void getSubmissionStatusByUser_validRequest_routesToUserService() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new AnalyticsControllerHelper.AuthenticatedUserRef(null, USER_UUID, 12));
        when(schemeRegularityService.getSubmissionStatusByUserUuid(12, USER_UUID, START, END))
                .thenReturn(userSubmissionStatusResponse());

        mockMvc.perform(get(BASE + "/submission-status/user")
                        .principal(buildJwtAuthentication())
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(11));

        verify(schemeRegularityService, times(1)).getSubmissionStatusByUserUuid(12, USER_UUID, START, END);
    }

    @Test
    void getSubmissionStatusByUser_withNumericSubject_routesToUserIdService() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new AnalyticsControllerHelper.AuthenticatedUserRef(11, null, 12));
        when(schemeRegularityService.getSubmissionStatusByUser(12, 11, START, END))
                .thenReturn(userSubmissionStatusResponse());

        mockMvc.perform(get(BASE + "/submission-status/user")
                        .principal(buildJwtAuthenticationWithNumericSubject())
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(11));

        verify(schemeRegularityService, times(1)).getSubmissionStatusByUser(12, 11, START, END);
    }

    @Test
    void getNonSubmissionReasonsByUser_whenServiceThrowsIllegalArgument_returnsBadRequestWithMessage() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new AnalyticsControllerHelper.AuthenticatedUserRef(null, USER_UUID, 12));
        when(schemeRegularityService.getNonSubmissionReasonSchemeCountByUserUuid(12, USER_UUID, START, END))
                .thenThrow(new IllegalArgumentException("No user found for uuid: " + USER_UUID));

        mockMvc.perform(get(BASE + "/non-submission-reasons/user")
                        .principal(buildJwtAuthentication())
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No user found for uuid: " + USER_UUID));
    }

    @Test
    void getSubmissionStatusByUser_whenServiceThrowsIllegalArgument_returnsBadRequestWithMessage() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new AnalyticsControllerHelper.AuthenticatedUserRef(null, USER_UUID, 12));
        when(schemeRegularityService.getSubmissionStatusByUserUuid(12, USER_UUID, START, END))
                .thenThrow(new IllegalArgumentException("No user found for uuid: " + USER_UUID));

        mockMvc.perform(get(BASE + "/submission-status/user")
                        .principal(buildJwtAuthentication())
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No user found for uuid: " + USER_UUID));
    }

    @Test
    void getSubmissionStatusSummary_withLgdId_routesToLgdService() throws Exception {
        when(schemeRegularityService.getSubmissionStatusSummaryByLgd(TENANT_ID, 100, START, END))
                .thenReturn(SubmissionStatusSummaryResponse.builder()
                        .schemeCount(2)
                        .compliantSubmissionCount(5)
                        .anomalousSubmissionCount(0)
                        .build());

        mockMvc.perform(get(BASE + "/submission-status")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("lgd_id", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.schemeCount").value(2))
                .andExpect(jsonPath("$.data.compliantSubmissionCount").value(5))
                .andExpect(jsonPath("$.data.anomalousSubmissionCount").value(0));

        verify(schemeRegularityService, times(1)).getSubmissionStatusSummaryByLgd(TENANT_ID, 100, START, END);
    }

    @Test
    void getSubmissionStatusSummary_withDepartmentId_routesToDepartmentService() throws Exception {
        when(schemeRegularityService.getSubmissionStatusSummaryByDepartment(TENANT_ID, 200, START, END))
                .thenReturn(SubmissionStatusSummaryResponse.builder()
                        .schemeCount(2)
                        .compliantSubmissionCount(5)
                        .anomalousSubmissionCount(0)
                        .build());

        mockMvc.perform(get(BASE + "/submission-status")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("department_id", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(schemeRegularityService, times(1)).getSubmissionStatusSummaryByDepartment(TENANT_ID, 200, START, END);
    }

    @Test
    void getSubmissionStatusSummary_withBothIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/submission-status")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("lgd_id", "100")
                        .param("department_id", "200"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getSubmissionStatusSummary_withNoScopeId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/submission-status")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getSubmissionStatusSummary_withoutTenantId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/submission-status")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("lgd_id", "100"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSubmissionStatusSummary_whenServiceThrows_returnsInternalServerErrorWrapper() throws Exception {
        when(schemeRegularityService.getSubmissionStatusSummaryByLgd(TENANT_ID, 100, START, END))
                .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get(BASE + "/submission-status")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("lgd_id", "100"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    private static Stream<Arguments> readingSubmissionValidRoutes() {
        return Stream.of(
                Arguments.of("current", "parent_lgd_id", "101", 1),
                Arguments.of("current", "parent_department_id", "201", 2),
                Arguments.of("child", "parent_lgd_id", "101", 3),
                Arguments.of("child", "parent_department_id", "201", 4)
        );
    }

    private static ReadingSubmissionRateResponse readingSubmissionResponse() {
        return ReadingSubmissionRateResponse.builder()
                .readingSubmissionRate(BigDecimal.valueOf(0.84))
                .build();
    }

    private static Stream<Arguments> nonSubmissionValidRoutes() {
        return Stream.of(
                Arguments.of("parent_lgd_id", "101", true),
                Arguments.of("parent_department_id", "201", false)
        );
    }

    private static JwtAuthenticationToken buildJwtAuthentication() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject(USER_UUID.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("USER_TYPE_SECTION_OFFICER")));
    }

    private static JwtAuthenticationToken buildJwtAuthenticationWithNumericSubject() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("11")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("USER_TYPE_SECTION_OFFICER")));
    }

    private static NonSubmissionReasonSchemeCountResponse nonSubmissionReasonResponse() {
        return NonSubmissionReasonSchemeCountResponse.builder()
                .childRegionCount(0)
                .nonSubmissionReasonSchemeCount(Map.of("operator_absent", 0))
                .build();
    }

    private static UserNonSubmissionReasonSchemeCountResponse userNonSubmissionReasonResponse() {
        return UserNonSubmissionReasonSchemeCountResponse.builder()
                .userId(11)
                .startDate(START)
                .endDate(END)
                .schemeCount(2)
                .nonSubmissionReasonSchemeCount(Map.of("app_issue", 1))
                .dailyNonSubmissionReasonDistribution(List.of(
                        UserNonSubmissionReasonSchemeCountResponse.DailyNonSubmissionReasonDistribution.builder()
                                .date(START)
                                .nonSubmissionReasonSchemeCount(Map.of("app_issue", 1))
                                .build()
                ))
                .build();
    }

    private static UserSubmissionStatusResponse userSubmissionStatusResponse() {
        return UserSubmissionStatusResponse.builder()
                .userId(11)
                .startDate(START)
                .endDate(END)
                .schemeCount(2)
                .compliantSubmissionCount(4)
                .anomalousSubmissionCount(1)
                .dailySubmissionSchemeDistribution(List.of(
                        UserSubmissionStatusResponse.DailySubmissionSchemeDistribution.builder()
                                .date(START)
                                .submittedSchemeCount(1)
                                .build()
                ))
                .build();
    }
}
