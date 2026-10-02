package org.arghyam.jalsoochak.tenant.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Scheduling stub retained for source compatibility with callers that previously
 * invoked {@link #rescheduleForTenant} after a tenant config update.
 *
 * <p>All per-tenant cron scheduling has been moved to Kubernetes {@code CronJob}
 * resources. The K8s CronJobs hit {@code POST /internal/jobs/{job}} on this service
 * pod, which iterates all active tenants via
 * {@link org.arghyam.jalsoochak.tenant.controller.JobTriggerController}.</p>
 *
 * <p>Because the schedule is now managed externally, there is nothing to reschedule
 * at runtime when a tenant's config changes — the next CronJob run will automatically
 * pick up the updated values from {@code tenant_config_master_table}. The method is
 * therefore a no-op and can be removed together with its call sites once callers are
 * updated.</p>
 */
@Component
@Slf4j
public class TenantSchedulerManager {

    /**
     * No-op. Previously cancelled and rebuilt all four per-tenant scheduled futures;
     * scheduling is now owned by K8s CronJobs which pick up DB config changes on
     * their next natural fire.
     *
     * @param tenantId  the tenant whose config was just updated (informational only)
     * @param stateCode the tenant's state code (informational only)
     */
    public void rescheduleForTenant(int tenantId, String stateCode) {
        log.info("[TenantSchedulerManager] rescheduleForTenant called for tenant={} stateCode={} — "
                + "no-op: scheduling is now managed by K8s CronJobs.", tenantId, stateCode);
    }
}
