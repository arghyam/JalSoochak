package org.arghyam.jalsoochak.analytics.controller;

import org.arghyam.jalsoochak.analytics.controller.ControllerRoutes.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins every route the service serves, as (method, absolute path) pairs.
 *
 * <p>The public dashboard and the officer console call these endpoints by path, so moving a handler
 * to another controller or package must leave this list untouched. A change here is an API change
 * for those callers, to be coordinated with them — not a test to update in passing.
 */
@DisplayName("Route parity — the HTTP surface the service serves")
class RouteParityTest {

    private static final List<String> SERVED_ROUTES = List.of(
            // Scheme status and reporting
            "GET /api/v1/analytics/schemes/status-count",
            "GET /api/v1/analytics/schemes/dashboard",
            "GET /api/v1/analytics/schemes/dashboard/download",
            "GET /api/v1/analytics/schemes",
            "GET /api/v1/analytics/schemes/region-report",
            "GET /api/v1/analytics/scheme-performance",
            // Scheme segments
            "GET /api/v1/analytics/critical-schemes",
            "GET /api/v1/analytics/critical-schemes/user",
            "GET /api/v1/analytics/continuous-schemes",
            "GET /api/v1/analytics/continuous-schemes/user",
            // Regularity
            "GET /api/v1/analytics/scheme-regularity/average",
            "GET /api/v1/analytics/scheme-regularity/periodic",
            "GET /api/v1/analytics/scheme-regularity/periodic/national",
            // Reading submission
            "GET /api/v1/analytics/reading-submission-rate",
            "GET /api/v1/analytics/meter-readings",
            "GET /api/v1/analytics/submission-status",
            "GET /api/v1/analytics/submission-status/user",
            "GET /api/v1/analytics/non-submission-reasons",
            "GET /api/v1/analytics/non-submission-reasons/user",
            // Water quantity
            "GET /api/v1/analytics/water-supply/average-per-region",
            "GET /api/v1/analytics/water-quantity/region-wise",
            "GET /api/v1/analytics/water-quantity/periodic",
            // Outages
            "GET /api/v1/analytics/outage-reasons",
            "GET /api/v1/analytics/outage-reasons/periodic",
            "GET /api/v1/analytics/outage-reasons/user",
            // Anomalies and escalations
            "GET /api/v1/analytics/anomalies",
            "GET /api/v1/analytics/anomalies/statuses",
            "GET /api/v1/analytics/escalations",
            "GET /api/v1/analytics/escalations/statuses",
            // Tenants
            "GET /api/v1/analytics/tenants",
            "GET /api/v1/analytics/tenant_data",
            "GET /api/v1/analytics/tenant_boundaries",
            "GET /api/v1/analytics/tenant_performance_score",
            // National dashboard
            "GET /api/v1/analytics/national/dashboard",
            "GET /api/v1/analytics/national/dashboard/district",
            "GET /api/v1/analytics/national/dashboard/boundary",
            "GET /api/v1/analytics/national/dashboard/boundary/district",
            // Officer dashboard
            "GET /api/v1/analytics/officer/dashboard",
            "GET /api/v1/analytics/operator-attendance"
    );

    @Test
    @DisplayName("the service serves exactly the pinned routes, each once")
    void servesExactlyThePinnedRoutes() {
        // A list, not a set: the same route declared on two controllers fails at startup, so it
        // should fail here first.
        List<String> served = ControllerRoutes.routes().stream()
                .map(Route::key)
                .toList();

        assertThat(served).containsExactlyInAnyOrderElementsOf(SERVED_ROUTES);
    }
}
