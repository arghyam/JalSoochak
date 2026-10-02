package org.arghyam.jalsoochak.tenant.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Smoke test confirming {@link TenantSchedulerManager#rescheduleForTenant} is a
 * no-op and does not throw. The detailed scheduling behaviour previously tested here
 * has moved to K8s CronJobs; the controller logic is tested in
 * {@link org.arghyam.jalsoochak.tenant.controller.JobTriggerControllerTest}.
 */
class TenantSchedulerManagerTest {

    private final TenantSchedulerManager manager = new TenantSchedulerManager();

    @Test
    void rescheduleForTenant_doesNotThrow() {
        assertThatCode(() -> manager.rescheduleForTenant(1, "MP")).doesNotThrowAnyException();
    }
}
