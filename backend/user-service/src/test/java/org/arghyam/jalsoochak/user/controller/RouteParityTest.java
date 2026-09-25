package org.arghyam.jalsoochak.user.controller;

import org.arghyam.jalsoochak.user.UserServiceApplication;
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

    private static final String SERVICE_PACKAGE = "org.arghyam.jalsoochak.user";

    private static final List<String> SERVED_ROUTES = List.of(
            // Authentication
            "POST /api/v1/auth/login",
            "POST /api/v1/auth/refresh",
            "POST /api/v1/auth/logout",
            "GET /api/v1/auth/invites",
            "POST /api/v1/auth/invites/activate",
            "POST /api/v1/auth/forgot-password",
            "POST /api/v1/auth/reset-password",
            "POST /api/v1/auth/staff/otp",
            "POST /api/v1/auth/staff/otp/verify",
            // Own account
            "GET /api/v1/users/me",
            "PATCH /api/v1/users/me",
            "PATCH /api/v1/users/me/password",
            // User administration
            "POST /api/v1/users/invitations",
            "GET /api/v1/users/super-users",
            "GET /api/v1/users/state-admins",
            "GET /api/v1/users/{id}",
            "PATCH /api/v1/users/{id}",
            "POST /api/v1/users/{id}/deactivate",
            "POST /api/v1/users/{id}/activate",
            "POST /api/v1/users/{id}/invitations",
            // Staff administration
            "GET /api/v1/tenant/user/staff",
            "PUT /api/v1/tenant/user/staff/{id}/role",
            "GET /api/v1/tenant/user/staff/counts/by-role",
            "POST /api/v1/tenant/user/staff/{id}/deactivate",
            "POST /api/v1/tenant/user/staff/{id}/activate",
            "POST /api/v1/tenant/user/staff/reports",
            "POST /api/v1/tenant/user/welcome",
            // Pump operator and person reads
            "GET /api/v1/pumpoperator/pump-operators/{pumpOperatorId}",
            "GET /api/v1/pumpoperator/pump-operators/by-uuid/{uuid}",
            "GET /api/v1/pumpoperator/pump-operators/{pumpOperatorId}/reading-compliance",
            "GET /api/v1/pumpoperator/pump-operators/{pumpOperatorId}/details-with-compliance",
            "GET /api/v1/pumpoperator/pump-operators/{pumpOperatorId}/readings",
            "GET /api/v1/pumpoperator/pump-operators/reading-compliance",
            "GET /api/v1/pumpoperator/pump-operators/by-scheme",
            "GET /api/v1/pumpoperator/pump-operators/by-scheme/reading-compliance",
            "GET /api/v1/pumpoperator/person/{personId}/schemes/count",
            "GET /api/v1/pumpoperator/person/{personId}/schemes",
            "GET /api/v1/pumpoperator/person/{personId}/pump-operators",
            // Scheme reads
            "GET /api/v1/pumpoperator/schemes/{schemeId}/details",
            "GET /api/v1/pumpoperator/schemes/{schemeId}/reading-submissions",
            // Bulk uploads
            "POST /api/v1/state-admin/pump-operators/upload",
            "POST /api/v1/state-admin/user-scheme-mappings/upload"
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
                .filter(type -> codeSource(type).equals(codeSource(UserServiceApplication.class)))
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
