package org.arghyam.jalsoochak.tenant.controller;

import org.arghyam.jalsoochak.tenant.TenantServiceApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins every route the service serves, as (method, absolute path) pairs.
 *
 * <p>The frontend and the other services call these endpoints by path, so moving a handler to
 * another controller or package must leave this list untouched. A change here is an API change for
 * those callers, to be coordinated with them — not a test to update in passing.
 *
 * <p>Routes are read off the service's own controller classes rather than a booted context: a
 * {@code @SpringBootTest} would need Postgres to answer a question about static structure.
 */
@DisplayName("Route parity — the HTTP surface the service serves")
class RouteParityTest {

    private static final String SERVICE_PACKAGE = "org.arghyam.jalsoochak.tenant";

    private static final List<String> SERVED_ROUTES = List.of(
            // Tenant lifecycle
            "POST /api/v1/tenants",
            "GET /api/v1/tenants",
            "GET /api/v1/tenants/summary",
            "PUT /api/v1/tenants/{tenantId}",
            "POST /api/v1/tenants/{tenantId}/deactivate",
            // Tenant configuration
            "GET /api/v1/tenants/{tenantId}/config",
            "PUT /api/v1/tenants/{tenantId}/config",
            "GET /api/v1/tenants/{tenantId}/config/public",
            "GET /api/v1/tenants/{tenantId}/config/status",
            // Tenant branding
            "PUT /api/v1/tenants/{tenantId}/logo",
            "GET /api/v1/tenants/{tenantId}/logo",
            // Tenant locations
            "GET /api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}",
            "PUT /api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}",
            "GET /api/v1/tenants/{tenantId}/location-hierarchy/{hierarchyType}/edit-constraints",
            "GET /api/v1/tenants/{tenantId}/locations/{hierarchyType}",
            // Tenant API token
            "POST /api/v1/tenants/api-token",
            // Messaging providers
            "PUT /api/v1/tenants/{tenantId}/messaging-providers",
            "GET /api/v1/tenants/{tenantId}/messaging-providers",
            "DELETE /api/v1/tenants/{tenantId}/messaging-providers/{channel}",
            "PUT /api/v1/tenants/{tenantId}/messaging-providers/{channel}/secrets",
            "GET /api/v1/tenants/{tenantId}/messaging-providers/{channel}/secrets",
            "DELETE /api/v1/tenants/{tenantId}/messaging-providers/{channel}/secrets",
            // Messaging secrets
            "POST /api/v1/system/messaging-secrets/rewrap",
            "POST /api/v1/system/messaging-secrets/tenants/{tenantId}/rotate",
            // System configuration
            "GET /api/v1/system/config",
            "PUT /api/v1/system/config",
            "GET /api/v1/system/channels"
    );

    @Test
    @DisplayName("the service serves exactly the pinned routes, each once")
    void servesExactlyThePinnedRoutes() {
        // A list, not a set: the same route declared on two controllers fails at startup, so it
        // should fail here first.
        assertThat(servedRoutes()).containsExactlyInAnyOrderElementsOf(SERVED_ROUTES);
    }

    /** Every "METHOD /absolute/path" pair declared on the service's own controllers. */
    private static List<String> servedRoutes() {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(Controller.class));

        return provider.findCandidateComponents(SERVICE_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(Objects::nonNull)
                .map(name -> ClassUtils.resolveClassName(name, RouteParityTest.class.getClassLoader()))
                // Test sources share the package; a fixture controller declared there is not served.
                .filter(type -> codeSource(type).equals(codeSource(TenantServiceApplication.class)))
                .flatMap(controller -> routesOf(controller).stream())
                .toList();
    }

    private static List<String> routesOf(Class<?> controller) {
        RequestMapping typeMapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        String[] bases = typeMapping == null || typeMapping.path().length == 0 ? new String[]{""} : typeMapping.path();

        List<String> routes = new ArrayList<>();
        for (Method method : controller.getDeclaredMethods()) {
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) {
                continue;
            }
            String[] paths = mapping.path().length == 0 ? new String[]{""} : mapping.path();
            // An empty method list maps every verb; record it as such rather than dropping it.
            List<String> verbs = mapping.method().length == 0
                    ? List.of("ANY")
                    : Arrays.stream(mapping.method()).map(RequestMethod::name).toList();
            for (String base : bases) {
                for (String path : paths) {
                    for (String verb : verbs) {
                        routes.add(verb + " " + base + path);
                    }
                }
            }
        }
        return routes;
    }

    private static String codeSource(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation().toString();
    }
}
