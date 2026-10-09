package org.arghyam.jalsoochak.message.ledger;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.channel.provider.DeliveryReceipt;
import org.arghyam.jalsoochak.message.channel.provider.EmailSender;
import org.arghyam.jalsoochak.message.channel.provider.SmsSender;
import org.arghyam.jalsoochak.message.channel.provider.TenantChannelProviders;
import org.arghyam.jalsoochak.message.dto.TenantRef;
import org.arghyam.jalsoochak.message.service.TenantRefResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Pulls delivery status for email and SMS rows the provider has not reported on by push.
 *
 * <p>For each ledger schema and each provider with pending rows, it asks the sender that tenant uses
 * <em>now</em> — {@link TenantChannelProviders}, so a tenant with its own account is asked through its own
 * credentials — provided that sender is still the provider the row went out through and can be asked at
 * all ({@code supportsStatusLookup}). A provider push ({@code DeliveryReceiptController}) usually settles
 * a row first; this makes a lost or unconfigured callback a delay rather than a gap. SMS has no other
 * source today, so this is its main one.</p>
 *
 * <p>Then marks rows pending beyond the look-back {@code UNRESOLVED}, on every channel.</p>
 *
 * <p>Same benign multi-replica caveat as {@code ReportFileReaperService}: two replicas may ask about the
 * same rows, and applying a status twice changes nothing the second time.</p>
 */
@Service
@Slf4j
public class NotificationStatusSweepService {

    private static final String TENANT_SCHEMA_PREFIX = "tenant_";

    private final NotificationLedger ledger;
    private final NotificationLedgerRepository repository;
    private final TenantChannelProviders channelProviders;
    private final TenantRefResolver tenantRefResolver;
    private final int sweepAfterMinutes;
    private final int lookbackHours;
    private final int maxPerProvider;
    private final int expireBatch;

    public NotificationStatusSweepService(
            NotificationLedger ledger,
            NotificationLedgerRepository repository,
            TenantChannelProviders channelProviders,
            TenantRefResolver tenantRefResolver,
            @Value("${notifications.ledger.status.email-sms-sweep-after-minutes:5}") int sweepAfterMinutes,
            @Value("${notifications.ledger.status.lookback-hours:72}") int lookbackHours,
            @Value("${notifications.ledger.status.sweep-max-per-provider:200}") int maxPerProvider,
            @Value("${notifications.ledger.status.expire-batch:5000}") int expireBatch) {
        this.ledger = ledger;
        this.repository = repository;
        this.channelProviders = channelProviders;
        this.tenantRefResolver = tenantRefResolver;
        this.sweepAfterMinutes = sweepAfterMinutes;
        this.lookbackHours = lookbackHours;
        this.maxPerProvider = maxPerProvider;
        this.expireBatch = expireBatch;
    }

    @Scheduled(fixedDelayString = "${notifications.ledger.status.sweep-interval-ms:900000}",
            initialDelayString = "${notifications.ledger.status.sweep-initial-delay-ms:300000}")
    public void sweepScheduled() {
        if (!ledger.isEnabled()) {
            return;
        }
        sweep();
    }

    /** One pass over every schema. Returns how many rows changed. */
    public int sweep() {
        int changed = 0;
        int asked = 0;
        int expired = 0;
        for (String schema : safeSchemas()) {
            TenantRef tenant = tenantOf(schema);
            for (LedgerChannel channel : List.of(LedgerChannel.EMAIL, LedgerChannel.SMS)) {
                List<String> providers;
                try {
                    providers = repository.pendingProviders(schema, channel.id(), lookbackHours);
                } catch (Exception e) {
                    log.warn("[Ledger] status sweep: could not read schema={}: {}", schema, e.getMessage());
                    continue;
                }
                for (String provider : providers) {
                    Lookup lookup = lookupFor(channel, tenant, provider);
                    if (lookup == null) {
                        continue;
                    }
                    List<NotificationLedgerRepository.PendingRow> rows = repository.pendingForSweep(
                            schema, channel.id(), provider, sweepAfterMinutes, lookbackHours, maxPerProvider);
                    if (rows.isEmpty()) {
                        continue;
                    }
                    asked += rows.size();
                    changed += applyAll(schema, rows, lookup);
                }
            }
            expired += ledger.markUnresolved(schema, lookbackHours, expireBatch);
        }
        if (asked > 0 || expired > 0) {
            log.info("[Ledger] status sweep: asked={} changed={} unresolved={}", asked, changed, expired);
        }
        return changed + expired;
    }

    /** A provider's status lookup, bound to the account that sent the rows. */
    @FunctionalInterface
    private interface Lookup {
        List<DeliveryReceipt> statuses(Collection<String> ids, Instant from, Instant to);
    }

    private Lookup lookupFor(LedgerChannel channel, TenantRef tenant, String provider) {
        try {
            if (channel == LedgerChannel.EMAIL) {
                EmailSender sender = channelProviders.emailFor(tenant);
                return provider.equals(sender.providerId()) && sender.supportsStatusLookup()
                        ? sender::lookupStatuses : null;
            }
            SmsSender sender = channelProviders.smsFor(tenant);
            return provider.equals(sender.providerId()) && sender.supportsStatusLookup()
                    ? sender::lookupStatuses : null;
        } catch (Exception e) {
            log.warn("[Ledger] status sweep: no {} sender for {}: {}", channel, tenant, e.getMessage());
            return null;
        }
    }

    private int applyAll(String schema, List<NotificationLedgerRepository.PendingRow> rows, Lookup lookup) {
        Instant from = rows.stream().map(NotificationLedgerRepository.PendingRow::createdAt)
                .min(Comparator.naturalOrder()).orElse(Instant.now());
        Instant to = rows.stream().map(NotificationLedgerRepository.PendingRow::createdAt)
                .max(Comparator.naturalOrder()).orElse(Instant.now());
        Map<String, NotificationLedgerRepository.PendingRow> byId = rows.stream().collect(Collectors.toMap(
                NotificationLedgerRepository.PendingRow::providerMessageId, Function.identity(), (a, b) -> a));
        int changed = 0;
        for (DeliveryReceipt receipt : lookup.statuses(byId.keySet(), from, to)) {
            if (receipt != null && byId.containsKey(receipt.providerMessageId())) {
                changed += ledger.applyReceipt(schema, receipt);
            }
        }
        return changed;
    }

    private List<String> safeSchemas() {
        try {
            return repository.ledgerSchemas();
        } catch (Exception e) {
            log.warn("[Ledger] status sweep: could not list ledger schemas: {}", e.getMessage());
            return List.of();
        }
    }

    private TenantRef tenantOf(String schema) {
        if (!schema.startsWith(TENANT_SCHEMA_PREFIX)) {
            return TenantRef.NONE;
        }
        return tenantRefResolver.resolve(null, schema.substring(TENANT_SCHEMA_PREFIX.length()));
    }
}
