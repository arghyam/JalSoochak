package org.arghyam.jalsoochak.analytics.controller.scheme;

import org.arghyam.jalsoochak.analytics.dto.response.ContinuousSchemesResponse;
import org.arghyam.jalsoochak.analytics.dto.response.CriticalSchemesResponse;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.helper.DefaultAnalyticsDateWindowProvider;
import org.arghyam.jalsoochak.analytics.service.AuthenticatedRequestContextService;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsSchemeSegmentController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsSchemeSegmentControllerTest {

    private static final String BASE = "/api/v1/analytics";
    private static final int TENANT_ID = 12;
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 31);

    @Autowired
    private MockMvc mockMvc;
    @MockBean
    private SchemeRegularityService schemeRegularityService;
    @MockBean
    private AuthenticatedRequestContextService authenticatedRequestContextService;
    @MockBean
    private DefaultAnalyticsDateWindowProvider defaultAnalyticsDateWindowProvider;

    @Test
    void getCriticalSchemes_defaultCountOnly_routesToLgdService() throws Exception {
        when(schemeRegularityService.getCriticalSchemesByLgd(eq(TENANT_ID), eq(101), eq(false), isNull(), isNull()))
                .thenReturn(CriticalSchemesResponse.builder()
                        .criticalSchemeCount(3L)
                        .list(false)
                        .page(null)
                        .limit(null)
                        .schemes(null)
                        .build());

        mockMvc.perform(get(BASE + "/critical-schemes")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("lgd_id", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.criticalSchemeCount").value(3))
                .andExpect(jsonPath("$.data.list").value(false))
                .andExpect(jsonPath("$.data.schemes").value(nullValue()));

        verify(schemeRegularityService, times(1))
                .getCriticalSchemesByLgd(eq(TENANT_ID), eq(101), eq(false), isNull(), isNull());
        verify(schemeRegularityService, never())
                .getCriticalSchemesByDepartment(any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void getCriticalSchemes_withListTrue_returnsCountAndList() throws Exception {
        when(schemeRegularityService.getCriticalSchemesByDepartment(eq(TENANT_ID), eq(201), eq(true), eq(1), eq(2)))
                .thenReturn(CriticalSchemesResponse.builder()
                        .criticalSchemeCount(3L)
                        .list(true)
                        .page(1)
                        .limit(2)
                        .schemes(List.of(
                                CriticalSchemesResponse.CriticalSchemeListItem.builder()
                                        .schemeId(101)
                                        .schemeName("Scheme A")
                                        .stateSchemeId(5001)
                                        .centreSchemeId(6001)
                                        .lastSuppliedDate(LocalDate.of(2026, 4, 1))
                                        .build(),
                                CriticalSchemesResponse.CriticalSchemeListItem.builder()
                                        .schemeId(102)
                                        .schemeName("Scheme B")
                                        .stateSchemeId(5002)
                                        .centreSchemeId(6002)
                                        .lastSuppliedDate(null)
                                        .build()
                        ))
                        .build());

        mockMvc.perform(get(BASE + "/critical-schemes")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("department_id", "201")
                        .param("list", "true")
                        .param("page", "1")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.criticalSchemeCount").value(3))
                .andExpect(jsonPath("$.data.list").value(true))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.limit").value(2))
                .andExpect(jsonPath("$.data.schemes").isArray())
                .andExpect(jsonPath("$.data.schemes[0].schemeId").value(101))
                .andExpect(jsonPath("$.data.schemes[0].schemeName").value("Scheme A"))
                .andExpect(jsonPath("$.data.schemes[0].stateSchemeId").value(5001))
                .andExpect(jsonPath("$.data.schemes[0].centreSchemeId").value(6001))
                .andExpect(jsonPath("$.data.schemes[0].lastSuppliedDate").value("2026-04-01"))
                .andExpect(jsonPath("$.data.schemes[1].schemeId").value(102))
                .andExpect(jsonPath("$.data.schemes[1].stateSchemeId").value(5002))
                .andExpect(jsonPath("$.data.schemes[1].centreSchemeId").value(6002))
                .andExpect(jsonPath("$.data.schemes[1].lastSuppliedDate").value(nullValue()));
    }

    @Test
    void getCriticalSchemes_withBothIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/critical-schemes")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("lgd_id", "101")
                        .param("department_id", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getCriticalSchemes_withNoId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/critical-schemes")
                        .param("tenant_id", String.valueOf(TENANT_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getCriticalSchemes_withListTrue_andInvalidPage_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/critical-schemes")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("lgd_id", "101")
                        .param("list", "true")
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getCriticalSchemesUser_defaultCountOnly_usesAuthRefUserId() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));
        when(schemeRegularityService.getCriticalSchemesByUser(eq(10), eq(9001), eq(false), isNull(), isNull()))
                .thenReturn(org.arghyam.jalsoochak.analytics.dto.response.CriticalSchemesResponse.builder()
                        .criticalSchemeCount(2L)
                        .list(false)
                        .page(null)
                        .limit(null)
                        .schemes(null)
                        .build());

        mockMvc.perform(get(BASE + "/critical-schemes/user")
                        .principal(buildJwtAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.criticalSchemeCount").value(2))
                .andExpect(jsonPath("$.data.list").value(false))
                .andExpect(jsonPath("$.data.schemes").value(nullValue()));

        verify(schemeRegularityService, times(1))
                .getCriticalSchemesByUser(eq(10), eq(9001), eq(false), isNull(), isNull());
        verify(schemeRegularityService, never()).getCriticalSchemesByUserUuid(any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void getCriticalSchemesUser_withListTrue_returnsCountAndList() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(9001, null, 10));
        when(schemeRegularityService.getCriticalSchemesByUser(eq(10), eq(9001), eq(true), eq(1), eq(2)))
                .thenReturn(org.arghyam.jalsoochak.analytics.dto.response.CriticalSchemesResponse.builder()
                        .criticalSchemeCount(2L)
                        .list(true)
                        .page(1)
                        .limit(2)
                        .schemes(List.of(
                                org.arghyam.jalsoochak.analytics.dto.response.CriticalSchemesResponse.CriticalSchemeListItem.builder()
                                        .schemeId(101)
                                        .schemeName("Scheme A")
                                        .stateSchemeId(5001)
                                        .centreSchemeId(6001)
                                        .lastSuppliedDate(LocalDate.of(2026, 4, 1))
                                        .build(),
                                org.arghyam.jalsoochak.analytics.dto.response.CriticalSchemesResponse.CriticalSchemeListItem.builder()
                                        .schemeId(102)
                                        .schemeName("Scheme B")
                                        .stateSchemeId(5002)
                                        .centreSchemeId(6002)
                                        .lastSuppliedDate(null)
                                        .build()
                        ))
                        .build());

        mockMvc.perform(get(BASE + "/critical-schemes/user")
                        .principal(buildJwtAuthentication())
                        .param("list", "true")
                        .param("page", "1")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.criticalSchemeCount").value(2))
                .andExpect(jsonPath("$.data.list").value(true))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.limit").value(2))
                .andExpect(jsonPath("$.data.schemes[0].schemeId").value(101))
                .andExpect(jsonPath("$.data.schemes[0].stateSchemeId").value(5001))
                .andExpect(jsonPath("$.data.schemes[0].centreSchemeId").value(6001))
                .andExpect(jsonPath("$.data.schemes[0].lastSuppliedDate").value("2026-04-01"));
    }

    @Test
    void getContinuousSchemes_defaultCountOnly_routesToLgdService() throws Exception {
        when(schemeRegularityService.getContinuousSchemesByLgd(
                eq(TENANT_ID), eq(101), eq(START), eq(END), eq(false), isNull(), isNull()))
                .thenReturn(ContinuousSchemesResponse.builder()
                        .continuousSchemeCount(5L)
                        .list(false)
                        .page(null)
                        .limit(null)
                        .startDate(START)
                        .endDate(END)
                        .daysInRange(31)
                        .schemes(null)
                        .build());

        mockMvc.perform(get(BASE + "/continuous-schemes")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("lgd_id", "101")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.continuousSchemeCount").value(5))
                .andExpect(jsonPath("$.data.list").value(false))
                .andExpect(jsonPath("$.data.schemes").value(nullValue()));

        verify(schemeRegularityService, times(1)).getContinuousSchemesByLgd(
                eq(TENANT_ID), eq(101), eq(START), eq(END), eq(false), isNull(), isNull());
        verify(schemeRegularityService, never()).getContinuousSchemesByDepartment(
                any(), any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void getContinuousSchemes_withListTrue_returnsCountAndList() throws Exception {
        when(schemeRegularityService.getContinuousSchemesByDepartment(
                eq(TENANT_ID), eq(201), eq(START), eq(END), eq(true), eq(1), eq(2)))
                .thenReturn(ContinuousSchemesResponse.builder()
                        .continuousSchemeCount(2L)
                        .list(true)
                        .page(1)
                        .limit(2)
                        .startDate(START)
                        .endDate(END)
                        .daysInRange(31)
                        .schemes(List.of(
                                ContinuousSchemesResponse.ContinuousSchemeListItem.builder()
                                        .schemeId(101)
                                        .schemeName("Scheme A")
                                        .build(),
                                ContinuousSchemesResponse.ContinuousSchemeListItem.builder()
                                        .schemeId(102)
                                        .schemeName("Scheme B")
                                        .build()
                        ))
                        .build());

        mockMvc.perform(get(BASE + "/continuous-schemes")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("department_id", "201")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("list", "true")
                        .param("page", "1")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.continuousSchemeCount").value(2))
                .andExpect(jsonPath("$.data.list").value(true))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.limit").value(2))
                .andExpect(jsonPath("$.data.schemes").isArray())
                .andExpect(jsonPath("$.data.schemes[0].schemeId").value(101))
                .andExpect(jsonPath("$.data.schemes[0].schemeName").value("Scheme A"));
    }

    @Test
    void getContinuousSchemes_withBothIds_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/continuous-schemes")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("lgd_id", "101")
                        .param("department_id", "201")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getContinuousSchemes_withNoId_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/continuous-schemes")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getContinuousSchemes_withoutDates_usesTenantDataDefaultWindow() throws Exception {
        when(defaultAnalyticsDateWindowProvider.defaultWindow())
                .thenReturn(new DefaultAnalyticsDateWindowProvider.DateWindow(START, END));
        when(schemeRegularityService.getContinuousSchemesByLgd(
                eq(TENANT_ID), eq(101), eq(START), eq(END), eq(false), isNull(), isNull()))
                .thenReturn(ContinuousSchemesResponse.builder()
                        .continuousSchemeCount(5L)
                        .list(false)
                        .page(null)
                        .limit(null)
                        .startDate(START)
                        .endDate(END)
                        .daysInRange(31)
                        .schemes(null)
                        .build());

        mockMvc.perform(get(BASE + "/continuous-schemes")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("lgd_id", "101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.continuousSchemeCount").value(5));

        verify(defaultAnalyticsDateWindowProvider, times(1)).defaultWindow();
        verify(schemeRegularityService, times(1)).getContinuousSchemesByLgd(
                eq(TENANT_ID), eq(101), eq(START), eq(END), eq(false), isNull(), isNull());
    }

    @Test
    void getContinuousSchemes_withOnlyOneDate_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/continuous-schemes")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("lgd_id", "101")
                        .param("start_date", START.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getContinuousSchemes_withListTrue_andInvalidPage_returnsBadRequest() throws Exception {
        mockMvc.perform(get(BASE + "/continuous-schemes")
                        .param("tenant_id", String.valueOf(TENANT_ID))
                        .param("lgd_id", "101")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("list", "true")
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void getContinuousSchemesForUser_defaultCountOnly_routesToUserService() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(77, null, TENANT_ID));
        when(schemeRegularityService.getContinuousSchemesByUser(
                eq(TENANT_ID), eq(77), eq(START), eq(END), eq(false), isNull(), isNull()))
                .thenReturn(ContinuousSchemesResponse.builder()
                        .continuousSchemeCount(3L)
                        .list(false)
                        .page(null)
                        .limit(null)
                        .startDate(START)
                        .endDate(END)
                        .daysInRange(31)
                        .schemes(null)
                        .build());

        mockMvc.perform(get(BASE + "/continuous-schemes/user")
                        .principal(buildJwtAuthentication())
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.continuousSchemeCount").value(3))
                .andExpect(jsonPath("$.data.list").value(false))
                .andExpect(jsonPath("$.data.schemes").value(nullValue()));

        verify(schemeRegularityService, times(1)).getContinuousSchemesByUser(
                eq(TENANT_ID), eq(77), eq(START), eq(END), eq(false), isNull(), isNull());
    }

    @Test
    void getContinuousSchemesForUser_withoutDates_usesTenantDataDefaultWindow() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(77, null, TENANT_ID));
        when(defaultAnalyticsDateWindowProvider.defaultWindow())
                .thenReturn(new DefaultAnalyticsDateWindowProvider.DateWindow(START, END));
        when(schemeRegularityService.getContinuousSchemesByUser(
                eq(TENANT_ID), eq(77), eq(START), eq(END), eq(false), isNull(), isNull()))
                .thenReturn(ContinuousSchemesResponse.builder()
                        .continuousSchemeCount(5L)
                        .list(false)
                        .page(null)
                        .limit(null)
                        .startDate(START)
                        .endDate(END)
                        .daysInRange(31)
                        .schemes(null)
                        .build());

        mockMvc.perform(get(BASE + "/continuous-schemes/user")
                        .principal(buildJwtAuthentication()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.continuousSchemeCount").value(5));

        verify(defaultAnalyticsDateWindowProvider, times(1)).defaultWindow();
        verify(schemeRegularityService, times(1)).getContinuousSchemesByUser(
                eq(TENANT_ID), eq(77), eq(START), eq(END), eq(false), isNull(), isNull());
    }

    @Test
    void getContinuousSchemesForUser_withListTrue_andInvalidPage_returnsBadRequest() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(77, null, TENANT_ID));
        mockMvc.perform(get(BASE + "/continuous-schemes/user")
                        .principal(buildJwtAuthentication())
                        .param("start_date", START.toString())
                        .param("end_date", END.toString())
                        .param("list", "true")
                        .param("page", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    /**
     * SA finding: "Authorization Bypass via Missing Subdivisional JWT Token".
     *
     * <p>Both endpoints used to take tenant_id and user_id as query parameters and pass them
     * straight to the service. Closing the anonymous hole is not enough on its own -- any
     * authenticated caller could still name someone else's user_id, in any tenant. Identity now
     * comes from the token, and these pin that: the query parameters are ignored even when a
     * caller supplies them.
     */
    @Test
    void getContinuousSchemesForUser_ignoresCallerSuppliedIdentity() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(77, null, TENANT_ID));
        when(schemeRegularityService.getContinuousSchemesByUser(
                eq(TENANT_ID), eq(77), eq(START), eq(END), eq(false), isNull(), isNull()))
                .thenReturn(ContinuousSchemesResponse.builder()
                        .continuousSchemeCount(1L)
                        .list(false)
                        .startDate(START)
                        .endDate(END)
                        .daysInRange(31)
                        .build());

        mockMvc.perform(get(BASE + "/continuous-schemes/user")
                        .principal(buildJwtAuthentication())
                        // Another officer, in another tenant. Both must be disregarded.
                        .param("tenant_id", "999")
                        .param("user_id", "424242")
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isOk());

        verify(schemeRegularityService, times(1)).getContinuousSchemesByUser(
                eq(TENANT_ID), eq(77), eq(START), eq(END), eq(false), isNull(), isNull());
        verify(schemeRegularityService, never()).getContinuousSchemesByUser(
                eq(999), any(), any(), any(), anyBoolean(), any(), any());
        verify(schemeRegularityService, never()).getContinuousSchemesByUser(
                any(), eq(424242), any(), any(), anyBoolean(), any(), any());
    }

    /** A token with only a uuid subject still resolves to the right numeric user id. */
    @Test
    void getContinuousSchemesForUser_resolvesUserIdFromUuidWhenClaimAbsent() throws Exception {
        UUID uuid = UUID.fromString("3f1a9c22-5b7e-4d38-9a10-8c4b2e6f0d71");
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(null, uuid, TENANT_ID));
        when(authenticatedRequestContextService.resolveUserIdByUuid(TENANT_ID, uuid))
                .thenReturn(77);
        when(schemeRegularityService.getContinuousSchemesByUser(
                eq(TENANT_ID), eq(77), eq(START), eq(END), eq(false), isNull(), isNull()))
                .thenReturn(ContinuousSchemesResponse.builder()
                        .continuousSchemeCount(4L)
                        .list(false)
                        .startDate(START)
                        .endDate(END)
                        .daysInRange(31)
                        .build());

        mockMvc.perform(get(BASE + "/continuous-schemes/user")
                        .principal(buildJwtAuthentication())
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.continuousSchemeCount").value(4));
    }

    /** A token carrying no tenant context must not fall through to an unscoped read. */
    @Test
    void getContinuousSchemesForUser_withoutTenantContext_returnsBadRequest() throws Exception {
        when(authenticatedRequestContextService.extractAuthenticatedUserRef(any()))
                .thenReturn(new org.arghyam.jalsoochak.analytics.helper.AnalyticsControllerHelper.AuthenticatedUserRef(77, null, 0));

        mockMvc.perform(get(BASE + "/continuous-schemes/user")
                        .principal(buildJwtAuthentication())
                        .param("start_date", START.toString())
                        .param("end_date", END.toString()))
                .andExpect(status().isBadRequest());

        verify(schemeRegularityService, never()).getContinuousSchemesByUser(
                any(), any(), any(), any(), anyBoolean(), any(), any());
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
