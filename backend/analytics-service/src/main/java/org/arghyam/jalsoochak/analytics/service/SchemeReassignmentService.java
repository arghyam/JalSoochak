package org.arghyam.jalsoochak.analytics.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.dto.event.SchemeReadingsReassignedEvent;
import org.arghyam.jalsoochak.analytics.repository.FactIngestionRepository;
import org.arghyam.jalsoochak.analytics.repository.SchemeReassignmentRepository;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityRecalculationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * Applies {@code SCHEME_READINGS_REASSIGNED}: a lenient-ingestion placeholder scheme's facts move to
 * the real scheme. In one transaction, holding both schemes' ingestion locks (so no reading event for
 * either can interleave): the meter-reading, attendance, anomaly and escalation facts move; each
 * affected day's water quantity is worked out again for the real scheme and removed from the
 * placeholder; the placeholder's daily aggregates and dim rows go. After commit the pre-aggregation
 * is rebuilt for the affected dates in the background.
 */
@Service
@Slf4j
public class SchemeReassignmentService {

    public record Outcome(List<LocalDate> dates, int attendanceMoved, int anomaliesMoved) {
    }

    private final SchemeReassignmentRepository repository;
    private final FactIngestionRepository factIngestionRepository;
    private final WaterQuantityRecalculationService waterQuantityRecalculationService;
    private final AggregationRefreshQueue aggregationRefreshQueue;
    private final TransactionTemplate transaction;

    public SchemeReassignmentService(SchemeReassignmentRepository repository,
                                     FactIngestionRepository factIngestionRepository,
                                     WaterQuantityRecalculationService waterQuantityRecalculationService,
                                     AggregationRefreshQueue aggregationRefreshQueue,
                                     PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.factIngestionRepository = factIngestionRepository;
        this.waterQuantityRecalculationService = waterQuantityRecalculationService;
        this.aggregationRefreshQueue = aggregationRefreshQueue;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public Outcome reassign(SchemeReadingsReassignedEvent event) {
        Integer tenantId = event.getTenantId();
        Integer from = event.getFromSchemeId();
        Integer to = event.getToSchemeId();
        if (tenantId == null || from == null || to == null || from.equals(to)) {
            log.warn("Ignoring SCHEME_READINGS_REASSIGNED with tenantId={} from={} to={}", tenantId, from, to);
            return new Outcome(List.of(), 0, 0);
        }
        Outcome outcome = transaction.execute(status -> {
            factIngestionRepository.lockSchemes(tenantId, Set.of(from, to));
            List<LocalDate> dates = repository.moveMeterReadings(tenantId, from, to);
            int attendance = repository.moveAttendance(tenantId, from, to);
            int anomalies = repository.moveAnomaliesAndEscalations(tenantId, from, to);
            for (LocalDate date : dates) {
                waterQuantityRecalculationService.recalculateAfterRemoval(tenantId, from, date);
                waterQuantityRecalculationService.recalculateAfterReading(tenantId, to, date);
            }
            repository.dropScheme(tenantId, from);
            return new Outcome(dates, attendance, anomalies);
        });
        log.info("Reassigned scheme {} -> {} (tenantId={}): {} reading day(s), {} attendance row(s), {} anomaly/escalation row(s)",
                from, to, tenantId, outcome.dates().size(), outcome.attendanceMoved(), outcome.anomaliesMoved());
        if (!outcome.dates().isEmpty()) {
            aggregationRefreshQueue.refresh(outcome.dates().get(0), outcome.dates().get(outcome.dates().size() - 1),
                    "scheme " + from + " -> " + to + " tenant " + tenantId);
        }
        return outcome;
    }
}
