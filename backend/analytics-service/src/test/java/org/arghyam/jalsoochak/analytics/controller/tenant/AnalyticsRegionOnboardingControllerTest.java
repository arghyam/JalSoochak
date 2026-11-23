package org.arghyam.jalsoochak.analytics.controller.tenant;

import org.arghyam.jalsoochak.analytics.dto.response.NationalOnboardedDistrictsResponse;
import org.arghyam.jalsoochak.analytics.dto.response.NationalOnboardedDistrictsResponse.TenantOnboardedDistricts;
import org.arghyam.jalsoochak.analytics.dto.response.OnboardedRegionsResponse;
import org.arghyam.jalsoochak.analytics.enums.RegionHierarchy;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.helper.SingleTenantModeGuard;
import org.arghyam.jalsoochak.analytics.service.RegionOnboardingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The {@code /onboarded-regions} and {@code /national/onboarded-districts} contracts. What counts as onboarded is {@code RegionOnboardingServiceTest}'s;
 * the 404 for a parent that isn't onboarded is {@code AnalyticsRegionOnboardingAccessTest}'s.
 */
@WebMvcTest(controllers = AnalyticsRegionOnboardingController.class)
@Import({GlobalExceptionHandler.class, SingleTenantModeGuard.class})
@AutoConfigureMockMvc(addFilters = false)
class AnalyticsRegionOnboardingControllerTest {

    private static final String ONBOARDED_REGIONS = "/api/v1/analytics/onboarded-regions";
    private static final String NATIONAL_ONBOARDED_DISTRICTS = "/api/v1/analytics/national/onboarded-districts";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RegionOnboardingService regionOnboardingService;

    @Test
    void lgdParent_answersTheOnboardedChildrenInTheEnvelope() throws Exception {
        when(regionOnboardingService.onboardedChildren(3, RegionHierarchy.LGD, 1))
                .thenReturn(OnboardedRegionsResponse.builder()
                        .tenantId(3)
                        .parentLgdId(1)
                        .phasedRollout(true)
                        .onboardedChildIds(List.of(12, 15))
                        .build());

        mockMvc.perform(get(ONBOARDED_REGIONS)
                        .param("tenant_id", "3")
                        .param("parent_lgd_id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.tenantId").value(3))
                .andExpect(jsonPath("$.data.parentLgdId").value(1))
                .andExpect(jsonPath("$.data.parentDepartmentId").value(nullValue()))
                .andExpect(jsonPath("$.data.phasedRollout").value(true))
                .andExpect(jsonPath("$.data.onboardedChildIds", contains(12, 15)));
    }

    @Test
    void departmentParent_isAskedInTheDepartmentHierarchy() throws Exception {
        when(regionOnboardingService.onboardedChildren(3, RegionHierarchy.DEPARTMENT, 40))
                .thenReturn(OnboardedRegionsResponse.builder()
                        .tenantId(3)
                        .parentDepartmentId(40)
                        .phasedRollout(true)
                        .onboardedChildIds(List.of(41))
                        .build());

        mockMvc.perform(get(ONBOARDED_REGIONS)
                        .param("tenant_id", "3")
                        .param("parent_department_id", "40"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentDepartmentId").value(40))
                .andExpect(jsonPath("$.data.onboardedChildIds", contains(41)));
    }

    @Test
    void switchOff_keepsTheNullIdsInTheBody() throws Exception {
        when(regionOnboardingService.onboardedChildren(3, RegionHierarchy.LGD, 1))
                .thenReturn(OnboardedRegionsResponse.builder()
                        .tenantId(3)
                        .parentLgdId(1)
                        .phasedRollout(false)
                        .build());

        mockMvc.perform(get(ONBOARDED_REGIONS)
                        .param("tenant_id", "3")
                        .param("parent_lgd_id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phasedRollout").value(false))
                .andExpect(jsonPath("$.data", hasKey("onboardedChildIds")))
                .andExpect(jsonPath("$.data.onboardedChildIds").value(nullValue()));
    }

    @Test
    void bothParents_isABadRequest() throws Exception {
        mockMvc.perform(get(ONBOARDED_REGIONS)
                        .param("tenant_id", "3")
                        .param("parent_lgd_id", "1")
                        .param("parent_department_id", "40"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Provide exactly one of parent_lgd_id or parent_department_id"));

        verifyNoInteractions(regionOnboardingService);
    }

    @Test
    void noParent_isABadRequest() throws Exception {
        mockMvc.perform(get(ONBOARDED_REGIONS).param("tenant_id", "3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Provide exactly one of parent_lgd_id or parent_department_id"));

        verifyNoInteractions(regionOnboardingService);
    }

    @Test
    void missingTenantId_isABadRequest() throws Exception {
        mockMvc.perform(get(ONBOARDED_REGIONS).param("parent_lgd_id", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing required parameter: tenant_id"));

        verifyNoInteractions(regionOnboardingService);
    }

    @Test
    void parentTheTenantDoesNotHave_isABadRequest() throws Exception {
        when(regionOnboardingService.onboardedChildren(3, RegionHierarchy.LGD, 999))
                .thenThrow(new IllegalArgumentException("LGD region 999 not found for tenant_id: 3"));

        mockMvc.perform(get(ONBOARDED_REGIONS)
                        .param("tenant_id", "3")
                        .param("parent_lgd_id", "999"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("LGD region 999 not found for tenant_id: 3"));
    }

    @Test
    void national_answersEachTenantsOnboardedDistrictsInTheEnvelope() throws Exception {
        when(regionOnboardingService.onboardedDistrictsByTenant())
                .thenReturn(NationalOnboardedDistrictsResponse.builder()
                        .phasedRollout(true)
                        .tenants(List.of(
                                new TenantOnboardedDistricts(3, List.of(12, 15)),
                                new TenantOnboardedDistricts(7, List.of(40))))
                        .build());

        mockMvc.perform(get(NATIONAL_ONBOARDED_DISTRICTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.phasedRollout").value(true))
                .andExpect(jsonPath("$.data.tenants[0].tenantId").value(3))
                .andExpect(jsonPath("$.data.tenants[0].onboardedDistrictIds", contains(12, 15)))
                .andExpect(jsonPath("$.data.tenants[1].tenantId").value(7))
                .andExpect(jsonPath("$.data.tenants[1].onboardedDistrictIds", contains(40)));
    }

    @Test
    void national_switchOff_keepsTheNullTenantsInTheBody() throws Exception {
        when(regionOnboardingService.onboardedDistrictsByTenant())
                .thenReturn(NationalOnboardedDistrictsResponse.builder().phasedRollout(false).build());

        mockMvc.perform(get(NATIONAL_ONBOARDED_DISTRICTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phasedRollout").value(false))
                .andExpect(jsonPath("$.data", hasKey("tenants")))
                .andExpect(jsonPath("$.data.tenants").value(nullValue()));
    }
}
