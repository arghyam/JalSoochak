package org.arghyam.jalsoochak.telemetry.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.channel.MeterRegister;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryLatestFlowReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.service.water.QuarantineReason;
import org.arghyam.jalsoochak.telemetry.service.water.SupplyPlausibilityGuard;
import org.arghyam.jalsoochak.telemetry.service.water.Verdict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * SUPPLY-PLAUSIBILITY: releases the quarantined readings the implausible-supply check accepts as it
 * stands now — readings quarantined under an earlier rule, or before their scheme's master data was
 * corrected.
 *
 * <p>Each reading is assessed again as {@code POST /readings} assesses a submission: against the
 * latest non-quarantined reading before its day, with the scheme's master data as it stands today. A
 * reading the check accepts, or cannot assess, has its marker cleared and is published to analytics
 * for the first time, as a passing correction is. A reading it still finds implausible stays
 * quarantined. The mode does not change this: the release replays the check.
 *
 * <p>A reading with no confirmed value is not assessed: releasing it would publish a reading without
 * one. It stays quarantined, is logged and is counted apart, and the run carries on.
 *
 * <p>Readings go oldest first, and each release is committed before the next reading is assessed, so a
 * released reading is the baseline its scheme's next reading is measured against, as it would have
 * been had it never been quarantined. For the same reason a range is released before the ranges after
 * it.
 *
 * <p>A dry run does the same inside a transaction it rolls back, and publishes nothing, so its counts
 * are the ones the release would give.
 *
 * <p>Each event waits for Kafka's acknowledgement on the calling thread, as {@link ReadingBackfillService}
 * does. A reading whose event is not acknowledged is quarantined again — the event may never arrive,
 * and a reading that is not quarantined is never picked up by a later release — and the run stops. A
 * publish that throws does the same, and the exception propagates.
 * Releasing the same range again carries on from there; should the event arrive after all, analytics
 * keeps the version the rerun sends.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuarantinedReadingReleaseService {

    private final TelemetryTenantRepository telemetryTenantRepository;
    private final TenantConfigRepository tenantConfigRepository;
    private final SupplyPlausibilityGuard supplyPlausibilityGuard;
    private final ReadingRepublisher readingRepublisher;
    private final TransactionTemplate transactionTemplate;

    /**
     * @param releasedCount         readings released: marker cleared and, outside a dry run, their
     *                              event acknowledged by Kafka
     * @param stillQuarantinedCount readings the check still finds implausible
     * @param noValueCount          readings left quarantined because they hold no confirmed value
     * @param notSentCount          readings left quarantined because Kafka stopped acknowledging: the
     *                              first it did not acknowledge and every one after it; 0 when the run
     *                              finished
     */
    public record Outcome(int releasedCount, int stillQuarantinedCount, int noValueCount, int notSentCount) {
    }

    /**
     * @param tenantCode the tenant's state code, in any case
     * @return the tenant's id; empty for an unknown or deleted tenant
     */
    public Optional<Integer> findTenantId(String tenantCode) {
        return tenantConfigRepository.findTenantIdByStateCode(tenantCode);
    }

    /**
     * @param stateSchemeId  looked up first, as on {@code POST /readings}; blank with a blank
     *                       {@code centreSchemeId} covers every scheme
     * @param centreSchemeId looked up when {@code stateSchemeId} is blank or matches no scheme
     * @param dryRun         assess and count without keeping any change or publishing
     * @throws ResponseStatusException 404 when a scheme id is given and neither matches a scheme
     */
    public Outcome release(Integer tenantId,
                           LocalDate fromDate,
                           LocalDate toDate,
                           String stateSchemeId,
                           String centreSchemeId,
                           boolean dryRun) {
        String schemaName = telemetryTenantRepository.findSchemaNameByTenantId(tenantId)
                .filter(schema -> !schema.isBlank())
                .orElseThrow(() -> new IllegalStateException("Tenant schema could not be resolved"));
        Long schemeId = resolveScheme(schemaName, stateSchemeId, centreSchemeId);

        Outcome outcome = dryRun
                ? transactionTemplate.execute(status -> {
                    status.setRollbackOnly();
                    return releaseAll(schemaName, tenantId, fromDate, toDate, schemeId, false);
                })
                : releaseAll(schemaName, tenantId, fromDate, toDate, schemeId, true);

        if (outcome.notSentCount() > 0) {
            log.warn("quarantine_release_stopped reason=\"not acknowledged\" tenantId={} fromDate={} toDate={} "
                            + "schemeId={} released={} stillQuarantined={} noValue={} notSent={}",
                    tenantId, fromDate, toDate, schemeId,
                    outcome.releasedCount(), outcome.stillQuarantinedCount(), outcome.noValueCount(),
                    outcome.notSentCount());
        } else {
            log.info("quarantine_release tenantId={} fromDate={} toDate={} schemeId={} dryRun={} released={} "
                            + "stillQuarantined={} noValue={}",
                    tenantId, fromDate, toDate, schemeId, dryRun,
                    outcome.releasedCount(), outcome.stillQuarantinedCount(), outcome.noValueCount());
        }
        return outcome;
    }

    private Outcome releaseAll(String schemaName,
                               Integer tenantId,
                               LocalDate fromDate,
                               LocalDate toDate,
                               Long schemeId,
                               boolean publish) {
        List<TelemetryLatestFlowReadingRecord> readings =
                telemetryTenantRepository.findQuarantinedReadings(schemaName, fromDate, toDate, schemeId);
        int released = 0;
        int stillQuarantined = 0;
        int noValue = 0;
        for (TelemetryLatestFlowReadingRecord reading : readings) {
            if (reading.confirmedReading() == null) {
                log.warn("quarantine_release_skipped reason=\"no confirmed reading\" tenantId={} readingId={} "
                                + "schemeId={} readingDate={}",
                        tenantId, reading.id(), reading.schemeId(), reading.readingDate());
                noValue++;
                continue;
            }
            if (isImplausible(schemaName, tenantId, reading)) {
                stillQuarantined++;
                continue;
            }
            telemetryTenantRepository.applyQuarantineReason(schemaName, reading.id(), QuarantineReason.NONE);
            if (!publish) {
                released++;
                continue;
            }
            ReadingRepublisher.Result result;
            try {
                result = readingRepublisher.republishAndAwait(schemaName, tenantId, reading.id());
            } catch (RuntimeException e) {
                // The cleared marker is already committed: put it back, or the unsent reading would
                // never be picked up again.
                try {
                    telemetryTenantRepository.applyQuarantineReason(schemaName, reading.id(), reading.quarantineReason());
                } catch (RuntimeException restoreFailure) {
                    e.addSuppressed(restoreFailure);
                }
                throw e;
            }
            if (result == ReadingRepublisher.Result.NOT_ACKNOWLEDGED) {
                telemetryTenantRepository.applyQuarantineReason(schemaName, reading.id(), reading.quarantineReason());
                break;
            }
            if (result == ReadingRepublisher.Result.PUBLISHED) {
                released++;
            } else {
                // Quarantined again by another write between the clear and the read-back, so nothing
                // was sent and the row is back where it started.
                stillQuarantined++;
            }
        }
        return new Outcome(released, stillQuarantined, noValue,
                readings.size() - released - stillQuarantined - noValue);
    }

    /** The submission path's assessment, made again for a stored reading. */
    private boolean isImplausible(String schemaName, Integer tenantId, TelemetryLatestFlowReadingRecord reading) {
        TelemetryConfirmedReadingSnapshot baseline = telemetryTenantRepository
                .findLatestConfirmedReadingSnapshotBeforeDate(
                        schemaName,
                        reading.schemeId(),
                        ReadingChannel.BFM,
                        MeterRegister.of(reading.submittedUnit()),
                        reading.readingDate(),
                        reading.id())
                .orElse(null);
        Verdict verdict = supplyPlausibilityGuard.assess(
                schemaName,
                tenantId,
                reading.createdBy(),
                reading.schemeId(),
                reading.readingDate(),
                reading.confirmedReading(),
                baseline,
                SupplyPlausibilityGuard.Path.RELEASE);
        return verdict instanceof Verdict.Quarantined;
    }

    /** As {@link ReadingBackfillService} resolves it: {@code null} when no scheme id was given. */
    private Long resolveScheme(String schemaName, String stateSchemeId, String centreSchemeId) {
        boolean hasStateSchemeId = stateSchemeId != null && !stateSchemeId.isBlank();
        boolean hasCentreSchemeId = centreSchemeId != null && !centreSchemeId.isBlank();
        if (!hasStateSchemeId && !hasCentreSchemeId) {
            return null;
        }
        return telemetryTenantRepository.findSchemeIdByStateSchemeId(schemaName, stateSchemeId)
                .or(() -> telemetryTenantRepository.findSchemeIdByCentreSchemeId(schemaName, centreSchemeId))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, ReadingBackfillService.SCHEME_NOT_FOUND));
    }
}
