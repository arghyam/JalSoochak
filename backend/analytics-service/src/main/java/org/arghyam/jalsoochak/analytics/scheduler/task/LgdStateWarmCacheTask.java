package org.arghyam.jalsoochak.analytics.scheduler.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.entity.DimLgdLocation;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.repository.DimLgdLocationRepository;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class LgdStateWarmCacheTask implements AnalyticsScheduledTask {

    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final int STATE_LGD_LEVEL = 1;
    /**
     * Warm-cache runs at midnight IST (the shared scheduler cron) and serves the same cached
     * data until the next run. The date window is anchored to "yesterday" (IST) for a stable
     * range that does not drift during the day.
     *
     * Window size is inclusive of both start and end dates.
     */
    @Value("${analytics.scheduler.national-dashboard.lookback-days:30}")
    private int lookbackDays;

    private final DimLgdLocationRepository dimLgdLocationRepository;
    private final SchemeRegularityService schemeRegularityService;

    @Override
    public String taskName() {
        return "lgd-state-warm-cache";
    }

    @Override
    @Scheduled(
            cron = "${analytics.scheduler.common.cron:0 0 0 * * *}",
            zone = "${analytics.scheduler.common.zone:Asia/Kolkata}")
    public void runTask() {
        log.info("Scheduler START '{}'", taskName());
        int sanitizedLookbackDays = Math.max(1, lookbackDays);
        // Stable 30-day window ending yesterday (IST): [end-29, end]
        LocalDate endDate = LocalDate.now(IST_ZONE).minusDays(1);
        LocalDate startDate = endDate.minusDays(sanitizedLookbackDays - 1L);
        PeriodScale scale = PeriodScale.DAY;

        List<DimLgdLocation> states = dimLgdLocationRepository.findByLgdLevel(STATE_LGD_LEVEL);
        log.info("Running scheduled task '{}' for {} states, range {} to {}, scale={}",
                taskName(), states.size(), startDate, endDate, scale.name().toLowerCase());

        for (DimLgdLocation state : states) {
            Integer lgdId = state.getLgdId();
            Integer tenantId = state.getTenantId();
            if (lgdId == null || tenantId == null) {
                continue;
            }
            try {
                // parent_lgd_id style APIs
                // Warm-cache: average scheme regularity for this state LGD (scope=current, last 30 days).
                schemeRegularityService.getAverageSchemeRegularity(tenantId, lgdId, startDate, endDate);
                // Warm-cache: average scheme regularity for this state's immediate child regions (scope=child, last 30 days).
                schemeRegularityService.getAverageSchemeRegularityForChildRegions(tenantId, lgdId, startDate, endDate);
                // Warm-cache: reading submission rate for this state LGD (scope=current, last 30 days).
                schemeRegularityService.getReadingSubmissionRateByLgd(tenantId, lgdId, startDate, endDate);
                // Warm-cache: reading submission rate for this state's immediate child regions (scope=child, last 30 days).
                schemeRegularityService.getReadingSubmissionRateByLgdForChildRegions(tenantId, lgdId, startDate, endDate);
                // Warm-cache: child-region-wise water quantity and household metrics under this state (last 30 days).
                schemeRegularityService.getRegionWiseWaterQuantityByLgd(tenantId, lgdId, startDate, endDate);
                // Warm-cache: outage reason distribution (overall + child regions) under this state (last 30 days).
                schemeRegularityService.getOutageReasonSchemeCountByLgd(tenantId, lgdId, startDate, endDate);
                // Warm-cache: non-submission reason distribution (overall + child regions) under this state (last 30 days).
                schemeRegularityService.getNonSubmissionReasonSchemeCountByLgd(tenantId, lgdId, startDate, endDate);
                // Warm-cache: schemes dashboard (work/operating status breakdowns + top schemes by reporting rate) for this state (last 30 days).
                schemeRegularityService.getSchemeStatusAndTopReportingByLgd(
                        tenantId, lgdId, startDate, endDate, 1, null, null, null);
                // Warm the common paginated view (page 1, default count).
                // Warm-cache: schemes region report (page 1, default count) for this state (last 30 days).
                schemeRegularityService.getSchemeRegionReportByLgd(tenantId, lgdId, startDate, endDate, 1, null);

                // lgd_id style APIs
                // Warm-cache: periodic water quantity time series for this state (last 30 days).
                schemeRegularityService.getPeriodicWaterQuantityByLgdId(lgdId, startDate, endDate, scale);
                // Warm-cache: periodic scheme regularity time series for this state (last 30 days).
                schemeRegularityService.getPeriodicSchemeRegularityByLgdId(tenantId, lgdId, startDate, endDate, scale);
                // Warm-cache: periodic outage reason time series for this state (last 30 days).
                schemeRegularityService.getPeriodicOutageReasonSchemeCountByLgdId(tenantId, lgdId, startDate, endDate, scale);
                // Warm-cache: submission status summary (scheme count + compliant/anomalous submissions) for this state (last 30 days).
                schemeRegularityService.getSubmissionStatusSummaryByLgd(tenantId, lgdId, startDate, endDate);
                // Warm-cache: scheme counts by work status and operating status for this state (not date-ranged).
                schemeRegularityService.getSchemeStatusCountByLgd(tenantId, lgdId);

                // water-supply requires tenant_id + parent_lgd_id for child scope
                // Warm-cache: average water-supply per child region under this state (scope=child, last 30 days).
                schemeRegularityService.getAverageWaterSupplyPerCurrentRegionByLgdForChildScope(
                        tenantId, lgdId, startDate, endDate);

                log.info(
                        "Warm-cache completed for state lgd_id={}, tenant_id={}, range {} to {}, scale={}",
                        lgdId,
                        tenantId,
                        startDate,
                        endDate,
                        scale.name().toLowerCase());
            } catch (Exception ex) {
                log.warn("Warm-cache failed for state lgd_id={}, tenant_id={}", lgdId, tenantId, ex);
            }
        }

        log.info("Completed scheduled task '{}' for range {} to {}", taskName(), startDate, endDate);
        log.info("Scheduler END '{}'", taskName());
    }
}
