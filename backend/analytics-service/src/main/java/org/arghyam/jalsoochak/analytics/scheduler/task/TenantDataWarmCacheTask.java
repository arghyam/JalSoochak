package org.arghyam.jalsoochak.analytics.scheduler.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.entity.DimLgdLocation;
import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.repository.DimLgdLocationRepository;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.arghyam.jalsoochak.analytics.service.TenantDetailsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Warms Redis cache for the tenant boundary API (tenant_data) so that the first UI request
 * for a tenant's level-1 LGD can be served from cache.
 *
 * <p>Date window is anchored to "yesterday" in the configured scheduler zone to ensure the warmed
 * keys remain stable across the 7PM→7PM warm-cache cycle.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TenantDataWarmCacheTask implements AnalyticsScheduledTask {

    private static final int LEVEL_1_LGD_LEVEL = 1;

    private final DimTenantRepository dimTenantRepository;
    private final DimLgdLocationRepository dimLgdLocationRepository;
    private final TenantDetailsService tenantDetailsService;

    @Value("${analytics.scheduler.common.zone:Asia/Kolkata}")
    private String schedulerZone;

    @Value("${analytics.scheduler.national-dashboard.lookback-days:30}")
    private int lookbackDays;

    @Override
    public String taskName() {
        return "tenant-data-warm-cache";
    }

    @Override
    @Scheduled(
            cron = "${analytics.scheduler.common.cron:0 0 19 * * *}",
            zone = "${analytics.scheduler.common.zone:Asia/Kolkata}")
    public void runTask() {
        ZoneId zone = ZoneId.of(schedulerZone);
        int sanitizedLookbackDays = Math.max(1, lookbackDays);

        // Stable inclusive window: [end-(N-1), end], with end anchored to yesterday.
        LocalDate endDate = LocalDate.now(zone).minusDays(1);
        LocalDate startDate = endDate.minusDays(sanitizedLookbackDays - 1L);

        List<DimTenant> tenants = dimTenantRepository.findByTenantIdGreaterThan(0);
        log.info("Running scheduled task '{}' for {} tenants, range {} to {}",
                taskName(), tenants.size(), startDate, endDate);

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
                tenantDetailsService.getTenantDetailsWithAggregatedMetrics(
                        tenantId, level1LgdId, startDate, endDate);

                log.info("Warm-cache completed for tenant_id={}, level1_lgd_id={}, range {} to {}",
                        tenantId, level1LgdId, startDate, endDate);
            } catch (Exception ex) {
                log.warn("Warm-cache failed for tenant_id={}: {}", tenantId, ex.getMessage());
            }
        }

        log.info("Completed scheduled task '{}' for range {} to {}", taskName(), startDate, endDate);
    }
}

