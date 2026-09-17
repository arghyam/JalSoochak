package org.arghyam.jalsoochak.scheme.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.arghyam.jalsoochak.scheme.config.properties.AppProperties;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies the Spring lifecycle half of the Single Tenant Mode invariant: that the container
 * actually invokes the validator's package-private {@code @PostConstruct} and that the resulting
 * {@link IllegalStateException} aborts context refresh — i.e. the deployment does not come up.
 *
 * <p>scheme-service needs this independently of the other services: it performs the same
 * SUPER_STATE_ADMIN expansion, so an already-issued token would otherwise reach every tenant's
 * scheme data through this service even with tenant-service and user-service both down.
 */
@DisplayName("Single Tenant Mode - startup abort")
class SingleTenantModeStartupAbortTest {

    private static AppProperties singleTenantMode(boolean enabled) {
        AppProperties properties = new AppProperties();
        properties.setSingleTenantMode(enabled);
        return properties;
    }

    private static SchemeDbRepository repositoryWithActiveTenants(String... stateCodes) {
        SchemeDbRepository repository = mock(SchemeDbRepository.class);
        when(repository.findActiveTenantStateCodes()).thenReturn(List.of(stateCodes));
        when(repository.findDegradedTenantStateCodes()).thenReturn(List.of());
        return repository;
    }

    private ApplicationContextRunner contextWith(AppProperties properties, SchemeDbRepository repository) {
        return new ApplicationContextRunner()
                .withBean(AppProperties.class, () -> properties)
                .withBean(SchemeDbRepository.class, () -> repository)
                .withBean(SingleTenantModeStartupValidator.class);
    }

    @Test
    @DisplayName("context refresh fails when Single Tenant Mode is on with two ACTIVE tenants")
    void contextFailsToStart_whenSingleTenantModeHasMultipleActiveTenants() {
        contextWith(singleTenantMode(true), repositoryWithActiveTenants("MP", "UP"))
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("SINGLE_TENANT_MODE=false")
                        .hasMessageContaining("MP, UP"));
    }

    @Test
    @DisplayName("context starts when Single Tenant Mode is on with one ACTIVE tenant")
    void contextStarts_whenSingleTenantModeHasOneActiveTenant() {
        contextWith(singleTenantMode(true), repositoryWithActiveTenants("MP"))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(SingleTenantModeStartupValidator.class));
    }

    @Test
    @DisplayName("context starts in Multi Tenant Mode however many tenants are ACTIVE")
    void contextStarts_inMultiTenantModeRegardlessOfTenantCount() {
        contextWith(singleTenantMode(false), repositoryWithActiveTenants("MP", "RJ", "UP"))
                .run(context -> assertThat(context).hasNotFailed());
    }
}
