package org.arghyam.jalsoochak.analytics.scheduler.task;

import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NationalDashboardRefreshTaskSingleTenantModeConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void whenSingleTenantModeTrue_taskBeanIsNotCreated() {
        contextRunner
                .withPropertyValues("analytics.single-tenant-mode=true")
                .run(context -> assertThat(context).doesNotHaveBean(NationalDashboardRefreshTask.class));
    }

    @Test
    void whenSingleTenantModeFalse_taskBeanIsCreated() {
        contextRunner
                .withPropertyValues("analytics.single-tenant-mode=false")
                .run(context -> assertThat(context).hasSingleBean(NationalDashboardRefreshTask.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(NationalDashboardRefreshTask.class)
    static class TestConfig {
        @org.springframework.context.annotation.Bean
        SchemeRegularityService schemeRegularityService() {
            return mock(SchemeRegularityService.class);
        }
    }
}

