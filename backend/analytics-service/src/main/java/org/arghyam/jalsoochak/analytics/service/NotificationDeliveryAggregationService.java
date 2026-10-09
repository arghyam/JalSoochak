package org.arghyam.jalsoochak.analytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.repository.NotificationDeliveryAggregationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Rebuilds the daily notification delivery and failure rollups from the fact table, see
 * {@link NotificationDeliveryAggregationRepository}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationDeliveryAggregationService {

    private final NotificationDeliveryAggregationRepository repository;

    /**
     * Recomputes both rollups for dispatch days [{@code from}, {@code to}] in one transaction.
     * Skipped when another pod holds the rollup lock: every pod fires the same cron, and one run is
     * enough.
     */
    @Transactional
    public void recompute(LocalDate from, LocalDate to) {
        if (!repository.tryLock()) {
            log.info("[notification-aggregation] skipped window {}..{}: another instance is aggregating", from, to);
            return;
        }
        int deliveryRows = repository.upsertDeliveryDaily(from, to);
        int deliveryRemoved = repository.deleteVanishedDeliveryDaily(from, to);
        int failureRows = repository.upsertFailureDaily(from, to);
        int failureRemoved = repository.deleteVanishedFailureDaily(from, to);
        log.info("[notification-aggregation] window {}..{} deliveryRows={} deliveryRemoved={} failureRows={} "
                        + "failureRemoved={}",
                from, to, deliveryRows, deliveryRemoved, failureRows, failureRemoved);
    }
}
