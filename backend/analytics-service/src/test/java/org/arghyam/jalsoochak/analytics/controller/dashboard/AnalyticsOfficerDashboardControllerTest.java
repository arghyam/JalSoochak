package org.arghyam.jalsoochak.analytics.controller.dashboard;

import org.arghyam.jalsoochak.analytics.dto.response.OperatorAttendanceDayItemDto;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.service.AuthenticatedRequestContextService;
import org.arghyam.jalsoochak.analytics.service.OperatorAttendanceQueryService;
import org.arghyam.jalsoochak.analytics.service.UserAlertTotalsService;
import org.junit.jupiter.api.Test;
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
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsOfficerDashboardController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsOfficerDashboardControllerTest {

    private static final String BASE = "/api/v1/analytics";
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 31);

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private OperatorAttendanceQueryService operatorAttendanceQueryService;
    @MockBean
    private UserAlertTotalsService userAlertTotalsService;
    @MockBean
    private AuthenticatedRequestContextService authenticatedRequestContextService;

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
    void getUserAlertTotals_returnsExpectedShape() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));
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
                        .principal(buildJwtAuthentication())
                        .param("start_date", start.toString())
                        .param("end_date", end.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalEscalationCount").value(12))
                .andExpect(jsonPath("$.data.totalAnomalyCount").value(7))
                .andExpect(jsonPath("$.data.totalMappedSchemeCount").value(5))
                .andExpect(jsonPath("$.data.totalWaterSupplied").value(143200));
    }

    @Test
    void getOperatorAttendanceDayWise_whenServiceThrows_returnsInternalServerError() throws Exception {
        when(operatorAttendanceQueryService.getDayWiseAttendance(any(), any(), any()))
                .thenThrow(new RuntimeException("unexpected"));

        mockMvc.perform(get(BASE + "/operator-attendance")
                        .param("uuid", "11111111-1111-1111-1111-111111111111")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getUserAlertTotals_whenServiceThrowsIllegalArg_returnsBadRequest() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));
        when(userAlertTotalsService.getTotals(any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("invalid"));

        mockMvc.perform(get(BASE + "/officer/dashboard")
                        .principal(buildJwtAuthentication())
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getUserAlertTotals_whenServiceThrows_returnsInternalServerError() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));
        when(userAlertTotalsService.getTotals(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db error"));

        mockMvc.perform(get(BASE + "/officer/dashboard")
                        .principal(buildJwtAuthentication())
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getUserAlertTotals_ignoresCallerSuppliedIdentity() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));
        when(userAlertTotalsService.getTotals(eq(10), eq(9001), any(), any()))
                .thenReturn(org.arghyam.jalsoochak.analytics.dto.response.UserAlertTotalsResponse.builder()
                        .totalEscalationCount(1L)
                        .totalAnomalyCount(1L)
                        .totalMappedSchemeCount(1)
                        .totalWaterSupplied(1L)
                        .build());

        mockMvc.perform(get(BASE + "/officer/dashboard")
                        .principal(buildJwtAuthentication())
                        .param("tenant_id", "999")
                        .param("user_id", "424242")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isOk());

        verify(userAlertTotalsService, times(1)).getTotals(eq(10), eq(9001), any(), any());
        verify(userAlertTotalsService, never()).getTotals(eq(999), any(), any(), any());
        verify(userAlertTotalsService, never()).getTotals(any(), eq(424242), any(), any());
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
