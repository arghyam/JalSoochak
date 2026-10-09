package org.arghyam.jalsoochak.message.ledger;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Housekeeping for the delivery ledger, both passes over every ledger schema:
 *
 * <ul>
 *   <li><b>Retention</b> — deletes rows older than {@code notifications.ledger.retention-days} (180),
 *       in batches, a bounded number per schema per run so a first run against a backlog never holds a
 *       long transaction. Modelled on {@code ReportFileReaperService}, with its benign multi-replica
 *       caveat: two replicas may each delete a batch.</li>
 *   <li><b>Daily statistics</b> — one {@code [NotificationStats]} line per tenant, message type,
 *       channel and provider for the previous IST day: sent, accepted, delivered (including read), read,
 *       failed, pending, unresolved, untracked and not sent. These are the delivery numbers the
 *       provider-side scripts used to be needed for, now for every channel, read from the ledger.</li>
 * </ul>
 */
@Service
@Slf4j
public class NotificationLedgerMaintenanceService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final NotificationLedger ledger;
    private final NotificationLedgerRepository repository;
    private final int retentionDays;
    private final int batchSize;
    private final int maxBatchesPerSchema;

    public NotificationLedgerMaintenanceService(
            NotificationLedger ledger,
            NotificationLedgerRepository repository,
            @Value("${notifications.ledger.retention-days:180}") int retentionDays,
            @Value("${notifications.ledger.purge.batch-size:5000}") int batchSize,
            @Value("${notifications.ledger.purge.max-batches-per-schema:20}") int maxBatchesPerSchema) {
        this.ledger = ledger;
        this.repository = repository;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        this.maxBatchesPerSchema = maxBatchesPerSchema;
    }

    @Scheduled(fixedDelayString = "${notifications.ledger.purge.interval-ms:3600000}",
            initialDelayString = "${notifications.ledger.purge.initial-delay-ms:600000}")
    public void purgeScheduled() {
        if (ledger.isEnabled()) {
            purge();
        }
    }

    @Scheduled(cron = "${notifications.ledger.stats.cron:0 30 9 * * *}", zone = "Asia/Kolkata")
    public void dailyStatsScheduled() {
        if (ledger.isEnabled()) {
            logDailyStats(LocalDate.now(IST).minusDays(1));
        }
    }

    /** Deletes rows past retention; returns how many. */
    public int purge() {
        int deleted = 0;
        for (String schema : schemas()) {
            for (int batch = 0; batch < maxBatchesPerSchema; batch++) {
                int n;
                try {
                    n = repository.purgeOlderThan(schema, retentionDays, batchSize);
                } catch (Exception e) {
                    log.warn("[Ledger] purge failed in schema={}: {}", schema, e.getMessage());
                    break;
                }
                deleted += n;
                if (n < batchSize) {
                    break;
                }
            }
        }
        if (deleted > 0) {
            log.info("[Ledger] purged {} row(s) older than {} days", deleted, retentionDays);
        }
        return deleted;
    }

    /** Logs one {@code [NotificationStats]} line per tenant, type, channel and provider for {@code day}. */
    public void logDailyStats(LocalDate day) {
        for (String schema : schemas()) {
            List<NotificationLedgerRepository.DailyStats> rows;
            try {
                rows = repository.dailyStats(schema, day);
            } catch (Exception e) {
                log.warn("[NotificationStats] could not read schema={}: {}", schema, e.getMessage());
                continue;
            }
            Integer tenantId = ledger.tenantIdFor(schema);
            for (NotificationLedgerRepository.DailyStats r : rows) {
                LedgerChannel channel = LedgerChannel.fromId(r.channelId());
                log.info("[NotificationStats] date={} tenant={} schema={} type={} channel={} provider={} sent={}"
                                + " accepted={} delivered={} read={} failed={} pending={} unresolved={}"
                                + " notTracked={} notSent={}",
                        day, tenantId == null ? "-" : tenantId, schema, r.messageType(),
                        channel == null ? r.channelId() : channel.name(), r.provider(), r.total(), r.accepted(),
                        r.delivered(), r.read(), r.failed(), r.pending(), r.unresolved(), r.notTracked(),
                        r.notSent());
            }
        }
    }

    private List<String> schemas() {
        try {
            return repository.ledgerSchemas();
        } catch (Exception e) {
            log.warn("[Ledger] could not list ledger schemas: {}", e.getMessage());
            return List.of();
        }
    }
}
