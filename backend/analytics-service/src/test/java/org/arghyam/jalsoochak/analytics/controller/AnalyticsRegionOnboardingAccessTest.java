package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.controller.regularity.AnalyticsRegularityController;
import org.arghyam.jalsoochak.analytics.controller.tenant.AnalyticsRegionOnboardingController;
import org.arghyam.jalsoochak.analytics.enums.RegionHierarchy;
import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.exception.RegionNotOnboardedException;
import org.arghyam.jalsoochak.analytics.helper.DefaultAnalyticsDateWindowProvider;
import org.arghyam.jalsoochak.analytics.helper.SingleTenantModeGuard;
import org.arghyam.jalsoochak.analytics.service.RegionOnboardingService;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the phased-rollout wiring with the switch on: the guard runs ahead of a real route and its
 * rejection reaches the client as a 404. The guard's own rules are in {@code RegionOnboardingInterceptorTest}.
 */
@WebMvcTest(controllers = {AnalyticsRegularityController.class, AnalyticsRegionOnboardingController.class})
@Import({GlobalExceptionHandler.class, SingleTenantModeGuard.class})
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "analytics.phased-rollout.enabled=true")
class AnalyticsRegionOnboardingAccessTest {

    private static final String AVERAGE_REGULARITY = "/api/v1/analytics/scheme-regularity/average";
    private static final String ONBOARDED_REGIONS = "/api/v1/analytics/onboarded-regions";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RegionOnboardingService regionOnboardingService;

    @MockBean
    private SchemeRegularityService schemeRegularityService;

    @MockBean
    private DefaultAnalyticsDateWindowProvider defaultAnalyticsDateWindowProvider;

    @Test
    void parentNotOnboarded_isNotFound_andNeverReachesTheController() throws Exception {
        doThrow(new RegionNotOnboardedException(3, RegionHierarchy.LGD, 12))
                .when(regionOnboardingService).requireOnboarded(3, RegionHierarchy.LGD, 12);

        mockMvc.perform(get(AVERAGE_REGULARITY)
                        .param("tenant_id", "3")
                        .param("parent_lgd_id", "12")
                        .param("start_date", "2026-01-01")
                        .param("end_date", "2026-01-31"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Region not found"));

        verifyNoInteractions(schemeRegularityService);
    }

    /** {@code @RequestParam} binds {@code name[]} when {@code name} is absent, so neither alias may bypass the guard. */
    @ParameterizedTest(name = "{0}=3&{1}=12")
    @CsvSource({
            "tenant_id, parent_lgd_id[]",
            "tenant_id[], parent_lgd_id"
    })
    void parentNotOnboarded_namedThroughABracketedAlias_isNotFound(String tenantParam, String parentParam)
            throws Exception {
        doThrow(new RegionNotOnboardedException(3, RegionHierarchy.LGD, 12))
                .when(regionOnboardingService).requireOnboarded(3, RegionHierarchy.LGD, 12);

        mockMvc.perform(get(AVERAGE_REGULARITY)
                        .param(tenantParam, "3")
                        .param(parentParam, "12")
                        .param("start_date", "2026-01-01")
                        .param("end_date", "2026-01-31"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Region not found"));

        verifyNoInteractions(schemeRegularityService);
    }

    @Test
    void onboardedParent_reachesTheController() throws Exception {
        mockMvc.perform(get(AVERAGE_REGULARITY)
                        .param("tenant_id", "3")
                        .param("parent_lgd_id", "12")
                        .param("start_date", "2026-01-01")
                        .param("end_date", "2026-01-31"))
                .andExpect(status().isOk());

        verify(regionOnboardingService).requireOnboarded(3, RegionHierarchy.LGD, 12);
        verify(schemeRegularityService).getAverageSchemeRegularity(
                3, 12, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));
    }

    @Test
    void onboardedRegions_parentNotOnboarded_isNotFound() throws Exception {
        doThrow(new RegionNotOnboardedException(3, RegionHierarchy.DEPARTMENT, 40))
                .when(regionOnboardingService).requireOnboarded(3, RegionHierarchy.DEPARTMENT, 40);

        mockMvc.perform(get(ONBOARDED_REGIONS)
                        .param("tenant_id", "3")
                        .param("parent_department_id", "40"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Region not found"));

        verify(regionOnboardingService, never()).onboardedChildren(anyInt(), any(), anyInt());
    }
}
