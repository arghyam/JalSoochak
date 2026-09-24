package org.arghyam.jalsoochak.analytics.controller.incident;

import org.arghyam.jalsoochak.analytics.dto.response.AnomalyListItemDto;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.service.AnomalyQueryService;
import org.arghyam.jalsoochak.analytics.service.AuthenticatedRequestContextService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsAnomalyController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsAnomalyControllerTest {

    private static final String BASE = "/api/v1/analytics";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private AnomalyQueryService anomalyQueryService;
    @MockBean
    private AuthenticatedRequestContextService authenticatedRequestContextService;

    @Test
    void getAnomalies_withExplicitDatesAndType_returnsExpectedShape() throws Exception {
        LocalDate start = LocalDate.of(2026, 3, 1);
        LocalDate end = LocalDate.of(2026, 3, 31);
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));

        AnomalyListItemDto a1 = AnomalyListItemDto.builder()
                .id(11L)
                .uuid("uuid-1")
                .type("2")
                .userId(999) // note: not the same as input mapped user id
                .schemeId(101)
                .tenantId(10)
                .statusCode(1)
                .createdAt(java.time.LocalDateTime.of(2026, 3, 15, 10, 0, 0, 0))
                .schemeName("Mapped Scheme")
                .build();

        Page<AnomalyListItemDto> anomalyPage = new PageImpl<>(List.of(a1), PageRequest.of(0, 10), 25);
        when(anomalyQueryService.getAnomaliesForUserSchemes(
                eq(10), eq(9001), eq(start), eq(end), eq("2"), eq("Mapped"), eq(1), any(Pageable.class)))
                .thenReturn(anomalyPage);

        mockMvc.perform(get(BASE + "/anomalies")
                        .principal(buildJwtAuthentication())
                        .param("start_date", start.toString())
                        .param("end_date", end.toString())
                        .param("anomaly_type", "2")
                        .param("scheme_name", "Mapped")
                        .param("status", "1")
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
                .andExpect(jsonPath("$.anomalies[0].status").value("In-Progress"))
                .andExpect(jsonPath("$.anomalies[0].scheme_name").value("Mapped Scheme"));

        verify(anomalyQueryService, times(1)).getAnomaliesForUserSchemes(
                eq(10), eq(9001), eq(start), eq(end), eq("2"), eq("Mapped"), eq(1), any(Pageable.class));
    }

    @Test
    void getAnomalies_withoutDates_defaultsHandledInService() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));

        Page<AnomalyListItemDto> empty = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(anomalyQueryService.getAnomaliesForUserSchemes(
                eq(10), eq(9001), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(empty);

        mockMvc.perform(get(BASE + "/anomalies")
                        .principal(buildJwtAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.total_count").value(0))
                .andExpect(jsonPath("$.anomalies").isArray());

        verify(anomalyQueryService, times(1)).getAnomaliesForUserSchemes(
                eq(10), eq(9001), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void getAnomalies_whenServiceThrowsIllegalArg_returnsBadRequest() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(
                        9001, null, 10));
        when(anomalyQueryService.getAnomaliesForUserSchemes(
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("invalid range"));

        mockMvc.perform(get(BASE + "/anomalies")
                        .principal(buildJwtAuthentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getAnomalies_whenServiceThrows_returnsInternalServerError() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(
                        9001, null, 10));
        when(anomalyQueryService.getAnomaliesForUserSchemes(
                any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("db error"));

        mockMvc.perform(get(BASE + "/anomalies")
                        .principal(buildJwtAuthentication()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void getAnomalyStatuses_returnsCodesAndLabels() throws Exception {
        mockMvc.perform(get(BASE + "/anomalies/statuses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].code").value(0))
                .andExpect(jsonPath("$.data[0].label").value("Unresolved"))
                .andExpect(jsonPath("$.data[1].code").value(1))
                .andExpect(jsonPath("$.data[1].label").value("In-Progress"))
                .andExpect(jsonPath("$.data[2].code").value(2))
                .andExpect(jsonPath("$.data[2].label").value("Resolved"));
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
