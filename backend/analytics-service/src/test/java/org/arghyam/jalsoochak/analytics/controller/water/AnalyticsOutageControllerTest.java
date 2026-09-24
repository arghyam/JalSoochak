package org.arghyam.jalsoochak.analytics.controller.water;

import org.arghyam.jalsoochak.analytics.dto.response.OutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicOutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserOutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper;
import org.arghyam.jalsoochak.analytics.service.AuthenticatedRequestContextService;
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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsOutageController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsOutageControllerTest {

    private static final String BASE = "/api/v1/analytics";
    private static final UUID USER_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final int TENANT_ID = 12;
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 31);

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SchemeRegularityService schemeRegularityService;

    @MockBean
    private AuthenticatedRequestContextService authenticatedRequestContextService;

    @ParameterizedTest
    @MethodSource("outageValidRoutes")
    void getOutageReasons_validRoutes(String paramName, String paramValue, boolean lgdRoute) throws Exception {
        if (lgdRoute) {
            when(schemeRegularityService.getOutageReasonSchemeCountByLgd(TENANT_ID, Integer.parseInt(paramValue), START, END))
                    .thenReturn(outageReasonResponse());
        } else {
            when(schemeRegularityService.getOutageReasonSchemeCountByDepartment(TENANT_ID, Integer.parseInt(paramValue), START, END))
                    .thenReturn(outageReasonResponse());
        }

        mockMvc.perform(get(BASE + "/outage-reasons")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param(paramName, paramValue))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").exists());

        if (lgdRoute) {
            verify(schemeRegularityService, times(1))
                    .getOutageReasonSchemeCountByLgd(TENANT_ID, Integer.parseInt(paramValue), START, END);
        } else {
            verify(schemeRegularityService, times(1))
                    .getOutageReasonSchemeCountByDepartment(TENANT_ID, Integer.parseInt(paramValue), START, END);
        }
    }

    @Test
    void getOutageReasons_withBothIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/outage-reasons")
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
    void getOutageReasons_withNoId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/outage-reasons")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getOutageReasons_withoutTenantId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/outage-reasons")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getOutageReasons_whenServiceThrows_returnsInternalServerErrorWrapper() throws Exception {
        when(schemeRegularityService.getOutageReasonSchemeCountByLgd(TENANT_ID, 101, START, END))
                .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get(BASE + "/outage-reasons")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("parent_lgd_id", "101"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getPeriodicOutageReasons_withLgdId_routesToLgdService() throws Exception {
        when(schemeRegularityService.getPeriodicOutageReasonSchemeCountByLgdId(
                TENANT_ID, 101, START, END, PeriodScale.DAY))
                .thenReturn(PeriodicOutageReasonSchemeCountResponse.builder()
                        .scale("day")
                        .periodCount(0)
                        .metrics(List.of())
                        .build());

        mockMvc.perform(get(BASE + "/outage-reasons/periodic")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", "day")
                        .param("lgd_id", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.scale").value("day"));

        verify(schemeRegularityService, times(1))
                .getPeriodicOutageReasonSchemeCountByLgdId(TENANT_ID, 101, START, END, PeriodScale.DAY);
    }

    @Test
    void getPeriodicOutageReasons_withBothIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/outage-reasons/periodic")
                        .param("tenant_id", String.valueOf(TENANT_ID))
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
    void getPeriodicOutageReasons_whenServiceThrows_returnsInternalServerErrorWrapper() throws Exception {
        when(schemeRegularityService.getPeriodicOutageReasonSchemeCountByLgdId(
                TENANT_ID, 101, START, END, PeriodScale.DAY))
                .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get(BASE + "/outage-reasons/periodic")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("scale", "day")
                        .param("lgd_id", "101"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getOutageReasonsByUser_validRequest_routesToUserService() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new AnalyticsControllerHelper.AuthenticatedUserRef(null, USER_UUID, 12));
        when(schemeRegularityService.getOutageReasonSchemeCountByUserUuid(12, USER_UUID, START, END))
                .thenReturn(userOutageReasonResponse());

        mockMvc.perform(get(BASE + "/outage-reasons/user")
                        .principal(buildJwtAuthentication())
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(11));

        verify(schemeRegularityService, times(1)).getOutageReasonSchemeCountByUserUuid(12, USER_UUID, START, END);
    }

    @Test
    void getOutageReasonsByUser_withNumericUserIdClaim_routesToUserIdService() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new AnalyticsControllerHelper.AuthenticatedUserRef(11, null, 12));
        when(schemeRegularityService.getOutageReasonSchemeCountByUser(12, 11, START, END))
                .thenReturn(userOutageReasonResponse());

        mockMvc.perform(get(BASE + "/outage-reasons/user")
                        .principal(buildJwtAuthenticationWithUserIdClaim())
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(11));

        verify(schemeRegularityService, times(1)).getOutageReasonSchemeCountByUser(12, 11, START, END);
    }

    @Test
    void getOutageReasonsByUser_whenServiceThrowsIllegalArgument_returnsBadRequestWithMessage() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new AnalyticsControllerHelper.AuthenticatedUserRef(null, USER_UUID, 12));
        when(schemeRegularityService.getOutageReasonSchemeCountByUserUuid(12, USER_UUID, START, END))
                .thenThrow(new IllegalArgumentException("No user found for uuid: " + USER_UUID));

        mockMvc.perform(get(BASE + "/outage-reasons/user")
                        .principal(buildJwtAuthentication())
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("No user found for uuid: " + USER_UUID));
    }

    private static Stream<Arguments> outageValidRoutes() {
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

    private static JwtAuthenticationToken buildJwtAuthenticationWithUserIdClaim() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("not-a-uuid")
                .claim("user_id", 11)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("USER_TYPE_SECTION_OFFICER")));
    }

    private static OutageReasonSchemeCountResponse outageReasonResponse() {
        return OutageReasonSchemeCountResponse.builder()
                .childRegionCount(0)
                .outageReasonSchemeCount(Map.of("power_failure", 0))
                .build();
    }

    private static UserOutageReasonSchemeCountResponse userOutageReasonResponse() {
        return UserOutageReasonSchemeCountResponse.builder()
                .userId(11)
                .startDate(START)
                .endDate(END)
                .schemeCount(2)
                .outageReasonSchemeCount(Map.of("draught", 1))
                .dailyOutageReasonDistribution(List.of(
                        UserOutageReasonSchemeCountResponse.DailyOutageReasonDistribution.builder()
                                .date(START)
                                .outageReasonSchemeCount(Map.of("draught", 1, "no_electricity", 0, "motor_burnt", 0))
                                .build()
                ))
                .build();
    }
}
