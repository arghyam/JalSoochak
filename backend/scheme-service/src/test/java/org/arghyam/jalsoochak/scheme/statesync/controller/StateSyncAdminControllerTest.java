package org.arghyam.jalsoochak.scheme.statesync.controller;

import org.arghyam.jalsoochak.scheme.config.SchemeSecurityEvaluator;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.arghyam.jalsoochak.scheme.statesync.config.StateSyncProperties;
import org.arghyam.jalsoochak.scheme.statesync.run.RunKind;
import org.arghyam.jalsoochak.scheme.statesync.run.StateSyncRunRepository;
import org.arghyam.jalsoochak.scheme.statesync.run.StateSyncRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@link StateSyncAdminController} through a real method-security proxy (no datasource needed). */
class StateSyncAdminControllerTest {

    private static AnnotationConfigApplicationContext context;
    private static StateSyncAdminController controller;
    private static StateSyncRunner runner;
    private static StateSyncRunRepository runRepository;
    private static final StateSyncProperties properties = new StateSyncProperties();

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityTestConfig {
    }

    @BeforeAll
    static void startContext() {
        runner = mock(StateSyncRunner.class);
        runRepository = mock(StateSyncRunRepository.class);
        context = new AnnotationConfigApplicationContext();
        context.registerBean(StateSyncProperties.class, () -> properties);
        context.registerBean(StateSyncRunner.class, () -> runner);
        context.registerBean(StateSyncRunRepository.class, () -> runRepository);
        context.registerBean(SchemeDbRepository.class, () -> mock(SchemeDbRepository.class));
        context.register(MethodSecurityTestConfig.class, SchemeSecurityEvaluator.class, StateSyncAdminController.class);
        context.refresh();
        controller = context.getBean(StateSyncAdminController.class);
    }

    @AfterAll
    static void closeContext() {
        context.close();
    }

    @BeforeEach
    void setUp() {
        reset(runner, runRepository);
        properties.setEnabled(true);
        properties.setTenantCode("as");
        authenticate("AS", "ROLE_STATE_ADMIN");
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void aStateAdminOfTheTenantStartsARun() {
        when(runner.startAsync(eq(RunKind.FULL), anyString())).thenReturn(Optional.of(42L));

        var response = controller.startRun("AS", RunKind.FULL, SecurityContextHolder.getContext().getAuthentication());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("runId", 42L);
        verify(runner).startAsync(RunKind.FULL, "ADMIN:user-uuid");
    }

    @Test
    void aHeldLockIsAConflict() {
        when(runner.startAsync(any(), anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.startRun("as", RunKind.DELTA, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void anotherTenantsAdminIsDenied() {
        authenticate("UP", "ROLE_STATE_ADMIN");

        assertThatThrownBy(() -> controller.startRun("AS", RunKind.FULL, null)).isInstanceOf(AccessDeniedException.class);
        verify(runner, never()).startAsync(any(), anyString());
    }

    @Test
    void anOrdinaryStaffRoleIsDenied() {
        authenticate("AS", "ROLE_SECTION_OFFICER");

        assertThatThrownBy(() -> controller.issues("AS", null, null, 10, 0)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aTenantTheSyncIsNotConfiguredForIsNotFound() {
        authenticate("UP", "ROLE_SUPER_USER");

        assertThatThrownBy(() -> controller.startRun("UP", RunKind.FULL, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void aSchemeRefreshCannotBeStartedAsABackgroundRun() {
        assertThatThrownBy(() -> controller.startRun("AS", RunKind.SCHEME_REFRESH, null))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void theDisabledFlagIsAConflictWithAReason() {
        var response = controller.disabled(new StateSyncRunner.SyncDisabledException());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).containsEntry("message", "State sync is disabled (state-sync.enabled=false)");
    }

    @Test
    void listsIssuesWithClampedPaging() {
        when(runRepository.findTenant("AS")).thenReturn(Optional.of(new StateSyncRunRepository.Tenant(1, "AS", "tenant_as")));
        when(runRepository.listIssues(1, 7L, "LGD_UNMATCHED", 1000, 0)).thenReturn(List.of());

        assertThat(controller.issues("AS", 7L, "LGD_UNMATCHED", 50_000, -5)).isEmpty();
        verify(runRepository).listIssues(1, 7L, "LGD_UNMATCHED", 1000, 0);
    }

    @Test
    void reportsTheEffectiveConfiguration() {
        assertThat(controller.config("AS")).containsEntry("enabled", true).containsEntry("mode", StateSyncProperties.Mode.DRY_RUN)
                .containsKeys("deltaCron", "fullCron");
    }

    private static void authenticate(String tenant, String role) {
        Jwt jwt = new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"),
                Map.of("sub", "user-uuid", "tenant_state_code", tenant));
        Authentication auth = new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)), "user-uuid");
        SecurityContextHolder.getContext().setAuthentication(auth);
    }
}
