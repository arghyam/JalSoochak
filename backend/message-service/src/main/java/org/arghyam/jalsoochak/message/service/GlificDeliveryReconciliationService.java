package org.arghyam.jalsoochak.message.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.dto.GlificDeliveryOutcome;
import org.arghyam.jalsoochak.message.dto.GlificMessageStatus;
import org.arghyam.jalsoochak.message.util.PhoneRedactor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Asks Glific what actually happened to the daily reports we sent, and writes the answer to the log.
 *
 * <p>{@code result=SENT} only ever meant "Glific accepted our API call". Gupshup and Meta act after
 * that call returns and report delivery status back to Glific alone, so a report sent to a number with
 * no WhatsApp account was counted as sent exactly like one that arrived. This job closes that gap: it
 * pulls a rolling window of messages from Glific, maps each recipient back to an officer, and emits
 * per-message, per-tenant and platform-wide lines under the {@code [GlificStatus]} prefix.</p>
 *
 * <h2>Shape of a pass</h2>
 * <ol>
 *   <li>For each {@code bspStatus} of interest, count then page the window
 *       ({@link GlificDeliveryStatusService}).</li>
 *   <li>Discard anything that is not an outbound HSM on one of our daily-report templates —
 *       {@code MessageFilter} cannot do this server-side.</li>
 *   <li>Resolve each remaining Glific contact id to an officer, one batched query per tenant.</li>
 *   <li>Tally by role and by failure code; log.</li>
 * </ol>
 *
 * <h2>Rolling window, not a fixed hour</h2>
 * <p>The daily-report cron is configurable per tenant via
 * {@code common_schema.tenant_config_master_table} key {@code DAILY_SITUATION_REPORT_TIME}, so no
 * single hour is correct for every tenant. A look-back window covers them all.</p>
 *
 * <h2>Privacy</h2>
 * <p>Every line carries ids and statuses only. Names and phone numbers are never read, let alone
 * logged.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GlificDeliveryReconciliationService {

    /**
     * The statuses worth pulling. Every outbound state Glific can report is here: omitting one would
     * silently drop those messages from the counts rather than showing them as anything.
     * {@code RECEIVED} and {@code DELETED} are excluded — inbound and removed, neither is a delivery.
     */
    private static final List<String> STATUSES_TO_CHECK = List.of(
            "ERROR", "CONTACT_OPT_OUT", "DELIVERED", "READ", "SEEN", "PLAYED", "SENT", "ENQUEUED");

    private static final String SCHEMA_PATTERN = "^[a-z0-9_]+$";
    private static final String UNKNOWN_ROLE = "UNKNOWN";

    private final GlificDeliveryStatusService glificDeliveryStatusService;
    private final JdbcTemplate jdbcTemplate;

    @Value("${glific.status.reconcile.enabled:false}")
    private boolean enabled;

    @Value("${glific.status.reconcile.window-hours:6}")
    private long windowHours;

    @Value("${glific.status.reconcile.page-size:250}")
    private int pageSize;

    @Value("${glific.status.reconcile.max-pages:40}")
    private int maxPages;

    @Value("${glific.status.reconcile.date-column:inserted_at}")
    private String dateColumn;

    /** Explicit override; blank means "derive from the configured daily-report template ids". */
    @Value("${glific.status.reconcile.template-ids:}")
    private String templateIdsCsv;

    /**
     * Failure codes that are properties of the Gupshup <em>account</em>, not of any recipient.
     * {@code 9999} ("low balance") fails every message in flight regardless of who it was for, so
     * counting it as N officer failures would read as a mass data problem instead of a billing one.
     */
    @Value("${glific.status.reconcile.account-level-error-codes:9999}")
    private String accountLevelErrorCodesCsv;

    // The four daily-report templates, whichever mode is live. Read here rather than passed in so the
    // job needs no configuration of its own in the common case.
    @Value("${glific.template.daily-report-so-id:}")
    private String dailyReportSoTemplateId;

    @Value("${glific.template.daily-report-sdo-id:}")
    private String dailyReportSdoTemplateId;

    @Value("${glific.template.daily-report-so-link-id:}")
    private String dailyReportSoLinkTemplateId;

    @Value("${glific.template.daily-report-sdo-link-id:}")
    private String dailyReportSdoLinkTemplateId;

    /**
     * Runs a reconciliation pass over the trailing window.
     *
     * <p>Off by default: it costs Glific calls that share the 500 ms throttle with live sends, so a
     * deployment opts in once the window and interval suit its volume.</p>
     */
    @Scheduled(fixedDelayString = "${glific.status.reconcile.interval-ms:1800000}",
            initialDelayString = "${glific.status.reconcile.initial-delay-ms:600000}")
    public void reconcileScheduled() {
        if (!enabled) {
            return;
        }
        Instant to = Instant.now();
        reconcile(to.minus(Duration.ofHours(windowHours)), to);
    }

    /**
     * Reconciles one explicit window. Separate from the scheduled entry point so an operator can drive
     * a past window (for a retro-check against Glific's console) without waiting for the timer.
     */
    public void reconcile(Instant from, Instant to) {
        long startNanos = System.nanoTime();
        Set<Integer> templateIds = resolveTemplateIds();
        if (templateIds.isEmpty()) {
            log.warn("[GlificStatus] No daily-report template ids configured — every message in the window"
                    + " would be discarded. Set GLIFIC_STATUS_RECONCILE_TEMPLATE_IDS or the"
                    + " glific.template.daily-report-* properties. Skipping this pass.");
            return;
        }

        WindowScan scan = scanWindow(from, to, templateIds);
        // A window with nothing of ours in it still emits a summaryTotal, in the same shape as a busy
        // one. A silent pass is indistinguishable from a broken job, and a differently-shaped line
        // would break whatever parses these.
        Map<Long, OfficerRef> officersByContactId =
                scan.matched().isEmpty() ? Map.of() : resolveOfficers(contactIdsOf(scan.matched()));
        Map<String, Tally> byTenant = new LinkedHashMap<>();
        Tally total = new Tally();
        int unmappedContacts = 0;

        for (GlificMessageStatus message : scan.matched()) {
            OfficerRef officer = message.receiverContactId() == null
                    ? null
                    : officersByContactId.get(message.receiverContactId());
            if (officer == null) {
                unmappedContacts++;
                logUnmapped(message);
                continue;
            }
            Tally tenantTally = byTenant.computeIfAbsent(officer.tenantKey(), k -> new Tally());
            record(tenantTally, officer, message);
            record(total, officer, message);
            logMessage(officer, message);
        }

        byTenant.forEach((tenantKey, tally) -> {
            logTenantSummary(from, to, tenantKey, tally);
            logFailedOfficers(from, to, tenantKey, tally);
        });
        logTotal(from, to, byTenant.size(), total, scan, unmappedContacts,
                (System.nanoTime() - startNanos) / 1_000_000L);
        reportAccountLevelFailures(scan);
    }

    // ── Window scan ────────────────────────────────────────────────────────────

    /**
     * What one pass pulled from Glific.
     *
     * @param matched                 outbound HSMs on one of our daily-report templates
     * @param windowScanned           every message returned across the statuses we query — the sanity
     *                                total that makes a pathological window visible. Not the whole
     *                                window: {@code RECEIVED} and {@code DELETED} are never fetched
     * @param discardedInbound        inbound or non-HSM messages dropped. Dropping these is a
     *                                correctness requirement: an inbound message's {@code receiver} is
     *                                our own org contact, not an officer
     * @param discardedOtherTemplates outbound HSMs belonging to nudges, OTPs or other templates
     * @param accountLevelFailures    failures whose code is account-wide, counted across the
     *                                <em>unfiltered</em> set — a low-balance rejection can arrive with
     *                                a null {@code templateId} and would otherwise be filtered away
     */
    private record WindowScan(List<GlificMessageStatus> matched, int windowScanned, int discardedInbound,
                              int discardedOtherTemplates, Map<String, Integer> accountLevelFailures) {}

    private WindowScan scanWindow(Instant from, Instant to, Set<Integer> templateIds) {
        List<GlificMessageStatus> matched = new ArrayList<>();
        Map<String, Integer> accountLevel = new TreeMap<>();
        Set<String> accountLevelCodes = csvToSet(accountLevelErrorCodesCsv);
        int windowScanned = 0;
        int discardedInbound = 0;
        int discardedOtherTemplates = 0;

        for (String status : STATUSES_TO_CHECK) {
            int count = glificDeliveryStatusService.countMessages(from, to, status, dateColumn);
            if (count == 0) {
                continue;
            }
            List<GlificMessageStatus> page =
                    glificDeliveryStatusService.fetchMessages(from, to, status, dateColumn, pageSize, maxPages);
            windowScanned += page.size();
            for (GlificMessageStatus message : page) {
                if (isAccountLevel(message, accountLevelCodes)) {
                    accountLevel.merge(message.failureKey(), 1, Integer::sum);
                }
                if (!message.isOutboundHsm()) {
                    discardedInbound++;
                } else if (message.templateId() == null || !templateIds.contains(message.templateId())) {
                    discardedOtherTemplates++;
                } else {
                    matched.add(message);
                }
            }
        }
        return new WindowScan(matched, windowScanned, discardedInbound, discardedOtherTemplates, accountLevel);
    }

    private static boolean isAccountLevel(GlificMessageStatus message, Set<String> accountLevelCodes) {
        return message.outcome() == GlificDeliveryOutcome.DELIVERY_FAILED
                && message.errorCode() != null
                && accountLevelCodes.contains(message.errorCode());
    }

    // ── Officer resolution ─────────────────────────────────────────────────────

    /** An officer identified from a Glific contact id, with just enough context to log a line. */
    private record OfficerRef(int tenantId, String tenantSchema, long officerUserId, String role) {
        String tenantKey() {
            return tenantId + "|" + tenantSchema;
        }
    }

    private static Set<Long> contactIdsOf(List<GlificMessageStatus> messages) {
        Set<Long> ids = new LinkedHashSet<>();
        for (GlificMessageStatus m : messages) {
            if (m.receiverContactId() != null) {
                ids.add(m.receiverContactId());
            }
        }
        return ids;
    }

    /**
     * Maps Glific contact ids to officers, one batched query per active tenant.
     *
     * <p>Resolution runs contact-id-first because Glific's window carries no tenant context. Glific
     * contact ids are globally unique, so the first tenant that claims one wins; two tenants claiming
     * the same id means a stale {@code whatsapp_connection_id} somewhere and is warned about rather
     * than silently resolved.</p>
     */
    private Map<Long, OfficerRef> resolveOfficers(Set<Long> contactIds) {
        Map<Long, OfficerRef> byContactId = new HashMap<>();
        if (contactIds.isEmpty()) {
            return byContactId;
        }
        for (TenantRef tenant : activeTenants()) {
            if (!tenant.schema().matches(SCHEMA_PATTERN)) {
                log.warn("[GlificStatus] Skipping tenant={} — schema '{}' is not a valid identifier",
                        tenant.id(), tenant.schema());
                continue;
            }
            for (OfficerRow row : queryOfficers(tenant, contactIds)) {
                OfficerRef existing = byContactId.putIfAbsent(row.contactId(),
                        new OfficerRef(tenant.id(), tenant.schema(), row.userId(), row.role()));
                if (existing != null) {
                    log.warn("[GlificStatus] Glific contactId={} is claimed by both tenant={} and tenant={}"
                                    + " — keeping the first. A stale whatsapp_connection_id is the usual cause.",
                            row.contactId(), existing.tenantId(), tenant.id());
                }
            }
        }
        return byContactId;
    }

    private record TenantRef(int id, String schema) {}

    private record OfficerRow(long contactId, long userId, String role) {}

    /**
     * Active tenants, using the same status filter as {@code TenantSchedulerManager}: everything except
     * INACTIVE(0), SUSPENDED(4), ARCHIVED(6) and REGISTERED(7). A REGISTERED tenant has no schema at
     * all, so querying it would throw.
     */
    private List<TenantRef> activeTenants() {
        return jdbcTemplate.query(
                "SELECT id, state_code FROM common_schema.tenant_master_table"
                        + " WHERE deleted_at IS NULL AND status IN (1, 2, 3, 5) ORDER BY id",
                (rs, n) -> new TenantRef(rs.getInt("id"),
                        "tenant_" + rs.getString("state_code").toLowerCase(Locale.ROOT)));
    }

    /**
     * The batched contact-id lookup — one query per tenant per pass, never one per message.
     *
     * <p>{@code tenantSchema} is validated against {@link #SCHEMA_PATTERN} by the caller before
     * interpolation (a schema name is an SQL identifier and cannot be bound as {@code ?}); the contact
     * ids bind as parameters. Mirrors {@code NotificationEventRouter.resolveOfficerContactsByIds}.</p>
     */
    @SuppressWarnings("java:S2077")
    private List<OfficerRow> queryOfficers(TenantRef tenant, Set<Long> contactIds) {
        String sql = "SELECT u.id, u.whatsapp_connection_id, ut.c_name AS user_type FROM "
                + tenant.schema() + ".user_table u"
                + " JOIN common_schema.user_type_master_table ut ON ut.id = u.user_type"
                + " WHERE u.whatsapp_connection_id IN (" + placeholders(contactIds.size()) + ")";
        try {
            return jdbcTemplate.query(sql,
                    (rs, n) -> new OfficerRow(
                            rs.getLong("whatsapp_connection_id"),
                            rs.getLong("id"),
                            rs.getString("user_type") == null
                                    ? UNKNOWN_ROLE
                                    : rs.getString("user_type").toUpperCase(Locale.ROOT)),
                    contactIds.toArray());
        } catch (Exception e) {
            // One tenant's schema being absent or mid-migration must not abort the whole pass.
            log.warn("[GlificStatus] Could not resolve officers in tenant={} schema={}: {}",
                    tenant.id(), tenant.schema(), e.getMessage());
            return List.of();
        }
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    // ── Tallying ───────────────────────────────────────────────────────────────

    /** One failed delivery, kept so the per-role/per-code officer lists can be grouped at log time. */
    private record FailedEntry(String role, long officerUserId, String code) {}

    private static final class Tally {
        private final Map<String, Integer> deliveredByRole = new TreeMap<>();
        private final Map<String, Integer> readByRole = new TreeMap<>();
        private final Map<String, Integer> failedByRole = new TreeMap<>();
        private final Map<String, Integer> failedByCode = new TreeMap<>();
        private final List<FailedEntry> failures = new ArrayList<>();
        private int matched;
        private int pending;
        private int unknownStatus;
    }

    private static void record(Tally tally, OfficerRef officer, GlificMessageStatus message) {
        tally.matched++;
        switch (message.outcome()) {
            case DELIVERED -> tally.deliveredByRole.merge(officer.role(), 1, Integer::sum);
            case READ -> tally.readByRole.merge(officer.role(), 1, Integer::sum);
            case DELIVERY_FAILED -> {
                tally.failedByRole.merge(officer.role(), 1, Integer::sum);
                tally.failedByCode.merge(message.failureKey(), 1, Integer::sum);
                tally.failures.add(new FailedEntry(officer.role(), officer.officerUserId(), message.failureKey()));
            }
            case PENDING -> tally.pending++;
            default -> tally.unknownStatus++;
        }
    }

    // ── Logging ────────────────────────────────────────────────────────────────

    /**
     * One line per message. {@code result=} and {@code role=} are adjacent, and {@code tenant=} and
     * {@code officer=} follow in that order.
     */
    private void logMessage(OfficerRef officer, GlificMessageStatus message) {
        if (message.outcome() == GlificDeliveryOutcome.DELIVERY_FAILED) {
            // Redacted again at the point of logging, even though GlificDeliveryStatusService already
            // redacts what it extracts. The reason text originates with Gupshup and is the one field
            // here that can carry a phone number; a second pass costs nothing and means a future code
            // path that builds a GlificMessageStatus some other way cannot leak one through this line.
            log.warn("[GlificStatus] result=DELIVERY_FAILED role={} tenant={} officer={} glificMsgId={}"
                            + " glificContactId={} templateId={} bspStatus={} errorCode={} reason=\"{}\"",
                    officer.role(), officer.tenantId(), officer.officerUserId(), message.messageId(),
                    message.receiverContactId(), message.templateId(), message.bspStatus(),
                    message.errorCode() == null ? "-" : message.errorCode(),
                    message.errorReason() == null ? "" : PhoneRedactor.redact(message.errorReason()));
            return;
        }
        log.info("[GlificStatus] result={} role={} tenant={} officer={} glificMsgId={} glificContactId={}"
                        + " templateId={} bspStatus={}",
                message.outcome(), officer.role(), officer.tenantId(), officer.officerUserId(),
                message.messageId(), message.receiverContactId(), message.templateId(), message.bspStatus());
    }

    /**
     * A message on one of our templates whose recipient matches no officer in any tenant. Logged rather
     * than dropped: it usually means a stale {@code whatsapp_connection_id}, which is worth fixing.
     */
    private void logUnmapped(GlificMessageStatus message) {
        log.warn("[GlificStatus] result=UNMAPPED_CONTACT glificMsgId={} glificContactId={} templateId={}"
                        + " bspStatus={} — no officer in any active tenant has this"
                        + " whatsapp_connection_id",
                message.messageId(), message.receiverContactId(), message.templateId(), message.bspStatus());
    }

    private void logTenantSummary(Instant from, Instant to, String tenantKey, Tally tally) {
        log.info("[GlificStatus] summary: window={}→{} tenant={} matched={} deliveredByRole={}"
                        + " readByRole={} failedByRole={} failedByCode={} pending={} unknownStatus={}",
                from, to, tenantIdOf(tenantKey), tally.matched, tally.deliveredByRole, tally.readByRole,
                tally.failedByRole, tally.failedByCode, tally.pending, tally.unknownStatus);
    }

    /**
     * The officer ids behind each failure, grouped by role and code.
     */
    private void logFailedOfficers(Instant from, Instant to, String tenantKey, Tally tally) {
        if (tally.failures.isEmpty()) {
            return;
        }
        Map<String, List<Long>> grouped = tally.failures.stream().collect(Collectors.groupingBy(
                f -> f.role() + "|" + f.code(),
                TreeMap::new,
                Collectors.mapping(FailedEntry::officerUserId, Collectors.toList())));
        grouped.forEach((key, officers) -> {
            String[] parts = key.split("\\|", 2);
            log.warn("[GlificStatus] failedOfficers: window={}→{} tenant={} role={} errorCode={} count={}"
                            + " officers={}",
                    from, to, tenantIdOf(tenantKey), parts[0], parts[1], officers.size(), officers);
        });
    }

    private void logTotal(Instant from, Instant to, int tenants, Tally total, WindowScan scan,
                          int unmappedContacts, long tookMs) {
        log.info("[GlificStatus] summaryTotal: window={}→{} tenants={} matched={} windowScanned={}"
                        + " discardedInbound={} discardedOtherTemplates={} unmappedContacts={}"
                        + " deliveredByRole={} readByRole={} failedByRole={} failedByCode={}"
                        + " pending={} unknownStatus={} tookMs={}",
                from, to, tenants, total.matched, scan.windowScanned(), scan.discardedInbound(),
                scan.discardedOtherTemplates(), unmappedContacts, total.deliveredByRole, total.readByRole,
                total.failedByRole, total.failedByCode, total.pending, total.unknownStatus, tookMs);
    }

    /**
     * Account-wide failures get their own line, because they are not facts about any officer. A Gupshup
     * balance of zero produces a run in which every message failed for a reason no officer data can
     * explain, and burying that inside {@code failedByCode} reads as a mass recipient problem.
     */
    private void reportAccountLevelFailures(WindowScan scan) {
        if (scan.accountLevelFailures().isEmpty()) {
            return;
        }
        scan.accountLevelFailures().forEach((code, count) ->
                log.error("[GlificStatus] ACCOUNT-LEVEL FAILURE: errorCode={} affected={} message(s) in the"
                                + " window — this is a Gupshup account condition (e.g. low balance), not an"
                                + " officer or recipient problem. Messages counted here include ones outside"
                                + " the daily-report templates.",
                        code, count));
    }

    private static String tenantIdOf(String tenantKey) {
        int sep = tenantKey.indexOf('|');
        return sep < 0 ? tenantKey : tenantKey.substring(0, sep);
    }

    // ── Configuration helpers ──────────────────────────────────────────────────

    /**
     * The template ids that mark a message as a daily report: the explicit override when set, otherwise
     * every configured daily-report template across both delivery modes. Both modes are included
     * deliberately — a window can straddle a DOCUMENT→LINK switch, and the SDO ids fall back to the SO
     * ones at send time, so over-including costs nothing while under-including loses messages.
     */
    Set<Integer> resolveTemplateIds() {
        Set<String> raw = templateIdsCsv == null || templateIdsCsv.isBlank()
                ? new LinkedHashSet<>(Arrays.asList(dailyReportSoTemplateId, dailyReportSdoTemplateId,
                        dailyReportSoLinkTemplateId, dailyReportSdoLinkTemplateId))
                : csvToSet(templateIdsCsv);
        Set<Integer> ids = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null || value.isBlank()) {
                continue;
            }
            try {
                ids.add(Integer.parseInt(value.trim()));
            } catch (NumberFormatException e) {
                log.warn("[GlificStatus] Ignoring non-numeric daily-report template id '{}'", value);
            }
        }
        return ids;
    }

    private static Set<String> csvToSet(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
