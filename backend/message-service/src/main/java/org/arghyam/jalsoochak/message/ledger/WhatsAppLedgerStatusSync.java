package org.arghyam.jalsoochak.message.ledger;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.channel.provider.DeliveryReceipt;
import org.arghyam.jalsoochak.message.channel.provider.DeliveryState;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppDeliveryStatusReader;
import org.arghyam.jalsoochak.message.dto.WhatsAppMessageStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Brings the WhatsApp rows of the delivery ledger up to date with what the provider reports. Driven by
 * {@code WhatsAppDeliveryReconciliationService}, which already pulls the provider's window for its log
 * summaries and hands every outbound template message it saw to {@link #apply}.
 *
 * <p>Two more passes close the gaps a window over send time leaves:</p>
 * <ol>
 *   <li><b>Incremental</b> ({@link #syncIncremental}) — every message whose status changed since the last
 *       pass, read on the provider's {@code updated_at} from a cursor kept in
 *       {@code common_schema.notification_status_sync_state}. Catches a report read the next morning,
 *       long after it left the send-time window. The cursor moves only once a pass completes, minus an
 *       overlap, so a failed pass is read again; a provider that will not filter on {@code updated_at}
 *       leaves the pass a logged no-op and the other passes still run.</li>
 *   <li><b>Sweep</b> ({@link #sweep}) — rows still pending after {@code sweep-after-minutes}, asked about
 *       one by one, capped per pass.</li>
 * </ol>
 *
 * <p>Statuses are read in the order a message moves through them, so one that advances mid-pass moves
 * into a status not yet read rather than one already read; offset paging can then shift it, but never
 * past the pass.</p>
 *
 * <p>Logs ids and counts only.</p>
 */
@Service
@Slf4j
public class WhatsAppLedgerStatusSync {

    static final String CURSOR_SOURCE = "whatsapp:updated_at";
    private static final String UPDATED_AT = "updated_at";

    /**
     * Every outbound status, in the order a message moves through them. {@code RECEIVED} and
     * {@code DELETED} are not deliveries.
     */
    public static final List<String> STATUSES_IN_PROGRESSION = List.of(
            "ENQUEUED", "SENT", "DELIVERED", "READ", "SEEN", "PLAYED", "ERROR", "CONTACT_OPT_OUT");

    private final WhatsAppDeliveryStatusReader reader;
    private final NotificationLedger ledger;
    private final NotificationLedgerRepository repository;
    private final boolean incrementalEnabled;
    private final long overlapMinutes;
    private final long firstRunHours;
    private final int pageSize;
    private final int maxPagesCap;
    private final int sweepAfterMinutes;
    private final int sweepMaxPerPass;
    private final int lookbackHours;

    public WhatsAppLedgerStatusSync(
            WhatsAppDeliveryStatusReader reader,
            NotificationLedger ledger,
            NotificationLedgerRepository repository,
            @Value("${whatsapp.status.ledger.incremental.enabled:true}") boolean incrementalEnabled,
            @Value("${whatsapp.status.ledger.incremental.overlap-minutes:10}") long overlapMinutes,
            @Value("${whatsapp.status.ledger.incremental.first-run-hours:6}") long firstRunHours,
            @Value("${whatsapp.status.reconcile.page-size:250}") int pageSize,
            @Value("${whatsapp.status.ledger.max-pages-cap:400}") int maxPagesCap,
            @Value("${notifications.ledger.status.sweep-after-minutes:120}") int sweepAfterMinutes,
            @Value("${whatsapp.status.ledger.sweep-max-per-pass:200}") int sweepMaxPerPass,
            @Value("${notifications.ledger.status.lookback-hours:72}") int lookbackHours) {
        this.reader = reader;
        this.ledger = ledger;
        this.repository = repository;
        this.incrementalEnabled = incrementalEnabled;
        this.overlapMinutes = overlapMinutes;
        this.firstRunHours = firstRunHours;
        this.pageSize = pageSize;
        this.maxPagesCap = maxPagesCap;
        this.sweepAfterMinutes = sweepAfterMinutes;
        this.sweepMaxPerPass = sweepMaxPerPass;
        this.lookbackHours = lookbackHours;
    }

    public boolean isActive() {
        return ledger.isEnabled();
    }

    /** What one pass did, for the summary line. */
    public record SyncStats(int seen, int applied, int notInLedger) {
        SyncStats plus(SyncStats other) {
            return new SyncStats(seen + other.seen, applied + other.applied, notInLedger + other.notInLedger);
        }
    }

    /**
     * Applies provider statuses to the ledger rows they belong to. Only outbound template messages are
     * considered; within those, a message is looked up in each ledger schema at once, and a row already
     * settled is never touched.
     */
    public SyncStats apply(Collection<WhatsAppMessageStatus> messages) {
        if (!isActive() || messages == null || messages.isEmpty()) {
            return new SyncStats(0, 0, 0);
        }
        Map<String, WhatsAppMessageStatus> byId = new LinkedHashMap<>();
        for (WhatsAppMessageStatus m : messages) {
            if (m.isOutboundHsm() && m.messageId() != null && toState(m) != null) {
                // Later statuses in a pass are the more advanced ones; keep the last seen.
                byId.put(m.messageId(), m);
            }
        }
        if (byId.isEmpty()) {
            return new SyncStats(0, 0, 0);
        }
        String provider = reader.providerId();
        Set<String> located = new HashSet<>();
        int applied = 0;
        for (String schema : repository.ledgerSchemas()) {
            List<String> open;
            try {
                open = repository.openMessageIds(schema, provider, byId.keySet());
            } catch (Exception e) {
                log.warn("[WhatsAppStatus] ledger: could not look up messages in schema={}: {}", schema, e.getMessage());
                continue;
            }
            for (String id : open) {
                located.add(id);
                applied += ledger.applyReceipt(schema, toReceipt(provider, byId.get(id)));
            }
        }
        return new SyncStats(byId.size(), applied, byId.size() - located.size());
    }

    /**
     * Reads every status change since the cursor and applies it. Returns zero stats, logged, when the
     * provider refuses the pass — the cursor then stays where it was.
     */
    public SyncStats syncIncremental(Instant now) {
        if (!isActive() || !incrementalEnabled) {
            return new SyncStats(0, 0, 0);
        }
        Instant from;
        try {
            from = repository.readCursor(CURSOR_SOURCE)
                    .map(c -> c.minus(Duration.ofMinutes(overlapMinutes)))
                    .orElse(now.minus(Duration.ofHours(firstRunHours)));
        } catch (Exception e) {
            log.warn("[WhatsAppStatus] ledger: no cursor table yet ({}); skipping the incremental pass", e.getMessage());
            return new SyncStats(0, 0, 0);
        }
        SyncStats total = new SyncStats(0, 0, 0);
        try {
            for (String status : STATUSES_IN_PROGRESSION) {
                List<WhatsAppMessageStatus> page = fetchAll(reader, from, now, status, UPDATED_AT, pageSize, maxPagesCap);
                total = total.plus(apply(page));
            }
        } catch (Exception e) {
            log.warn("[WhatsAppStatus] ledger: incremental pass on {} failed, cursor not moved: {}",
                    UPDATED_AT, e.getMessage());
            return total;
        }
        repository.writeCursor(CURSOR_SOURCE, now);
        log.info("[WhatsAppStatus] ledger incremental: window={}→{} seen={} applied={} notInLedger={}",
                from, now, total.seen(), total.applied(), total.notInLedger());
        return total;
    }

    /**
     * Asks the provider about rows still pending after {@code sweep-after-minutes}, oldest first, at most
     * {@code sweep-max-per-pass} of them across all schemas.
     */
    public int sweep() {
        if (!isActive() || sweepMaxPerPass <= 0) {
            return 0;
        }
        String provider = reader.providerId();
        int budget = sweepMaxPerPass;
        int asked = 0;
        int applied = 0;
        for (String schema : repository.ledgerSchemas()) {
            if (budget <= 0) {
                break;
            }
            List<NotificationLedgerRepository.PendingRow> rows;
            try {
                rows = repository.pendingForSweep(schema, LedgerChannel.WHATSAPP.id(), provider,
                        sweepAfterMinutes, lookbackHours, budget);
            } catch (Exception e) {
                log.warn("[WhatsAppStatus] ledger sweep: could not read schema={}: {}", schema, e.getMessage());
                continue;
            }
            for (NotificationLedgerRepository.PendingRow row : rows) {
                budget--;
                asked++;
                try {
                    Optional<WhatsAppMessageStatus> status = reader.fetchMessage(row.providerMessageId());
                    if (status.isPresent() && toState(status.get()) != null) {
                        applied += ledger.applyReceipt(schema, toReceipt(provider, status.get()));
                    }
                } catch (Exception e) {
                    log.warn("[WhatsAppStatus] ledger sweep: lookup failed for providerMsgId={}: {}",
                            row.providerMessageId(), e.getMessage());
                }
            }
        }
        if (asked > 0) {
            log.info("[WhatsAppStatus] ledger sweep: asked={} applied={}", asked, applied);
        }
        return applied;
    }

    /**
     * Pages through one status in a window, sizing the page budget from the provider's own count so a
     * busy window is read to the end: {@code ceil(count / page) + 1} pages, the extra one absorbing
     * messages inserted while paging, never more than {@code maxPagesCap}. Logs how many came back
     * against the count, so a short read is visible.
     */
    public static List<WhatsAppMessageStatus> fetchAll(WhatsAppDeliveryStatusReader reader, Instant from,
                                                       Instant to, String status, String dateColumn,
                                                       int requestedPageSize, int maxPagesCap) {
        int count = reader.countMessages(from, to, status, dateColumn);
        if (count == 0) {
            return List.of();
        }
        int providerMax = reader.maxPageSize();
        int page = providerMax > 0 ? Math.min(requestedPageSize, providerMax) : requestedPageSize;
        page = Math.max(1, page);
        int pages = count > 0 ? (int) Math.min(maxPagesCap, (long) Math.ceil(count / (double) page) + 1) : maxPagesCap;
        List<WhatsAppMessageStatus> fetched = new ArrayList<>(
                reader.fetchMessages(from, to, status, dateColumn, page, Math.max(1, pages)));
        boolean complete = count < 0 || fetched.size() >= count;
        if (complete) {
            log.debug("[WhatsAppStatus] status={} dateColumn={} count={} fetched={} complete=true",
                    status, dateColumn, count, fetched.size());
        } else {
            log.warn("[WhatsAppStatus] status={} dateColumn={} count={} fetched={} complete=false — fewer"
                            + " messages came back than the provider counted; the rest are read on a later pass",
                    status, dateColumn, count, fetched.size());
        }
        return fetched;
    }

    static DeliveryState toState(WhatsAppMessageStatus message) {
        if (message.outcome() == null) {
            return DeliveryState.PENDING;
        }
        return switch (message.outcome()) {
            case DELIVERED -> DeliveryState.DELIVERED;
            case READ -> DeliveryState.READ;
            case DELIVERY_FAILED -> DeliveryState.FAILED;
            case IGNORED -> null;
            default -> DeliveryState.PENDING;
        };
    }

    private static DeliveryReceipt toReceipt(String provider, WhatsAppMessageStatus m) {
        DeliveryState state = toState(m);
        return new DeliveryReceipt(provider, m.messageId(), null, state, m.bspStatus(),
                state == DeliveryState.FAILED ? m.errorCode() : null,
                state == DeliveryState.FAILED ? m.errorReason() : null,
                null, null, null);
    }
}
