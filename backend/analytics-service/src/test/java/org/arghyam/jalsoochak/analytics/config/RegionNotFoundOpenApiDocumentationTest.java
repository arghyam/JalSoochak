package org.arghyam.jalsoochak.analytics.config;

import org.arghyam.jalsoochak.analytics.controller.tenant.AnalyticsRegionOnboardingController;
import org.arghyam.jalsoochak.analytics.controller.tenant.AnalyticsTenantController;
import org.arghyam.jalsoochak.analytics.helper.DefaultAnalyticsDateWindowProvider;
import org.arghyam.jalsoochak.analytics.helper.SingleTenantModeGuard;
import org.arghyam.jalsoochak.analytics.repository.DimLgdLocationRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.service.RegionOnboardingService;
import org.arghyam.jalsoochak.analytics.service.TenantDetailsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins that the generated OpenAPI document, not just the customizer, carries the phased-rollout 404:
 * springdoc must run {@link RegionNotFoundResponseCustomizer} after it has built an operation's
 * responses, or the 404 would be dropped. The rules themselves are in
 * {@link RegionNotFoundResponseCustomizerTest}.
 */
@WebMvcTest(controllers = {AnalyticsTenantController.class, AnalyticsRegionOnboardingController.class})
@Import({OpenApiConfig.class, SingleTenantModeGuard.class})
@ImportAutoConfiguration({
        SpringDocConfiguration.class,
        SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class})
@AutoConfigureMockMvc(addFilters = false)
class RegionNotFoundOpenApiDocumentationTest {

    private static final String API_DOCS = "/v3/api-docs";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DimTenantRepository dimTenantRepository;

    @MockBean
    private DimLgdLocationRepository dimLgdLocationRepository;

    @MockBean
    private TenantDetailsService tenantDetailsService;

    @MockBean
    private DefaultAnalyticsDateWindowProvider defaultAnalyticsDateWindowProvider;

    @MockBean
    private RegionOnboardingService regionOnboardingService;

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/tenant_data", "/tenant_boundaries", "/tenant_performance_score"})
    void endpointTakingARegionParam_documentsTheNotFound(String path) throws Exception {
        mockMvc.perform(get(API_DOCS))
                .andExpect(status().isOk())
                .andExpect(jsonPath(responses(path) + "['404'].description", containsString("phased rollout")))
                .andExpect(jsonPath(responses(path) + "['404'].content['application/json'].examples.failure.value.message")
                        .value("Region not found"))
                .andExpect(jsonPath(responses(path) + "['200']").exists())
                .andExpect(jsonPath(responses(path) + "['400']").exists());
    }

    @Test
    void onboardedRegions_keepsItsOwnNotFound() throws Exception {
        mockMvc.perform(get(API_DOCS))
                .andExpect(jsonPath(responses("/onboarded-regions") + "['404'].description")
                        .value("The parent isn't onboarded"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/tenants", "/national/onboarded-districts"})
    void endpointWithoutARegionParam_hasNoNotFound(String path) throws Exception {
        mockMvc.perform(get(API_DOCS))
                .andExpect(jsonPath(responses(path) + "['200']").exists())
                .andExpect(jsonPath(responses(path) + "['404']").doesNotExist());
    }

    private static String responses(String path) {
        return "$.paths['/api/v1/analytics" + path + "'].get.responses";
    }
}
