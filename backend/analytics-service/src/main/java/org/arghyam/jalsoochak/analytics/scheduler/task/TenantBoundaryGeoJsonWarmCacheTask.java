package org.arghyam.jalsoochak.analytics.scheduler.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.entity.DimLgdLocation;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.repository.DimLgdLocationRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.service.TenantDetailsService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Warms Redis cache for the tenant boundaries API (tenant_boundaries) so that the first UI request
 * for a tenant's level-1 LGD can be served from cache.
 *
 * <p>This endpoint has no date window; warm-cache still runs on the common schedule to keep keys hot.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantBoundaryGeoJsonWarmCacheTask implements AnalyticsScheduledTask {

    private static final int LEVEL_1_LGD_LEVEL = 1;

    private final DimTenantRepository dimTenantRepository;
    private final DimLgdLocationRepository dimLgdLocationRepository;
    private final TenantDetailsService tenantDetailsService;

    @Override
    public String taskName() {
        return "tenant-boundaries-warm-cache";
    }

    @Override
    @Scheduled(
            cron = "${analytics.scheduler.common.cron:0 0 0 * * *}",
            zone = "${analytics.scheduler.common.zone:Asia/Kolkata}")
    public void runTask() {
        log.info("Scheduler START '{}'", taskName());

        List<DimTenant> tenants = dimTenantRepository.findByTenantIdGreaterThan(0);
        log.info("Running scheduled task '{}' for {} tenants", taskName(), tenants.size());

        for (DimTenant tenant : tenants) {
            Integer tenantId = tenant != null ? tenant.getTenantId() : null;
            if (tenantId == null || tenantId <= 0) {
                continue;
            }
            try {
                DimLgdLocation level1 = dimLgdLocationRepository
                        .findFirstByTenantIdAndLgdLevelOrderByLgdIdAsc(tenantId, LEVEL_1_LGD_LEVEL)
                        .orElse(null);
                Integer level1LgdId = level1 != null ? level1.getLgdId() : null;
                if (level1LgdId == null || level1LgdId <= 0) {
                    continue;
                }

                // This method path writes the Redis API cache (TenantDetailsServiceImpl).
                tenantDetailsService.getTenantBoundaryGeoJson(tenantId, level1LgdId);

                log.info("Warm-cache completed for tenant_id={}, level1_lgd_id={}", tenantId, level1LgdId);
            } catch (Exception ex) {
                log.warn("Warm-cache failed for tenant_id={}", tenantId, ex);
            }
        }

        log.info("Completed scheduled task '{}'", taskName());
        log.info("Scheduler END '{}'", taskName());
    }
}

