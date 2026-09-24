package org.arghyam.jalsoochak.analytics.config;

import org.arghyam.jalsoochak.analytics.controller.ControllerRoutes;
import org.arghyam.jalsoochak.analytics.controller.ControllerRoutes.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pins the anonymous surface of analytics-service against the routes it actually serves.
 *
 * <p>{@code SecurityConfig.PUBLIC_ANALYTICS_ENDPOINTS} is keyed on literal paths, so nothing ties it
 * to the controllers. This test ties it both ways. Every entry must be a GET route the service
 * declares, so a stale entry cannot open a path someone adds later. And every declared GET route is
 * requested without a token, so the anonymous surface is exactly {@link #PUBLIC_PATHS}.
 *
 * <p>{@link #PUBLIC_PATHS} is written out rather than read from the constant: making an endpoint
 * public has to show up as a change to this test too, where review will see it. Authorisation is
 * decided by path before dispatch, so a stand-in controller answers every analytics path and the
 * real ones are not loaded.
 */
@WebMvcTest(controllers = SecurityConfigPublicAllowlistTest.AnalyticsStandInController.class)
@Import({SecurityConfig.class, JwtAuthConverter.class, SecurityConfigPublicAllowlistTest.AnalyticsStandInController.class})
@DisplayName("Public analytics allowlist — the anonymous surface")
class SecurityConfigPublicAllowlistTest {

    /** The endpoints the anonymous public dashboard renders. Nothing else is readable without a token. */
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/analytics/continuous-schemes",
            "/api/v1/analytics/critical-schemes",
            "/api/v1/analytics/national/dashboard",
            "/api/v1/analytics/national/dashboard/boundary",
            "/api/v1/analytics/outage-reasons",
            "/api/v1/analytics/outage-reasons/periodic",
            "/api/v1/analytics/reading-submission-rate",
            "/api/v1/analytics/scheme-regularity/average",
            "/api/v1/analytics/scheme-regularity/periodic",
            "/api/v1/analytics/scheme-regularity/periodic/national",
            "/api/v1/analytics/schemes/dashboard",
            "/api/v1/analytics/schemes/dashboard/download",
            "/api/v1/analytics/submission-status",
            "/api/v1/analytics/tenant_boundaries",
            "/api/v1/analytics/tenant_data",
            "/api/v1/analytics/water-quantity/periodic",
            "/api/v1/analytics/water-quantity/region-wise",
            "/api/v1/analytics/water-supply/average-per-region"
    );

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtDecoder jwtDecoder;

    static SortedSet<String> declaredGetPaths() {
        return ControllerRoutes.routes().stream()
                .filter(route -> route.method().equals("GET"))
                .map(Route::path)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("every allowlist entry is a GET route the service serves")
    void everyAllowlistEntryIsADeclaredGetRoute() {
        String[] allowlist = (String[]) ReflectionTestUtils.getField(SecurityConfig.class, "PUBLIC_ANALYTICS_ENDPOINTS");

        assertThat(allowlist).isNotEmpty();
        assertThat(declaredGetPaths()).containsAll(Arrays.asList(allowlist));
    }

    @Test
    @DisplayName("every pinned public path is a GET route the service serves")
    void everyPinnedPathIsADeclaredGetRoute() {
        // The parameterised test below only requests declared routes, so a stale pin would never be
        // exercised and the surface it claims to pin would be one path short.
        assertThat(declaredGetPaths()).containsAll(PUBLIC_PATHS);
    }

    @ParameterizedTest(name = "GET {0}")
    @MethodSource("declaredGetPaths")
    @DisplayName("a declared GET route is readable without a token exactly when it is pinned public")
    void anonymousAccessMatchesThePinnedList(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(PUBLIC_PATHS.contains(path) ? status().isOk() : status().isUnauthorized());
    }

    /** Answers every analytics path, so a request's status is decided by the filter chain alone. */
    @RestController
    static class AnalyticsStandInController {

        @GetMapping("/api/v1/analytics/**")
        String anyAnalyticsPath() {
            return "ok";
        }
    }
}
