package org.arghyam.jalsoochak.scheme.controller;

import org.arghyam.jalsoochak.scheme.dto.SchemeAnalyticsResyncResponseDTO;
import org.arghyam.jalsoochak.scheme.service.SchemeService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Calls {@link SchemeAnalyticsResyncController} through a real method-security proxy, so its
 * {@code @PreAuthorize} role check runs. The caller's tenant is checked by the service, and
 * {@code SchemeAnalyticsResyncTest} covers that.
 *
 * <p>Built as a hand-rolled context rather than {@code @SpringBootTest} so that no datasource,
 * Eureka client or Kafka broker is required.
 */
@DisplayName("SchemeAnalyticsResyncController")
class SchemeAnalyticsResyncControllerTest {

    private static AnnotationConfigApplicationContext context;
    private static SchemeAnalyticsResyncController controller;
    private static SchemeService schemeService;

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @BeforeAll
    static void startContext() {
        schemeService = mock(SchemeService.class);

        context = new AnnotationConfigApplicationContext();
        context.registerBean(SchemeService.class, () -> schemeService);
        context.register(MethodSecurityTestConfig.class, SchemeAnalyticsResyncController.class);
        context.refresh();

        controller = context.getBean(SchemeAnalyticsResyncController.class);
    }

    @AfterAll
    static void closeContext() {
        context.close();
    }

    /** The context is built once for speed, so the shared mock needs clearing per test. */
    @BeforeEach
    void resetMocks() {
        reset(schemeService);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("a state admin's resync goes to the service and its counts come back")
    void stateAdmin_isServed() {
        authenticate("ROLE_STATE_ADMIN");
        SchemeAnalyticsResyncResponseDTO counts = SchemeAnalyticsResyncResponseDTO.builder()
                .totalSchemes(2)
                .sentSchemes(2)
                .build();
        when(schemeService.resyncSchemesToAnalytics()).thenReturn(counts);

        assertThat(controller.resyncSchemesToAnalytics().getBody()).isEqualTo(counts);
    }

    @Test
    @DisplayName("a super state admin may resync too")
    void superStateAdmin_isServed() {
        authenticate("ROLE_SUPER_STATE_ADMIN");

        controller.resyncSchemesToAnalytics();

        verify(schemeService).resyncSchemesToAnalytics();
    }

    @Test
    @DisplayName("any other role is turned away before the service runs")
    void otherRole_isDenied() {
        authenticate("ROLE_STAFF");

        assertThatThrownBy(() -> controller.resyncSchemesToAnalytics())
                .isInstanceOf(AccessDeniedException.class);
        verify(schemeService, never()).resyncSchemesToAnalytics();
    }

    private static void authenticate(String authority) {
        SecurityContextHolder.clearContext();
        TestingAuthenticationToken authentication = new TestingAuthenticationToken("user-uuid", null, authority);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
