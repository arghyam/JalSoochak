package org.arghyam.jalsoochak.telemetry.controller;

import org.arghyam.jalsoochak.telemetry.TelemetryServiceApplication;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads the service's HTTP surface off its controller classes, for tests that pin it.
 *
 * <p>Scans the service's own classes rather than booting a context: a {@code @SpringBootTest} would
 * need Postgres and Kafka to answer a question about static structure. Test sources share the
 * package and declare fixture controllers of their own, so anything not compiled alongside the
 * application class is left out.
 */
public final class ControllerRoutes {

    private static final String SERVICE_PACKAGE = "org.arghyam.jalsoochak.telemetry";

    /** One (method, absolute path) pair a controller serves. */
    public record Route(Class<?> controller, String method, String path) {
        public String key() {
            return method + " " + path;
        }
    }

    private ControllerRoutes() {
    }

    /** Every route the service serves, one entry per (method, path) pair. */
    public static List<Route> routes() {
        return controllers().stream()
                .flatMap(controller -> routesOf(controller).stream())
                .toList();
    }

    public static Set<Class<?>> controllers() {
        return productionTypes(Controller.class);
    }

    /** The service's own classes carrying {@code annotation}, directly or as a meta-annotation. */
    public static Set<Class<?>> productionTypes(Class<? extends Annotation> annotation) {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(annotation));

        return provider.findCandidateComponents(SERVICE_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(Objects::nonNull)
                .map(name -> ClassUtils.resolveClassName(name, ControllerRoutes.class.getClassLoader()))
                .filter(type -> codeSource(type).equals(codeSource(TelemetryServiceApplication.class)))
                .collect(Collectors.toSet());
    }

    /** Every (method, absolute path) pair a controller serves, from the composed mapping annotations. */
    public static List<Route> routesOf(Class<?> controller) {
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

    private static String codeSource(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation().toString();
    }
}
