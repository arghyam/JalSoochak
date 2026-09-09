package org.arghyam.jalsoochak.telemetry.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two structural assumptions the filter's design rests on: where it sits in the chain, and
 * that this service has no CORS policy.
 */
@DisplayName("DisallowedHttpMethodFilter placement")
class DisallowedHttpMethodFilterOrderTest {

    private static final String SERVICE_PACKAGE = "org.arghyam.jalsoochak.telemetry";

    @Test
    @DisplayName("runs after request correlation so rejections carry the request id")
    void runsAfterRequestCorrelation() {
        assertThat(DisallowedHttpMethodFilter.ORDER).isGreaterThan(RequestCorrelationFilter.ORDER);
    }

    @Test
    @DisplayName("runs before both credential gates")
    void runsBeforeBothCredentialGates() {
        // So a disallowed method never triggers an API-key lookup against the database and never
        // reaches TenantInterceptor, which would otherwise apply an unauthenticated X-Tenant-Code.
        assertThat(DisallowedHttpMethodFilter.ORDER)
                .isLessThan(TelemetryApiKeyAuthFilter.ORDER)
                .isLessThan(GlificWebhookAuthFilter.ORDER);
    }

    @Test
    @DisplayName("the ORDER constant matches the @Order annotation Boot actually reads")
    void theOrderConstantMatchesTheOrderAnnotation() {
        // Spring Boot sorts filter beans on the annotation, not on the constant; GenericFilterBean
        // does not implement Ordered. If the two drift, the documented chain order silently stops
        // being the real one.
        Order order = AnnotationUtils.findAnnotation(DisallowedHttpMethodFilter.class, Order.class);

        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(DisallowedHttpMethodFilter.ORDER);
    }

    @Test
    @DisplayName("the service publishes no CORS policy, which is what licenses rejecting every OPTIONS")
    void noCorsConfigurationIsPublishedByThisService() {
        // The filter rejects OPTIONS unconditionally, with no preflight carve-out, because a
        // preflight can never legitimately succeed here. If someone adds CORS to telemetry-service
        // that reasoning stops holding and browser preflights would get 405 instead of an answer —
        // so this fails the build and forces the decision to be revisited.
        Set<String> corsBeanMethods = types(new AnnotationTypeFilter(Configuration.class)).stream()
                .flatMap(type -> declaredMethods(type).stream()
                        .filter(method -> CorsConfigurationSource.class.isAssignableFrom(method.getReturnType()))
                        .map(method -> type.getSimpleName() + "#" + method.getName()))
                .collect(Collectors.toSet());

        assertThat(corsBeanMethods)
                .as("a CorsConfigurationSource bean means this service now needs a preflight carve-out")
                .isEmpty();

        Set<String> configurersMappingCors = types(new AssignableTypeFilter(WebMvcConfigurer.class)).stream()
                .filter(type -> declaredMethods(type).stream()
                        .map(Method::getName)
                        .anyMatch("addCorsMappings"::equals))
                .map(Class::getSimpleName)
                .collect(Collectors.toSet());

        assertThat(configurersMappingCors)
                .as("addCorsMappings is the other way a CORS policy can arrive")
                .isEmpty();
    }

    /**
     * Scans the service's own classes rather than booting a context: a {@code @SpringBootTest} here
     * would need Postgres and Kafka to answer a question about static structure.
     */
    private static Set<Class<?>> types(TypeFilter filter) {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(filter);

        return provider.findCandidateComponents(SERVICE_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .filter(Objects::nonNull)
                .map(name -> ClassUtils.resolveClassName(name, classLoader()))
                .collect(Collectors.toSet());
    }

    /**
     * A method signature can reference a class that is not on the test classpath, which surfaces as
     * a {@link NoClassDefFoundError} rather than an exception. Such a class cannot be declaring a
     * {@code CorsConfigurationSource} bean, so skipping it is safe.
     */
    private static Set<Method> declaredMethods(Class<?> type) {
        try {
            return Set.of(type.getDeclaredMethods());
        } catch (NoClassDefFoundError | TypeNotPresentException e) {
            return Set.of();
        }
    }

    private static ClassLoader classLoader() {
        return DisallowedHttpMethodFilterOrderTest.class.getClassLoader();
    }
}
