package org.arghyam.jalsoochak.analytics.controller.incident;

import org.arghyam.jalsoochak.analytics.dto.response.EscalationListItemDto;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.service.AuthenticatedRequestContextService;
import org.arghyam.jalsoochak.analytics.service.EscalationQueryService;
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
import java.util.UUID;

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

@WebMvcTest(controllers = AnalyticsEscalationController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsEscalationControllerTest {

    private static final String BASE = "/api/v1/analytics";

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private EscalationQueryService escalationQueryService;
    @MockBean
    private AuthenticatedRequestContextService authenticatedRequestContextService;

    @Test
    void getEscalationsPaginated_returnsExpectedShape() throws Exception {
        LocalDate start = LocalDate.of(2026, 2, 1);
        LocalDate end = LocalDate.of(2026, 3, 1);
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));

        EscalationListItemDto e1 = EscalationListItemDto.builder()
                .id(1L)
                .tenantId(10)
                .schemeId(101)
                .userId(9001)
                .escalationType("2")
                .message("test")
                .resolutionStatusCode(0)
                .createdAt(LocalDateTime.of(2026, 2, 15, 10, 0))
                .schemeName("Test Scheme")
                .build();

        Page<EscalationListItemDto> page = new PageImpl<>(List.of(e1), PageRequest.of(0, 5), 12);
        when(escalationQueryService.getEscalations(
                eq(10),
                eq(9001),
                eq("2"),
                eq(101),
                eq("Test"),
                eq(0),
                eq(start),
                eq(end),
                any(Pageable.class)
        )).thenReturn(page);

        mockMvc.perform(get(BASE + "/escalations")
                        .principal(buildJwtAuthentication())
                        .param("page_number", "1")
                        .param("limit", "5")
                        .param("escalation_type", "2")
                        .param("scheme_id", "101")
                        .param("scheme_name", "Test")
                        .param("resolution_status", "0")
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
                .andExpect(jsonPath("$.escalations[0].resolution_status").value("Unresolved"))
                .andExpect(jsonPath("$.escalations[0].scheme_name").value("Test Scheme"));
    }

    @Test
    void getEscalationsPaginated_withoutPageAndLimit_defaultsApplied() throws Exception {
        LocalDate start = LocalDate.of(2026, 2, 1);
        LocalDate end = LocalDate.of(2026, 3, 1);
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));

        Page<EscalationListItemDto> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(escalationQueryService.getEscalations(
                eq(10),
                eq(9001),
                eq("2"),
                eq(101),
                eq("Test"),
                eq(0),
                eq(start),
                eq(end),
                any(Pageable.class)
        )).thenReturn(page);

        mockMvc.perform(get(BASE + "/escalations")
                        .principal(buildJwtAuthentication())
                        .param("escalation_type", "2")
                        .param("scheme_id", "101")
                        .param("scheme_name", "Test")
                        .param("resolution_status", "0")
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
    void getEscalationsPaginated_whenPageNumberInvalid_returnsBadRequest() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));

        mockMvc.perform(get(BASE + "/escalations")
                        .principal(buildJwtAuthentication())
                        .param("page_number", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.total_count").value(0))
                .andExpect(jsonPath("$.escalations").isArray());
    }

    @Test
    void getEscalationsPaginated_whenLimitInvalid_returnsBadRequest() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));

        mockMvc.perform(get(BASE + "/escalations")
                        .principal(buildJwtAuthentication())
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.total_count").value(0))
                .andExpect(jsonPath("$.escalations").isArray());
    }

    @Test
    void getEscalationsPaginated_whenTenantIdMissingInAuthRef_returnsBadRequest() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, null));

        mockMvc.perform(get(BASE + "/escalations")
                        .principal(buildJwtAuthentication()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.total_count").value(0))
                .andExpect(jsonPath("$.escalations").isArray());
    }

    @Test
    void getEscalationsPaginated_whenUserIdNull_resolvesViaUuid() throws Exception {
        UUID uuid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(null, uuid, 10));
        when(authenticatedRequestContextService.resolveUserIdByUuid(eq(10), eq(uuid)))
                .thenReturn(9001);

        Page<EscalationListItemDto> page = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);
        when(escalationQueryService.getEscalations(
                eq(10), eq(9001), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get(BASE + "/escalations")
                        .principal(buildJwtAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.limit").value(10))
                .andExpect(jsonPath("$.total_count").value(0));

        verify(authenticatedRequestContextService, times(1))
                .resolveUserIdByUuid(eq(10), eq(uuid));
    }

    @Test
    void getEscalationsPaginated_whenServiceThrows_returnsServerError() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));
        when(escalationQueryService.getEscalations(
                eq(10), eq(9001), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenThrow(new RuntimeException("boom"));

        mockMvc.perform(get(BASE + "/escalations")
                        .principal(buildJwtAuthentication()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.total_count").value(0))
                .andExpect(jsonPath("$.escalations").isArray());
    }

    @Test
    void getEscalationStatuses_returnsCodesAndLabels() throws Exception {
        mockMvc.perform(get(BASE + "/escalations/statuses"))
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
