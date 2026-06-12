package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.dto.response.NonSubmissionReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.OutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.AverageWaterSupplyResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicWaterQuantityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.PeriodicOutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.RegionWiseWaterQuantityResponse;
import org.arghyam.jalsoochak.analytics.dto.response.SubmissionStatusSummaryResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserNonSubmissionReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserOutageReasonSchemeCountResponse;
import org.arghyam.jalsoochak.analytics.dto.response.UserSubmissionStatusResponse;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

@WebMvcTest(controllers = AnalyticsWaterQuantityOutageSubmissionController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsWaterQuantityOutageSubmissionControllerTest {

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

    private static Stream<Arguments> outageValidRoutes() {
        return Stream.of(
                Arguments.of("parent_lgd_id", "101", true),
                Arguments.of("parent_department_id", "201", false)
        );
    }

    private static Stream<Arguments> nonSubmissionValidRoutes() {
        return Stream.of(
                Arguments.of("parent_lgd_id", "101", true),
                Arguments.of("parent_department_id", "201", false)
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
