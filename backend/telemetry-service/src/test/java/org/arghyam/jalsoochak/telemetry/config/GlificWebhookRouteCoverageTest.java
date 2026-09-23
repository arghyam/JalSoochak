package org.arghyam.jalsoochak.telemetry.config;

import org.arghyam.jalsoochak.telemetry.TelemetryServiceApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the closed allowlist in {@link GlificWebhookRoutes} against drift.
 *
 * <p>An allowlist is the right choice here (see {@code GlificWebhookRoutes} for why a path prefix
 * would break the vendor ingestion endpoints), but it has one failure mode: a webhook added later
 * would be silently unauthenticated — a security regression that no other test would notice. This
 * test makes that a build failure.
 *
 * <p>It fails in both directions on purpose: a new {@code @PostMapping} that nobody protected, and a
 * protected path whose handler has been deleted.
 *
 * <p>The webhook surface is found by scanning for {@link WebhookRoute}, not by naming a controller
 * class. Reflecting over one class would silently cover only that class once the surface spans
 * several — and the routes on the others would ship public.
 */
@DisplayName("GlificWebhookRoutes — coverage of the webhook controllers")
class GlificWebhookRouteCoverageTest {

    private static final String SERVICE_PACKAGE = "org.arghyam.jalsoochak.telemetry";

    /**
     * Routes deliberately served outside both credential gates. {@code ApiController} is Initializr
     * scaffolding due for deletion; these entries go with it, and the test fails until they do.
     */
    private static final Set<String> UNGATED_ROUTES = Set.of(
            "GET /api/v1/telemetry",
            "POST /api/v1/publish"
    );

    private record Route(Class<?> controller, String method, String path) {
        String key() {
            return method + " " + path;
        }
    }

    @Test
    @DisplayName("every @PostMapping on a webhook controller is in the protected set, and vice versa")
    void protectedSetMatchesWebhookControllersExactly() {
        // A list, not a set: a path declared on two webhook controllers is a mistake to surface too.
        List<String> declared = webhookControllers().stream()
                .flatMap(controller -> postMappingPaths(controller).stream())
                .toList();

        assertThat(declared)
                .as("Routes on @WebhookRoute controllers that GlificWebhookRoutes does not protect. "
                        + "A new Glific webhook must be added to GlificWebhookRoutes, or it ships "
                        + "unauthenticated.")
                .containsExactlyInAnyOrderElementsOf(GlificWebhookRoutes.relativePaths());
    }

    @Test
    @DisplayName("every webhook controller is mapped on the base path the filter matches on")
    void basePathMatchesEveryWebhookControllerRequestMapping() {
        assertThat(webhookControllers())
                .isNotEmpty()
                .allSatisfy(controller -> {
                    RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
                    assertThat(mapping).as("%s has no @RequestMapping", controller.getSimpleName()).isNotNull();
                    assertThat(mapping.value())
                            .as("@RequestMapping of %s", controller.getSimpleName())
                            .containsExactly(GlificWebhookRoutes.BASE_PATH);
                });
    }

    @Test
    @DisplayName("webhook controllers map nothing but POST, the only method the allowlist matches")
    void webhookControllersMapOnlyPost() {
        List<String> nonPost = webhookControllers().stream()
                .flatMap(controller -> routesOf(controller).stream())
                .filter(route -> !"POST".equals(route.method()))
                .map(route -> route.controller().getSimpleName() + ": " + route.key())
                .toList();

        assertThat(nonPost)
                .as("Mappings on @WebhookRoute controllers that GlificWebhookRoutes.isProtected can "
                        + "never match, so the webhook gate would not authenticate them")
                .isEmpty();
    }

    @Test
    @DisplayName("the audit reported 12 endpoints; the webhook surface actually has 26")
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
     * this filter picks up. If a path were dropped from {@link GlificWebhookRoutes}, it would be exempt
     * from one gate and unknown to the other — fully public, with no test failing. Iterates the
     * exemptions themselves rather than naming them, so a new exemption is covered the moment it is
     * added.
     */
    @Test
    @DisplayName("the API-key gate's exemptions are covered by the webhook token instead")
    void apiKeyExemptionsAreCoveredByTheWebhookGate() {
        assertThat(TelemetryApiKeyAuthFilter.unauthenticatedWebhookPaths())
                .isNotEmpty()
                .allSatisfy(path -> assertThat(GlificWebhookRoutes.isProtected("POST", path))
                        .as("%s is exempt from the API-key gate but not protected by the webhook gate", path)
                        .isTrue());
    }

    @Test
    @DisplayName("absolute paths are the base path joined to each relative path")
    void absolutePathsAreBasePlusRelative() {
        assertThat(GlificWebhookRoutes.absolutePaths())
                .allSatisfy(path -> assertThat(path).startsWith(GlificWebhookRoutes.BASE_PATH + "/"))
                .contains("/api/v1/telemetry/intro", "/api/v1/telemetry/update-previous-reading");
    }

    /**
     * Closes the gap the allowlist cannot see: a controller that should have carried
     * {@link WebhookRoute} but does not. Its routes would be in no allowlist and, outside the
     * {@code /readings} and {@code /schemes/*} prefixes, behind no gate at all. Every route the
     * service maps must therefore be authenticated by exactly one of the two gates, or be listed in
     * {@link #UNGATED_ROUTES}.
     */
    @Test
    @DisplayName("every route the service maps is behind exactly one credential gate")
    void everyRouteIsBehindExactlyOneGate() {
        List<Route> routes = controllers().stream()
                .flatMap(controller -> routesOf(controller).stream())
                .toList();

        List<String> misgated = routes.stream()
                .filter(route -> !UNGATED_ROUTES.contains(route.key()))
                .filter(route -> {
                    boolean webhookGate = GlificWebhookRoutes.isProtected(route.method(), route.path());
                    boolean apiKeyGate = TelemetryApiKeyAuthFilter.requiresApiKey(route.path());
                    boolean webhookFamily = route.controller().isAnnotationPresent(WebhookRoute.class);
                    return webhookFamily ? !webhookGate || apiKeyGate : webhookGate || !apiKeyGate;
                })
                .map(route -> route.controller().getSimpleName() + ": " + route.key())
                .toList();

        assertThat(misgated)
                .as("Routes not behind exactly the gate of their family. A webhook controller needs "
                        + "@WebhookRoute and an allowlist entry; anything else must sit under the "
                        + "API-key prefixes.")
                .isEmpty();
        assertThat(routes.stream().map(Route::key).collect(Collectors.toSet()))
                .as("UNGATED_ROUTES entries that no controller maps any more — remove them")
                .containsAll(UNGATED_ROUTES);
    }

    private static Set<Class<?>> webhookControllers() {
        return controllers().stream()
                .filter(controller -> controller.isAnnotationPresent(WebhookRoute.class))
                .collect(Collectors.toSet());
    }

    private static List<String> postMappingPaths(Class<?> controller) {
        List<String> paths = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            PostMapping mapping = method.getAnnotation(PostMapping.class);
            if (mapping == null) {
                continue;
            }
            String[] values = mapping.value().length > 0 ? mapping.value() : mapping.path();
            assertThat(values)
                    .as("@PostMapping on %s.%s declares no path", controller.getSimpleName(), method.getName())
                    .isNotEmpty();
            paths.addAll(Arrays.asList(values));
        }
        return paths;
    }

    /** Every (method, absolute path) pair a controller serves, from the composed mapping annotations. */
    private static List<Route> routesOf(Class<?> controller) {
        RequestMapping typeMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        String[] bases = typeMapping == null || typeMapping.path().length == 0 ? new String[]{""} : typeMapping.path();

        List<Route> routes = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            String[] paths = mapping.path().length == 0 ? new String[]{""} : mapping.path();
            // An empty method list maps every verb; record it as such so no gate can claim it.
            List<String> verbs = mapping.method().length == 0
                    ? List.of("ANY")
                    : Arrays.stream(mapping.method()).map(RequestMethod::name).toList();
            for (String base : bases) {
                for (String path : paths) {
                    for (String verb : verbs) {
                        routes.add(new Route(controller, verb, base + path));
                    }
                }
            }
        }
        return routes;
    }

    /**
     * Scans the service's own classes rather than booting a context: a {@code @SpringBootTest} here
     * would need Postgres and Kafka to answer a question about static structure. Test sources share
     * the package and declare fixture controllers of their own, so anything not compiled alongside
     * the application class is left out.
     */
    private static Set<Class<?>> controllers() {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        return provider.findCandidateComponents(SERVICE_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(Objects::nonNull)
                .map(name -> ClassUtils.resolveClassName(name, GlificWebhookRouteCoverageTest.class.getClassLoader()))
                .filter(type -> codeSource(type).equals(codeSource(TelemetryServiceApplication.class)))
                .collect(Collectors.toSet());
    }

    private static String codeSource(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation().toString();
    }
}
