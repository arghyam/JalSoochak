package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.exception.GlobalExceptionHandler;
import org.arghyam.jalsoochak.analytics.helper.DefaultAnalyticsDateWindowProvider;
import org.arghyam.jalsoochak.analytics.service.DateDimensionService;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AnalyticsWaterSupplyNationalController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "analytics.single-tenant-mode=true")
class AnalyticsWaterSupplyNationalControllerSingleTenantModeAccessTest {

    private static final String BASE = "/api/v1/analytics";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private SchemeRegularityService schemeRegularityService;

    @MockBean
    private DateDimensionService dateDimensionService;

    @MockBean
    private DefaultAnalyticsDateWindowProvider defaultAnalyticsDateWindowProvider;

    @Test
    void nationalDashboardBoundary_forbiddenInSingleTenantMode() throws Exception {
        mockMvc.perform(get(BASE + "/national/dashboard/boundary"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("API 'national/dashboard/boundary' cannot be accessed when single-tenant mode is enabled"));
    }

    @Test
    void nationalDashboardLevel2Boundary_forbiddenInSingleTenantMode() throws Exception {
        mockMvc.perform(get(BASE + "/national/dashboard/boundary/district"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("API 'national/dashboard/boundary/district' cannot be accessed when single-tenant mode is enabled"));
    }

    @Test
    void nationalDashboard_forbiddenInSingleTenantMode() throws Exception {
        mockMvc.perform(get(BASE + "/national/dashboard")
                        .param("start_date", "2026-01-01")
                        .param("end_date", "2026-01-31"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("API 'national/dashboard' cannot be accessed when single-tenant mode is enabled"));
    }

    @Test
    void nationalDashboardDistrict_forbiddenInSingleTenantMode() throws Exception {
        mockMvc.perform(get(BASE + "/national/dashboard/district"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("API 'national/dashboard/district' cannot be accessed when single-tenant mode is enabled"));
    }

    @Test
    void periodicNationalSchemeRegularity_forbiddenInSingleTenantMode() throws Exception {
        mockMvc.perform(get(BASE + "/scheme-regularity/periodic/national")
                        .param("start_date", "2026-01-01")
                        .param("end_date", "2026-01-31")
                        .param("scale", "day"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("API 'scheme-regularity/periodic/national' cannot be accessed when single-tenant mode is enabled"));
    }
}

