package org.arghyam.jalsoochak.analytics.scheduler.task;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.entity.DimLgdLocation;
import org.arghyam.jalsoochak.analytics.enums.PeriodScale;
import org.arghyam.jalsoochak.analytics.repository.DimLgdLocationRepository;
import org.arghyam.jalsoochak.analytics.service.SchemeRegularityService;
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
    private static final int LOOKBACK_DAYS = 30;

    private final DimLgdLocationRepository dimLgdLocationRepository;
    private final SchemeRegularityService schemeRegularityService;

    @Override
    public String taskName() {
        return "lgd-state-warm-cache";
    }

    @Override
    @Scheduled(
            cron = "${analytics.scheduler.common.cron:0 0 19 * * *}",
            zone = "${analytics.scheduler.common.zone:Asia/Kolkata}")
    public void runTask() {
        LocalDate endDate = LocalDate.now(IST_ZONE);
        LocalDate startDate = endDate.minusDays(LOOKBACK_DAYS);
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
                schemeRegularityService.getAverageSchemeRegularity(lgdId, startDate, endDate);
                // Warm-cache: average scheme regularity for this state's immediate child regions (scope=child, last 30 days).
                schemeRegularityService.getAverageSchemeRegularityForChildRegions(lgdId, startDate, endDate);
                // Warm-cache: reading submission rate for this state LGD (scope=current, last 30 days).
                schemeRegularityService.getReadingSubmissionRateByLgd(lgdId, startDate, endDate);
                // Warm-cache: reading submission rate for this state's immediate child regions (scope=child, last 30 days).
                schemeRegularityService.getReadingSubmissionRateByLgdForChildRegions(lgdId, startDate, endDate);
                // Warm-cache: child-region-wise water quantity and household metrics under this state (last 30 days).
                schemeRegularityService.getRegionWiseWaterQuantityByLgd(lgdId, startDate, endDate);
                // Warm-cache: outage reason distribution (overall + child regions) under this state (last 30 days).
                schemeRegularityService.getOutageReasonSchemeCountByLgd(lgdId, startDate, endDate);
                // Warm-cache: non-submission reason distribution (overall + child regions) under this state (last 30 days).
                schemeRegularityService.getNonSubmissionReasonSchemeCountByLgd(lgdId, startDate, endDate);
                // Warm-cache: schemes dashboard (active/inactive counts + top schemes by reporting rate) for this state (last 30 days).
                schemeRegularityService.getSchemeStatusAndTopReportingByLgd(lgdId, startDate, endDate, null);
                // Warm the common paginated view (page 1, default count).
                // Warm-cache: schemes region report (page 1, default count) for this state (last 30 days).
                schemeRegularityService.getSchemeRegionReportByLgd(lgdId, startDate, endDate, 1, null);

                // lgd_id style APIs
                // Warm-cache: periodic water quantity time series for this state (last 30 days).
                schemeRegularityService.getPeriodicWaterQuantityByLgdId(lgdId, startDate, endDate, scale);
                // Warm-cache: periodic scheme regularity time series for this state (last 30 days).
                schemeRegularityService.getPeriodicSchemeRegularityByLgdId(lgdId, startDate, endDate, scale);
                // Warm-cache: periodic outage reason time series for this state (last 30 days).
                schemeRegularityService.getPeriodicOutageReasonSchemeCountByLgdId(lgdId, startDate, endDate, scale);
                // Warm-cache: submission status summary (scheme count + compliant/anomalous submissions) for this state (last 30 days).
                schemeRegularityService.getSubmissionStatusSummaryByLgd(lgdId, startDate, endDate);
                // Warm-cache: scheme status counts (active/inactive) for this state (not date-ranged).
                schemeRegularityService.getSchemeStatusCountByLgd(lgdId);

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
                log.warn("Warm-cache failed for state lgd_id={}, tenant_id={}: {}", lgdId, tenantId, ex.getMessage());
            }
        }

        log.info("Completed scheduled task '{}' for range {} to {}", taskName(), startDate, endDate);
    }
}

