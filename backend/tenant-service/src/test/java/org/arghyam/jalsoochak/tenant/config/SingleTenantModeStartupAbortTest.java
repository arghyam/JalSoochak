package org.arghyam.jalsoochak.tenant.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.arghyam.jalsoochak.tenant.config.properties.AppProperties;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies the Spring lifecycle half of the Single Tenant Mode invariant: that the container
 * actually invokes the validator's package-private {@code @PostConstruct} and that the resulting
 * {@link IllegalStateException} aborts context refresh — i.e. the deployment does not come up.
 *
 * <p>{@link SingleTenantModeStartupValidatorTest} covers the decision logic by calling the method
 * directly; only a real context can prove the annotation is wired and that startup aborts.
 */
@DisplayName("Single Tenant Mode - startup abort")
class SingleTenantModeStartupAbortTest {

    private static AppProperties singleTenantMode(boolean enabled) {
        AppProperties properties = new AppProperties();
        properties.setSingleTenantMode(enabled);
        return properties;
    }

    private static TenantCommonRepository repositoryWithActiveTenants(String... stateCodes) {
        TenantCommonRepository repository = mock(TenantCommonRepository.class);
        when(repository.findActiveTenantStateCodes()).thenReturn(List.of(stateCodes));
        when(repository.findDegradedTenantStateCodes()).thenReturn(List.of());
        return repository;
    }

    private ApplicationContextRunner contextWith(AppProperties properties, TenantCommonRepository repository) {
        return new ApplicationContextRunner()
                .withBean(AppProperties.class, () -> properties)
                .withBean(TenantCommonRepository.class, () -> repository)
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
