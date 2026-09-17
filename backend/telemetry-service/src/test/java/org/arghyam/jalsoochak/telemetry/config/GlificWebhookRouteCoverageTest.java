package org.arghyam.jalsoochak.telemetry.config;

import org.arghyam.jalsoochak.telemetry.controller.GlificWebhookController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the closed allowlist in {@link GlificWebhookRoutes} against drift.
 *
 * <p>An allowlist is the right choice here (see {@code GlificWebhookRoutes} for why a path prefix
 * would break the vendor ingestion endpoints), but it has one failure mode: a webhook added to the
 * controller later would be silently unauthenticated — a security regression that no other test would
 * notice. This test makes that a build failure.
 *
 * <p>It fails in both directions on purpose: a new {@code @PostMapping} that nobody protected, and a
 * protected path whose handler has been deleted.
 */
@DisplayName("GlificWebhookRoutes — coverage of the controller")
class GlificWebhookRouteCoverageTest {

    private static Set<String> declaredPostMappingPaths() {
        Set<String> paths = new LinkedHashSet<>();
        for (Method method : GlificWebhookController.class.getDeclaredMethods()) {
            PostMapping mapping = method.getAnnotation(PostMapping.class);
            if (mapping == null) {
                continue;
            }
            String[] values = mapping.value().length > 0 ? mapping.value() : mapping.path();
            assertThat(values)
                    .as("@PostMapping on %s declares no path", method.getName())
                    .isNotEmpty();
            paths.addAll(Arrays.asList(values));
        }
        return paths;
    }

    @Test
    @DisplayName("every @PostMapping on the controller is in the protected set, and vice versa")
    void protectedSetMatchesControllerExactly() {
        Set<String> declared = declaredPostMappingPaths();

        assertThat(declared)
                .as("Routes on GlificWebhookController that GlificWebhookRoutes does not protect. "
                        + "A new Glific webhook must be added to GlificWebhookRoutes, or it ships "
                        + "unauthenticated.")
                .containsExactlyInAnyOrderElementsOf(GlificWebhookRoutes.relativePaths());
    }

    @Test
    @DisplayName("the controller's base path is the one the filter matches on")
    void basePathMatchesControllerRequestMapping() {
        RequestMapping mapping = GlificWebhookController.class.getAnnotation(RequestMapping.class);

        assertThat(mapping).isNotNull();
        assertThat(mapping.value()).containsExactly(GlificWebhookRoutes.BASE_PATH);
    }

    @Test
    @DisplayName("the audit reported 12 endpoints; the controller actually has 26")
    void protectsEveryEndpointNotJustTheReportedOnes() {
        // The security audit listed 12 paths. Pinning the real count keeps that discrepancy visible:
        // if this number changes, the Glific flow webhook nodes need updating too.
        assertThat(GlificWebhookRoutes.relativePaths()).hasSize(26);
        assertThat(GlificWebhookRoutes.absolutePaths()).hasSize(26);
    }

    /**
     * The two gates must hand off cleanly. {@link TelemetryApiKeyAuthFilter} denies by default under
     * {@code /readings**} and {@code /schemes/*}; a Glific route landing inside one of those prefixes
     * without being on its exemption list would be rejected for lacking an {@code X-Api-Key} it is
     * never going to have, breaking the flow in the field. Nothing else in either class would catch it.
     */
    @Test
    @DisplayName("no Glific webhook route is intercepted by the API-key gate")
    void noWebhookRouteIsBlockedByTheApiKeyGate() {
        assertThat(GlificWebhookRoutes.absolutePaths())
                .filteredOn(TelemetryApiKeyAuthFilter::requiresApiKey)
                .as("Glific routes that TelemetryApiKeyAuthFilter would 401 before the webhook token "
                        + "is ever checked. Add them to its UNAUTHENTICATED_WEBHOOK_PATHS.")
                .isEmpty();
    }

    /**
     * The mirror of the above: the paths the API-key filter deliberately lets through must be the ones
     * this filter picks up. If a path were dropped from {@link GlificWebhookRoutes}, these two would
     * be exempt from one gate and unknown to the other — fully public, with no test failing.
     */
    @Test
    @DisplayName("the API-key gate's exemptions are covered by the webhook token instead")
    void apiKeyExemptionsAreCoveredByTheWebhookGate() {
        assertThat(GlificWebhookRoutes.isProtected("POST", "/api/v1/telemetry/readings/glific")).isTrue();
        assertThat(GlificWebhookRoutes.isProtected("POST", "/api/v1/telemetry/schemes")).isTrue();
    }

    @Test
    @DisplayName("absolute paths are the base path joined to each relative path")
    void absolutePathsAreBasePlusRelative() {
        assertThat(GlificWebhookRoutes.absolutePaths())
                .allSatisfy(path -> assertThat(path).startsWith(GlificWebhookRoutes.BASE_PATH + "/"))
                .contains("/api/v1/telemetry/intro", "/api/v1/telemetry/update-previous-reading");
    }
}
