package org.arghyam.jalsoochak.telemetry.controller;

import org.arghyam.jalsoochak.telemetry.controller.ControllerRoutes.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins every route the service serves, as (method, absolute path) pairs.
 *
 * <p>The chatbot flows, the State IT integration and the frontend call these endpoints by path, so
 * moving a handler to another controller or package must leave this list untouched. A change here is
 * an API change for one of those callers, to be coordinated with them — not a test to update in
 * passing.
 *
 * <p>It compares path values, not handler counts: {@code POST} and {@code PUT /readings} each serve
 * the path with and without a trailing slash, and the State IT integration calls both spellings. A
 * count would read two higher than the handlers and invite dropping one.
 */
@DisplayName("Route parity — the HTTP surface the service serves")
class RouteParityTest {

    private static final List<String> SERVED_ROUTES = List.of(
            // Chatbot webhooks — readings
            "POST /api/v1/telemetry/readings/glific",
            "POST /api/v1/telemetry/take-meter-reading",
            "POST /api/v1/telemetry/manual-reading",
            "POST /api/v1/telemetry/location",
            "POST /api/v1/telemetry/update-previous-reading",
            // Chatbot webhooks — selections
            "POST /api/v1/telemetry/language/selection",
            "POST /api/v1/telemetry/selected/language",
            "POST /api/v1/telemetry/channel/selection",
            "POST /api/v1/telemetry/selected/channel",
            "POST /api/v1/telemetry/schemes",
            "POST /api/v1/telemetry/scheme/selected",
            "POST /api/v1/telemetry/item/selection",
            "POST /api/v1/telemetry/selected/item",
            // Chatbot webhooks — issue reports
            "POST /api/v1/telemetry/issue-report",
            "POST /api/v1/telemetry/issue-report/submit",
            "POST /api/v1/telemetry/issue-report/telemetry",
            "POST /api/v1/telemetry/issue-report/telemetry/submit",
            "POST /api/v1/telemetry/meter/issue-report",
            "POST /api/v1/telemetry/others",
            "POST /api/v1/telemetry/others/submitted",
            // Chatbot webhooks — meter change
            "POST /api/v1/telemetry/meter-change",
            "POST /api/v1/telemetry/meter/meter-change",
            "POST /api/v1/telemetry/meter/meter-change/submit",
            // Chatbot webhooks — conversation
            "POST /api/v1/telemetry/intro",
            "POST /api/v1/telemetry/closing",
            "POST /api/v1/telemetry/trigger-welcome-message",
            // State IT ingestion
            "POST /api/v1/telemetry/readings",
            "POST /api/v1/telemetry/readings/",
            "PUT /api/v1/telemetry/readings",
            "PUT /api/v1/telemetry/readings/",
            "POST /api/v1/telemetry/readings/reset-latest",
            "PATCH /api/v1/telemetry/schemes/{schemeId}/yesterday-final-reading",
            "POST /api/v1/telemetry/readings/formats/{format}",
            // Initializr scaffolding, due for deletion
            "GET /api/v1/telemetry",
            "POST /api/v1/publish"
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
