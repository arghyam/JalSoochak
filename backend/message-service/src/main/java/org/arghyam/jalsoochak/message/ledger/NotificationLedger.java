package org.arghyam.jalsoochak.message.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.channel.provider.DeliveryReceipt;
import org.arghyam.jalsoochak.message.channel.provider.ProviderAcceptance;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSender;
import org.arghyam.jalsoochak.message.dto.TenantRef;
import org.arghyam.jalsoochak.message.service.PiiEncryptionService;
import org.arghyam.jalsoochak.message.service.TenantRefResolver;
import org.arghyam.jalsoochak.message.util.PhoneRedactor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The outbound delivery ledger: one {@code notification_table} row per message per recipient, in the
 * recipient's tenant schema, or in {@code common_schema} for a send that belongs to no tenant (V63).
 *
 * <p>The only API senders use. A send {@link #open opens} its row <em>before</em> calling the
 * provider and {@link #close closes} it with the answer, so that a delivery report can never arrive
 * for a row that does not exist yet, a process that dies mid-send leaves a visible {@code DISPATCHING}
 * row, and a report that fails to build fails on the same row it would have been sent from. Provider
 * reports arrive later through {@link #applyReceipt}.</p>
 *
 * <p><strong>Never throws, and never stands in a send's way.</strong> Every write is caught and logged:
 * a ledger outage, a migration not yet applied, a bad id — none of them may stop or retry a message.
 * Do not "fix" that by letting errors through.</p>
 *
 * <p>No address is ever stored, logged or published: the recipient is kept as a user id and an HMAC
 * of the normalised phone number or email address, the same keyed hash {@code user_table} uses.
 * Provider error text is phone-redacted before it is stored. {@code message_blob} keeps only
 * {@link #METADATA_KEYS}.</p>
 *
 * <p>Off unless {@code notifications.ledger.enabled} is set, so a deployment turns it on once V63 is
 * applied to its database.</p>
 */
@Service
@Slf4j
public class NotificationLedger {

    public static final String PLATFORM_SCHEMA = "common_schema";
    private static final String TENANT_SCHEMA_PREFIX = "tenant_";
    private static final String SCHEMA_PATTERN = "^[a-z0-9_]+$";
    private static final String UNKNOWN = "unknown";
    private static final String WRITE_FAILURES = "notification.ledger.write.failures";
    private static final String OPERATION = "operation";

    /** The only keys that reach {@code message_blob}. None of them can hold a name, address or OTP. */
    static final Set<String> METADATA_KEYS = Set.of(
            "deliveryMode", "documentUrl", "reportDate", "weekStart", "weekEnd", "nudgeDate",
            "escalationLevel", "mailTemplate", "flowRef", "otpChannel");

    private final NotificationLedgerRepository repository;
    private final NotificationDeliveryEventPublisher eventPublisher;
    private final PiiEncryptionService piiEncryptionService;
    private final TenantRefResolver tenantRefResolver;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final ObjectProvider<WhatsAppSender> whatsAppSender;
    private final boolean enabled;

    private final Map<String, Optional<Integer>> tenantIdBySchema = new ConcurrentHashMap<>();

    public NotificationLedger(NotificationLedgerRepository repository,
                              NotificationDeliveryEventPublisher eventPublisher,
                              PiiEncryptionService piiEncryptionService,
                              TenantRefResolver tenantRefResolver,
                              ObjectMapper objectMapper,
                              MeterRegistry meterRegistry,
                              ObjectProvider<WhatsAppSender> whatsAppSender,
                              @Value("${notifications.ledger.enabled:false}") boolean enabled) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.piiEncryptionService = piiEncryptionService;
        this.tenantRefResolver = tenantRefResolver;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.whatsAppSender = whatsAppSender;
        this.enabled = enabled;
        log.info("[Ledger] notification delivery ledger {}", enabled ? "ENABLED" : "disabled");
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Opens the row for a message about to be sent. Always returns a ref; it is unrecorded when the
     * ledger is off or the write failed, and closing it then does nothing.
     */
    public LedgerRef open(LedgerEntry entry) {
        long started = System.nanoTime();
        String uuid = UUID.randomUUID().toString();
        String schema = schemaOf(entry);
        Integer tenantId = entry.tenantId() != null ? entry.tenantId() : tenantIdFor(schema);
        if (!enabled) {
            return new LedgerRef(schema, uuid, tenantId, false, started);
        }
        if (entry.type() == null || entry.channel() == null) {
            log.warn("[Ledger] not recording an entry with no type or channel: {}", entry);
            return new LedgerRef(schema, uuid, tenantId, false, started);
        }
        try {
            String recipientHash = hashRecipient(entry.recipient());
            String provider = providerOf(entry);
            repository.open(schema, new NotificationLedgerRepository.NewRow(
                    uuid, entry.userId(), entry.adminUserId(), entry.userType(), recipientHash,
                    entry.channel().id(), entry.type().name(), entry.eventType(), provider,
                    entry.contactRef(), entry.templateRef(), entry.correlationId(), entry.subjectDate(),
                    dedupeKey(entry, recipientHash), metadataJson(entry.metadata())));
            return new LedgerRef(schema, uuid, tenantId, true, started);
        } catch (Exception e) {
            writeFailed("open", entry, e);
            return new LedgerRef(schema, uuid, tenantId, false, started);
        }
    }

    /** Records how the send ended. Does nothing for an unrecorded ref. */
    public void close(LedgerRef ref, LedgerOutcome outcome) {
        if (ref == null || !ref.recorded() || outcome == null) {
            return;
        }
        try {
            ProviderAcceptance acceptance = outcome.acceptance();
            int latencyMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - ref.startedNanos()) / 1_000_000L);
            Optional<LedgerSnapshot> snapshot = repository.close(ref.schema(), ref.uuid(),
                    new NotificationLedgerRepository.Closing(
                            outcome.status().name(),
                            outcome.deliveryState().name(),
                            outcome.failureStage(),
                            acceptance == null ? null : acceptance.providerMessageId(),
                            acceptance == null ? null : acceptance.providerStatus(),
                            outcome.errorCode(),
                            redact(outcome.errorMessage()),
                            outcome.templateRef(),
                            acceptance == null ? null : acceptance.cost(),
                            acceptance == null ? null : acceptance.costCurrency(),
                            latencyMs,
                            outcome.reachedProvider()));
            snapshot.ifPresent(s -> {
                count(s, outcome.status().name());
                eventPublisher.publish(s, ref.tenantId());
            });
        } catch (Exception e) {
            log.warn("[Ledger] could not close uuid={} as {}: {}", ref.uuid(), outcome.status(), e.getMessage());
            meterRegistry.counter(WRITE_FAILURES, OPERATION, "close").increment();
        }
    }

    /** Opens and closes a row in one go, for an outcome known before any send — a skip, say. */
    public void recordOutcome(LedgerEntry entry, LedgerOutcome outcome) {
        close(open(entry), outcome);
    }

    /**
     * Applies a provider report. Our tracking reference, when the provider echoed it, leads straight
     * to the row; otherwise the provider's message id is looked up in every ledger schema.
     *
     * @return how many rows changed: 0 when the report was already applied, is older than what the
     *         row holds, or matches no row
     */
    public int applyReceipt(DeliveryReceipt receipt) {
        if (!enabled || receipt == null || receipt.state() == null) {
            return 0;
        }
        String[] ref = LedgerRef.parseTrackingRef(receipt.trackingRef());
        if (ref != null) {
            return apply(ref[0], ref[1], receipt);
        }
        if (receipt.providerMessageId() == null || receipt.providerMessageId().isBlank()) {
            return 0;
        }
        int changed = 0;
        for (String schema : repository.ledgerSchemas()) {
            changed += apply(schema, null, receipt);
            if (changed > 0) {
                break;
            }
        }
        return changed;
    }

    /** Applies a provider report to a row in a known schema, matched by the provider's message id. */
    public int applyReceipt(String schema, DeliveryReceipt receipt) {
        if (!enabled || receipt == null || receipt.state() == null || schema == null) {
            return 0;
        }
        return apply(schema, null, receipt);
    }

    /** Marks rows still pending after {@code olderThanHours} as unresolved; returns how many. */
    public int markUnresolved(String schema, int olderThanHours, int limit) {
        if (!enabled) {
            return 0;
        }
        try {
            List<LedgerSnapshot> changed = repository.markUnresolved(schema, olderThanHours, limit);
            Integer tenantId = tenantIdFor(schema);
            changed.forEach(s -> eventPublisher.publish(s, tenantId));
            return changed.size();
        } catch (Exception e) {
            log.warn("[Ledger] could not mark unresolved rows in schema={}: {}", schema, e.getMessage());
            return 0;
        }
    }

    /** The schema a tenant's rows live in, or {@link #PLATFORM_SCHEMA} for no tenant. */
    public String schemaFor(TenantRef tenant) {
        if (tenant == null || tenant.code() == null) {
            return PLATFORM_SCHEMA;
        }
        String schema = TENANT_SCHEMA_PREFIX + tenant.code().toLowerCase(Locale.ROOT);
        return schema.matches(SCHEMA_PATTERN) ? schema : PLATFORM_SCHEMA;
    }

    private int apply(String schema, String uuid, DeliveryReceipt receipt) {
        try {
            DeliveryReceipt clean = new DeliveryReceipt(receipt.providerId(), receipt.providerMessageId(),
                    receipt.trackingRef(), receipt.state(), receipt.providerStatus(), receipt.errorCode(),
                    redact(receipt.errorReason()), receipt.occurredAt(), receipt.cost(), receipt.costCurrency());
            List<LedgerSnapshot> changed = repository.applyStatus(schema, uuid, clean);
            Integer tenantId = tenantIdFor(schema);
            for (LedgerSnapshot s : changed) {
                meterRegistry.counter("notification.ledger.status.updates",
                        "provider", tag(s.provider()), "state", tag(s.deliveryStatus())).increment();
                eventPublisher.publish(s, tenantId);
            }
            return changed.size();
        } catch (Exception e) {
            log.warn("[Ledger] could not apply a {} status report in schema={}: {}",
                    receipt.providerId(), schema, e.getMessage());
            meterRegistry.counter(WRITE_FAILURES, OPERATION, "status").increment();
            return 0;
        }
    }

    /**
     * The entry's provider. A WhatsApp entry may leave it out: every tenant shares the one WhatsApp
     * sender, so its id is taken from that sender here rather than asked of it by each caller.
     */
    private String providerOf(LedgerEntry entry) {
        String provider = entry.provider();
        if ((provider == null || provider.isBlank()) && entry.channel() == LedgerChannel.WHATSAPP) {
            WhatsAppSender sender = whatsAppSender.getIfAvailable();
            provider = sender == null ? null : sender.providerId();
        }
        return provider == null || provider.isBlank() ? UNKNOWN : provider;
    }

    private String schemaOf(LedgerEntry entry) {
        String schema = entry.tenantSchema();
        if (schema == null || schema.isBlank()) {
            return PLATFORM_SCHEMA;
        }
        String lower = schema.trim().toLowerCase(Locale.ROOT);
        return lower.matches(SCHEMA_PATTERN) ? lower : PLATFORM_SCHEMA;
    }

    /** The tenant id behind a schema, cached; {@code null} for {@code common_schema} or an unknown one. */
    public Integer tenantIdFor(String schema) {
        if (schema == null || !schema.startsWith(TENANT_SCHEMA_PREFIX)) {
            return null;
        }
        return tenantIdBySchema.computeIfAbsent(schema, s -> {
            try {
                return Optional.ofNullable(
                        tenantRefResolver.resolve(null, s.substring(TENANT_SCHEMA_PREFIX.length())).id());
            } catch (Exception e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /**
     * HMAC of the normalised address: an email lower-cased, a phone number reduced to its digits with
     * the country code added to a bare ten-digit number, the form {@code user_table} hashes.
     */
    private String hashRecipient(String recipient) {
        if (recipient == null || recipient.isBlank()) {
            return null;
        }
        String trimmed = recipient.trim();
        String normalised;
        if (trimmed.contains("@")) {
            normalised = trimmed.toLowerCase(Locale.ROOT);
        } else {
            String digits = trimmed.replaceAll("\\D", "");
            normalised = digits.length() == 10 ? "91" + digits : digits;
        }
        try {
            return piiEncryptionService.hmac(normalised);
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code TYPE:recipient:subjectDate}, for a later duplicate guard; only when there is a subject date. */
    private static String dedupeKey(LedgerEntry entry, String recipientHash) {
        if (entry.subjectDate() == null) {
            return null;
        }
        String recipient;
        if (entry.userId() != null) {
            recipient = "u" + entry.userId();
        } else if (entry.adminUserId() != null) {
            recipient = "a" + entry.adminUserId();
        } else if (recipientHash != null) {
            recipient = "h" + recipientHash.substring(0, Math.min(16, recipientHash.length()));
        } else {
            return null;
        }
        return entry.type().name() + ":" + recipient + ":" + entry.subjectDate();
    }

    /** The allow-listed metadata as JSON, a URL without its query string; {@code null} when nothing is left. */
    String metadataJson(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        Map<String, Object> kept = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (value != null && METADATA_KEYS.contains(key)) {
                kept.put(key, "documentUrl".equals(key) ? String.valueOf(value).replaceFirst("\\?.*$", "") : value);
            }
        });
        if (kept.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(kept);
        } catch (Exception e) {
            return null;
        }
    }

    private static String redact(String text) {
        if (text == null) {
            return null;
        }
        String redacted = PhoneRedactor.redact(text);
        return NotificationLedgerRepository.truncate(redacted, NotificationLedgerRepository.ERROR_MESSAGE_MAX);
    }

    private void count(LedgerSnapshot s, String status) {
        LedgerChannel channel = s.channelId() == null ? null : LedgerChannel.fromId(s.channelId());
        meterRegistry.counter("notification.ledger.records",
                "type", tag(s.messageType()),
                "channel", channel == null ? UNKNOWN : channel.name(),
                "provider", tag(s.provider()),
                "status", tag(status)).increment();
    }

    private void writeFailed(String operation, LedgerEntry entry, Exception e) {
        log.warn("[Ledger] could not {} a row for {}: {}", operation, entry, e.getMessage());
        meterRegistry.counter(WRITE_FAILURES, OPERATION, operation).increment();
    }

    private static String tag(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
