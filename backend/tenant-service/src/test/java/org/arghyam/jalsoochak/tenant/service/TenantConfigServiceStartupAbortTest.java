package org.arghyam.jalsoochak.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Verifies the Spring lifecycle half of the schedule-defaults check: that the container invokes
 * {@link TenantConfigService}'s package-private {@code @PostConstruct} and that an unusable default
 * aborts context refresh, so the service does not come up.
 *
 * <p>{@link TenantConfigServiceTest} covers which defaults are refused by calling the method
 * directly; only a real context can prove the annotation is wired and that startup aborts.</p>
 */
@DisplayName("Schedule defaults - startup abort")
class TenantConfigServiceStartupAbortTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(TenantConfigService.class);

    @Test
    @DisplayName("context refresh fails when a schedule default is out of range")
    void contextFailsToStart_whenADefaultIsOutOfRange() {
        contextRunner
                .withPropertyValues("daily-report.schedule.hour=25")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Invalid default in daily-report.schedule.*")
                            .hasRootCauseMessage("hour must be in [0,23]");
                });
    }

    @Test
    @DisplayName("context starts on usable defaults")
    void contextStarts_whenDefaultsAreUsable() {
        contextRunner
                .withPropertyValues("daily-report.schedule.hour=17", "weekly-report.schedule.day-of-week=0")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
