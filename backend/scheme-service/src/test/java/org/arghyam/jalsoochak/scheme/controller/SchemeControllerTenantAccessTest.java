package org.arghyam.jalsoochak.scheme.controller;

import org.arghyam.jalsoochak.scheme.config.SchemeSecurityEvaluator;
import org.arghyam.jalsoochak.scheme.dto.SchemeCountsDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusCountsDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusUpdateRequestDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeStatusesResponseDTO;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
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
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the {@code @PreAuthorize} expressions on {@link SchemeController} through a real
 * method-security proxy.
 *
 * <p>{@code SchemeSecurityEvaluatorTest} covers the access rule itself; this test covers the
 * wiring the rule depends on — that the {@code @schemeSecurity} bean name resolves, that the
 * {@code #tenantCode} / {@code #tenantId} parameter references bind, and that a denial stops the
 * call before the service (and therefore the database) is touched.
 *
 * <p>Built as a hand-rolled context rather than {@code @SpringBootTest} so that no datasource,
 * Eureka client or Kafka broker is required.
 */
@DisplayName("SchemeController - tenant access control")
class SchemeControllerTenantAccessTest {

    private static AnnotationConfigApplicationContext context;
    private static SchemeController controller;
    private static SchemeService schemeService;
    private static SchemeDbRepository schemeDbRepository;

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @BeforeAll
    static void startContext() {
        schemeService = mock(SchemeService.class);
        schemeDbRepository = mock(SchemeDbRepository.class);

        context = new AnnotationConfigApplicationContext();
        context.registerBean(SchemeService.class, () -> schemeService);
        context.registerBean(SchemeDbRepository.class, () -> schemeDbRepository);
        context.register(MethodSecurityTestConfig.class, SchemeSecurityEvaluator.class, SchemeController.class);
        context.refresh();

        controller = context.getBean(SchemeController.class);
    }

    @AfterAll
    static void closeContext() {
        context.close();
    }

    /** The context is built once for speed, so the shared mocks need clearing per test. */
    @BeforeEach
    void resetMocks() {
        reset(schemeService, schemeDbRepository);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("the evaluator is registered under the bean name the SpEL expressions reference")
    void evaluatorIsRegisteredAsSchemeSecurity() {
        assertThat(context.containsBean("schemeSecurity")).isTrue();
        assertThat(context.getBean("schemeSecurity")).isInstanceOf(SchemeSecurityEvaluator.class);
    }

    @Test
    @DisplayName("counts for the caller's own tenant are served")
    void getSchemeCounts_ownTenant_isServed() {
        authenticate(stateAdmin("AS"));
        SchemeCountsDTO counts = SchemeCountsDTO.builder().activeSchemes(4).inactiveSchemes(2).build();
        when(schemeService.getSchemeCounts("AS")).thenReturn(counts);

        assertThat(controller.getSchemeCounts("AS").getBody()).isEqualTo(counts);
    }

    @Test
    @DisplayName("counts for another tenant are denied before the service is reached")
    void getSchemeCounts_otherTenant_isDenied() {
        authenticate(stateAdmin("AS"));

        assertThatThrownBy(() -> controller.getSchemeCounts("UP"))
                .isInstanceOf(AccessDeniedException.class);
        verify(schemeService, never()).getSchemeCounts(any());
    }

    @Test
    @DisplayName("counts/by-status is guarded too — the endpoint that survives the counts removal")
    void getSchemeStatusCounts_otherTenant_isDenied() {
        authenticate(stateAdmin("AS"));

        assertThatThrownBy(() -> controller.getSchemeStatusCounts("UP"))
                .isInstanceOf(AccessDeniedException.class);
        verify(schemeService, never()).getSchemeStatusCounts(any());
    }

    @Test
    @DisplayName("the scheme list — the largest cross-tenant exposure — is guarded")
    void listSchemes_otherTenant_isDenied() {
        authenticate(stateAdmin("AS"));

        assertThatThrownBy(() -> controller.listSchemes(
                "UP", 0, 20, "id", "desc", null, null, null, null, null, null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("scheme mappings are guarded")
    void listSchemeMappings_otherTenant_isDenied() {
        authenticate(stateAdmin("AS"));

        assertThatThrownBy(() -> controller.listSchemeMappings(
                "UP", 0, 20, "id", "desc", null, null, null, null, null, null, null))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("yesterday-final-readings is guarded for Section Officer staff tokens")
    void yesterdayFinalReadings_staffToken_isScopedToOwnTenant() {
        authenticate(token(claims("AS"), "ROLE_STAFF"));

        assertThatThrownBy(() -> controller.listSchemesWithYesterdayFinalReading("UP", 0, 20, null))
                .isInstanceOf(AccessDeniedException.class);

        assertThatCode(() -> controller.listSchemesWithYesterdayFinalReading("AS", 0, 20, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the tenantId-addressed statuses endpoint resolves the tenant before deciding")
    void getSchemeStatuses_otherTenantId_isDenied() {
        authenticate(stateAdmin("AS"));
        when(schemeDbRepository.findSchemaNameByTenantId(9)).thenReturn("tenant_up");

        assertThatThrownBy(() -> controller.getSchemeStatuses(1, 9))
                .isInstanceOf(AccessDeniedException.class);
        verify(schemeService, never()).getSchemeStatuses(any(), anyInt());
    }

    @Test
    @DisplayName("the tenantId-addressed statuses endpoint serves the caller's own tenant")
    void getSchemeStatuses_ownTenantId_isServed() {
        authenticate(stateAdmin("AS"));
        when(schemeDbRepository.findSchemaNameByTenantId(7)).thenReturn("tenant_as");
        SchemeStatusesResponseDTO statuses = new SchemeStatusesResponseDTO();
        when(schemeService.getSchemeStatuses(7, 1)).thenReturn(statuses);

        assertThat(controller.getSchemeStatuses(1, 7).getBody()).isEqualTo(statuses);
    }

    @Test
    @DisplayName("the status update keeps its STATE_ADMIN role check and gains the tenant check")
    void updateSchemeStatuses_keepsRoleCheckAndAddsTenantCheck() {
        SchemeStatusUpdateRequestDTO request = new SchemeStatusUpdateRequestDTO();

        // Right role, wrong tenant.
        authenticate(stateAdmin("AS"));
        assertThatThrownBy(() -> controller.updateSchemeStatuses("UP", 1, request))
                .isInstanceOf(AccessDeniedException.class);

        // Right tenant, wrong role.
        authenticate(token(claims("AS"), "ROLE_STAFF"));
        assertThatThrownBy(() -> controller.updateSchemeStatuses("AS", 1, request))
                .isInstanceOf(AccessDeniedException.class);

        // Both correct.
        authenticate(stateAdmin("AS"));
        assertThatCode(() -> controller.updateSchemeStatuses("AS", 1, request))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SUPER_USER keeps its national cross-tenant reach")
    void superUser_reachesAnyTenant() {
        authenticate(token(Map.of("sub", "national-admin"), "ROLE_SUPER_USER"));
        when(schemeService.getSchemeCounts("UP")).thenReturn(SchemeCountsDTO.builder().build());

        assertThatCode(() -> controller.getSchemeCounts("UP")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SUPER_STATE_ADMIN stays scoped to its own tenant in multi-tenant mode")
    void superStateAdmin_multiTenantMode_isScopedToOwnTenant() {
        authenticate(token(claims("AS"), "ROLE_SUPER_STATE_ADMIN"));
        when(schemeService.getSchemeStatusCounts("AS")).thenReturn(SchemeStatusCountsDTO.builder().build());

        assertThatCode(() -> controller.getSchemeStatusCounts("AS")).doesNotThrowAnyException();
        assertThatThrownBy(() -> controller.getSchemeStatusCounts("UP"))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("SUPER_STATE_ADMIN reaches any tenant in Single Tenant Mode, where the converter expands it")
    void superStateAdmin_singleTenantMode_isAllowedThrough() {
        authenticate(token(claims("AS"),
                "ROLE_SUPER_STATE_ADMIN", "ROLE_SUPER_USER", "ROLE_STATE_ADMIN"));
        when(schemeService.getSchemeStatusCounts("UP")).thenReturn(SchemeStatusCountsDTO.builder().build());

        assertThatCode(() -> controller.getSchemeStatusCounts("UP")).doesNotThrowAnyException();
    }

    // --- helpers -------------------------------------------------------------------------

    private static void authenticate(Authentication authentication) {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private static Authentication stateAdmin(String tenantStateCode) {
        return token(claims(tenantStateCode), "ROLE_STATE_ADMIN");
    }

    private static Map<String, Object> claims(String tenantStateCode) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "user-uuid");
        claims.put("tenant_state_code", tenantStateCode);
        return claims;
    }

    private static Authentication token(Map<String, Object> claims, String... authorities) {
        Jwt jwt = new Jwt(
                "token-value",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "none"),
                claims);
        Set<GrantedAuthority> granted = new HashSet<>();
        for (String authority : authorities) {
            granted.add(new SimpleGrantedAuthority(authority));
        }
        return new JwtAuthenticationToken(jwt, granted, "user-uuid");
    }
}
