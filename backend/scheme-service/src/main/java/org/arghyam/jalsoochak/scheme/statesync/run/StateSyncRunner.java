package org.arghyam.jalsoochak.scheme.statesync.run;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.scheme.service.PiiEncryptionService;
import org.arghyam.jalsoochak.scheme.statesync.config.StateSyncProperties;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamNode;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamPerson;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamScheme;
import org.arghyam.jalsoochak.scheme.statesync.port.StateMasterDataSource;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.HierarchyReconciler;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.HierarchyReconciler.DepartmentOutcome;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.SchemeReconciler;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.StateSyncTenantRepository;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.StateSyncTenantRepository.LocationNode;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.StateSyncTenantRepository.UserRow;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.UserDirectory;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.UserSchemeMappings;
import org.arghyam.jalsoochak.scheme.statesync.run.StateSyncEventPublisher.PendingEvents;
import org.arghyam.jalsoochak.scheme.statesync.run.StateSyncRunRepository.Tenant;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetAddress;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs one state sync end to end:
 * <ol>
 *   <li><b>claim</b> the tenant's run lock (a RUNNING row in {@code state_sync_run_table}; see
 *       {@link StateSyncRunRepository#claim}) — a second pod, or an overlapping cron, gets nothing and
 *       skips;</li>
 *   <li><b>fetch</b> everything from the upstream before touching the database, so no transaction is
 *       held open across HTTP calls;</li>
 *   <li><b>reconcile</b> inside one transaction, rolled back at the end in {@code DRY_RUN};</li>
 *   <li><b>publish</b> analytics events, after commit and only in {@code APPLY};</li>
 *   <li><b>finish</b> the run row with its counts and persist its issues — in both modes.</li>
 * </ol>
 * A heartbeat thread refreshes the run row for the whole run — fetch, placeholder look-ups and
 * reconcile — outside the data transaction, so other pods see it while that transaction is open. Just
 * before committing, the data transaction re-reads its run row under a lock and rolls back if another
 * pod has taken the run over.
 */
@Service
@Slf4j
public class StateSyncRunner {

    /** Thrown when a run is requested while the feature flag is off. */
    public static class SyncDisabledException extends RuntimeException {
        public SyncDisabledException() {
            super("State sync is disabled (state-sync.enabled=false)");
        }
    }

    /** Thrown when the tenant or actor the sync needs is not configured or does not exist. */
    public static class SyncMisconfiguredException extends RuntimeException {
        public SyncMisconfiguredException(String message) {
            super(message);
        }
    }

    public record RunResult(long runId, boolean succeeded, Map<String, Integer> counts, List<SyncIssue> issues,
                            String error) {
    }

    private final StateSyncProperties properties;
    private final ObjectProvider<StateMasterDataSource> sourceProvider;
    private final StateSyncRunRepository runRepository;
    private final StateSyncTenantRepository tenantRepository;
    private final HierarchyReconciler hierarchyReconciler;
    private final PiiEncryptionService pii;
    private final StateSyncEventPublisher eventPublisher;
    private final TransactionTemplate dataTransaction;
    /** IMIS id → when to ask again, for placeholders the upstream did not know. Per pod; a restart just asks again. */
    private final Map<String, Instant> placeholderCooldown = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "state-sync-run");
        t.setDaemon(true);
        return t;
    });

    public StateSyncRunner(StateSyncProperties properties, ObjectProvider<StateMasterDataSource> sourceProvider,
                           StateSyncRunRepository runRepository, StateSyncTenantRepository tenantRepository,
                           HierarchyReconciler hierarchyReconciler, PiiEncryptionService pii,
                           StateSyncEventPublisher eventPublisher, PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.sourceProvider = sourceProvider;
        this.runRepository = runRepository;
        this.tenantRepository = tenantRepository;
        this.hierarchyReconciler = hierarchyReconciler;
        this.pii = pii;
        this.eventPublisher = eventPublisher;
        this.dataTransaction = new TransactionTemplate(transactionManager);
    }

    /** Scheduled entry point: runs on the caller's thread; a held lock means another pod has it — skip. */
    public void runScheduled(RunKind kind) {
        try {
            Optional<RunResult> result = runNow(kind, null, "SCHEDULER");
            if (result.isEmpty()) {
                log.info("[state-sync] {} skipped: another run holds the lock", kind);
            }
        } catch (RuntimeException e) {
            log.error("[state-sync] scheduled {} could not start: {}", kind, e.getMessage());
        }
    }

    /**
     * Claims the lock and runs on the caller's thread.
     *
     * @param schemeRef upstream scheme code or IMIS id, for {@link RunKind#SCHEME_REFRESH} only
     * @return empty when another run holds the lock
     */
    public Optional<RunResult> runNow(RunKind kind, String schemeRef, String triggeredBy) {
        Context ctx = prepare();
        return claim(ctx, kind, triggeredBy).map(runId -> execute(ctx, runId, kind, schemeRef));
    }

    /**
     * Claims the lock on the caller's thread (so the caller learns at once whether it got it) and runs
     * in the background.
     *
     * @return the run id, or empty when another run holds the lock
     */
    public Optional<Long> startAsync(RunKind kind, String triggeredBy) {
        Context ctx = prepare();
        Optional<Long> runId = claim(ctx, kind, triggeredBy);
        runId.ifPresent(id -> executor.submit(() -> execute(ctx, id, kind, null)));
        return runId;
    }

    // ── run ─────────────────────────────────────────────────────────────────

    private record Context(Tenant tenant, int actor, StateMasterDataSource source) {
    }

    private Context prepare() {
        if (!properties.isEnabled()) {
            throw new SyncDisabledException();
        }
        Tenant tenant = runRepository.findTenant(properties.getTenantCode())
                .orElseThrow(() -> new SyncMisconfiguredException(
                        "state-sync.tenant-code does not name a live tenant: " + properties.getTenantCode()));
        if (properties.getActorUserId() == null) {
            throw new SyncMisconfiguredException("state-sync.actor-user-id (STATE_SYNC_ACTOR_USER_ID) is required");
        }
        StateMasterDataSource source = sourceProvider.getIfAvailable();
        if (source == null) {
            throw new SyncMisconfiguredException("no state master-data source is configured");
        }
        return new Context(tenant, properties.getActorUserId(), source);
    }

    private Optional<Long> claim(Context ctx, RunKind kind, String triggeredBy) {
        return runRepository.claim(ctx.tenant().id(), kind, properties.getMode(), triggeredBy, owner(),
                properties.getStaleRunAfter());
    }

    RunResult execute(Context ctx, long runId, RunKind kind, String schemeRef) {
        SyncReport report = new SyncReport();
        String error = null;
        boolean dryRun = properties.getMode() == StateSyncProperties.Mode.DRY_RUN;
        log.info("[state-sync] run {} {} started (mode={}, tenant={})", runId, kind, properties.getMode(),
                ctx.tenant().stateCode());
        ScheduledExecutorService heartbeat = startHeartbeat(runId);
        try {
            Fetched fetched = fetch(ctx, runId, kind, schemeRef, report);
            PendingEvents events = dataTransaction.execute(status -> {
                PendingEvents pending = reconcile(ctx, kind, fetched, report);
                if (!runRepository.stillRunning(runId)) {
                    throw new IllegalStateException("run " + runId + " lost its lock to another pod; rolled back");
                }
                if (dryRun) {
                    status.setRollbackOnly();
                }
                return pending;
            });
            if (!dryRun && events != null) {
                StateSyncEventPublisher.Outcome published =
                        eventPublisher.publish(ctx.tenant().schemaName(), ctx.tenant().id(), events);
                report.add("events.failed", published.failures());
                for (SchemeReconciler.Reassignment move : published.failedReassignments()) {
                    report.issue("SCHEME", null, "REASSIGNMENT_EVENT_FAILED",
                            Map.of("fromSchemeId", move.fromSchemeId(), "toSchemeId", move.toSchemeId()));
                }
            }
        } catch (RuntimeException e) {
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("[state-sync] run {} {} failed: {}", runId, kind, error, e);
        } finally {
            heartbeat.shutdownNow();
        }
        boolean succeeded = error == null;
        try {
            runRepository.insertIssues(runId, ctx.tenant().id(), report.issues());
        } catch (RuntimeException e) {
            log.error("[state-sync] run {}: could not persist {} issue(s): {}", runId, report.issues().size(), e.getMessage());
        }
        runRepository.finish(runId, succeeded, report, error);
        log.info("[state-sync] run {} {} {} — {} issue(s), counts={}", runId, kind,
                succeeded ? "succeeded" : "failed", report.issues().size(), report.counts());
        return new RunResult(runId, succeeded, report.counts(), report.issues(), error);
    }

    // ── fetch ───────────────────────────────────────────────────────────────

    private record Fetched(HierarchyReconciler.DepartmentTree departments, HierarchyReconciler.LgdTree lgd,
                           List<UpstreamPerson> users, List<UpstreamScheme> schemes, List<String> archived,
                           List<String> blocked) {
    }

    private Fetched fetch(Context ctx, long runId, RunKind kind, String schemeRef, SyncReport report) {
        StateMasterDataSource src = ctx.source();
        return switch (kind) {
            case FULL -> {
                var departments = new HierarchyReconciler.DepartmentTree(
                        step(runId, "zones", src::zones, report), step(runId, "circles", src::circles, report),
                        step(runId, "divisions", src::divisions, report),
                        step(runId, "subdivisions", src::subdivisions, report));
                var lgd = new HierarchyReconciler.LgdTree(
                        step(runId, "districts", src::districts, report), step(runId, "blocks", src::blocks, report),
                        step(runId, "panchayats", src::panchayats, report),
                        step(runId, "villages", src::villages, report));
                List<UpstreamPerson> users = step(runId, "users", src::users, report);
                List<UpstreamScheme> schemes = step(runId, "schemes", () -> src.schemes(null), report);
                List<String> archived = step(runId, "archived_schemes", src::archivedSchemeCodes, report);
                List<String> blocked = step(runId, "blocked_users", src::blockedUserCodes, report);
                yield new Fetched(departments, lgd, users, schemes, archived, blocked);
            }
            case DELTA -> {
                LocalDateTime since = runRepository.lastAppliedWatermark(ctx.tenant().id())
                        .map(w -> w.minus(properties.getDeltaOverlap()))
                        .orElse(null);
                report.add("delta.full_scheme_crawl", since == null ? 1 : 0);
                List<UpstreamScheme> schemes = new ArrayList<>(step(runId, "schemes", () -> src.schemes(since), report));
                schemes.addAll(lookUpPlaceholders(ctx, schemes, report));
                yield new Fetched(null, null, List.of(), schemes, List.of(), List.of());
            }
            case SCHEME_REFRESH -> {
                if (schemeRef == null || schemeRef.isBlank()) {
                    throw new IllegalArgumentException("a scheme code or IMIS id is required");
                }
                String ref = schemeRef.trim();
                Optional<UpstreamScheme> scheme = ref.chars().allMatch(Character::isDigit)
                        ? src.schemeByCentreSchemeId(ref)
                        : src.schemeByCode(ref);
                if (scheme.isEmpty()) {
                    report.issue("SCHEME", ref, "NOT_FOUND_UPSTREAM", Map.of());
                }
                yield new Fetched(null, null, List.of(), scheme.map(List::of).orElse(List.of()), List.of(), List.of());
            }
        };
    }

    /**
     * LENIENT-INGEST follow-up: asks the upstream about the newest placeholder schemes lenient ingestion
     * created for readings against an unknown IMIS id. One the upstream knows is reconciled like any
     * other scheme (and raises PLACEHOLDER_SUPERSEDED); one it does not know is left alone for
     * {@code placeholder-retry-after}.
     */
    private List<UpstreamScheme> lookUpPlaceholders(Context ctx, List<UpstreamScheme> alreadyFetched, SyncReport report) {
        if (properties.getPlaceholderLookupsPerRun() <= 0) {
            return List.of();
        }
        Set<String> fetchedCentreIds = new HashSet<>();
        alreadyFetched.forEach(s -> fetchedCentreIds.add(s.centreSchemeId()));
        Instant now = Instant.now();
        placeholderCooldown.values().removeIf(until -> until.isBefore(now));

        List<UpstreamScheme> found = new ArrayList<>();
        int budget = properties.getPlaceholderLookupsPerRun();
        for (String centreId : tenantRepository.unresolvedPlaceholderCentreIds(ctx.tenant().schemaName(), budget * 4)) {
            if (budget == 0) {
                break;
            }
            if (fetchedCentreIds.contains(centreId) || placeholderCooldown.containsKey(centreId)) {
                continue;
            }
            budget--;
            report.count("placeholders.looked_up");
            Optional<UpstreamScheme> scheme = ctx.source().schemeByCentreSchemeId(centreId);
            if (scheme.isPresent()) {
                found.add(scheme.get());
                report.count("placeholders.found_upstream");
            } else {
                placeholderCooldown.put(centreId, now.plus(properties.getPlaceholderRetryAfter()));
                report.count("placeholders.unknown_upstream");
            }
        }
        return found;
    }

    private <T> List<T> step(long runId, String name, Supplier<List<T>> call, SyncReport report) {
        List<T> rows = call.get();
        report.add("fetched." + name, rows.size());
        return rows;
    }

    /**
     * Refreshes the run row every quarter of {@code stale-run-after} (at most every minute) until the run
     * ends. Plain auto-committed updates on their own thread, so they are visible while the data
     * transaction is still open.
     */
    private ScheduledExecutorService startHeartbeat(long runId) {
        ScheduledExecutorService beat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "state-sync-heartbeat");
            t.setDaemon(true);
            return t;
        });
        long periodMs = Math.max(1000L, Math.min(properties.getStaleRunAfter().toMillis() / 4, 60_000L));
        beat.scheduleAtFixedRate(() -> {
            try {
                if (!runRepository.heartbeat(runId)) {
                    log.warn("[state-sync] run {} is no longer RUNNING; it will roll back before committing", runId);
                }
            } catch (RuntimeException e) {
                log.warn("[state-sync] run {} heartbeat failed: {}", runId, e.getMessage());
            }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS);
        return beat;
    }

    // ── reconcile ───────────────────────────────────────────────────────────

    private PendingEvents reconcile(Context ctx, RunKind kind, Fetched fetched, SyncReport report) {
        String schema = ctx.tenant().schemaName();
        int tenantId = ctx.tenant().id();
        List<Map<String, Object>> departmentEvents = new ArrayList<>();

        if (kind == RunKind.FULL) {
            DepartmentOutcome dept = hierarchyReconciler.reconcileDepartments(schema, fetched.departments(), ctx.actor(), report);
            departmentEvents.addAll(departmentEvents(tenantId, dept));
            hierarchyReconciler.reconcileLgd(schema, fetched.lgd(), ctx.actor(), report);
        }

        boolean needsUsers = !fetched.schemes().isEmpty() || !fetched.users().isEmpty() || !fetched.blocked().isEmpty();
        if (needsUsers && !pii.isEnabled()) {
            throw new IllegalStateException("PII_ENCRYPTION_KEY / PII_HMAC_KEY are not configured; users cannot be written");
        }
        if (!needsUsers && kind != RunKind.FULL) {
            return new PendingEvents(List.of(), List.of(), departmentEvents, List.of());
        }

        UserDirectory users = new UserDirectory(tenantRepository, pii, schema, tenantId, ctx.actor());
        UserSchemeMappings mappings = new UserSchemeMappings(tenantRepository, schema, ctx.actor());
        for (UpstreamPerson person : fetched.users()) {
            users.resolve(person, report);
        }

        SchemeReconciler schemes = new SchemeReconciler(tenantRepository, properties, schema, ctx.actor(), users, mappings);
        schemes.reconcile(fetched.schemes(), report);

        if (kind == RunKind.FULL) {
            schemes.applyArchived(fetched.archived(), report);
            for (String code : fetched.blocked()) {
                users.block(code, report).ifPresent(u -> mappings.retireAllForUser(u.id(), report));
            }
            Set<String> listed = new HashSet<>();
            fetched.schemes().forEach(s -> listed.add(s.code()));
            schemes.reportAbsent(listed, new HashSet<>(fetched.archived()), report);
        }
        return new PendingEvents(List.copyOf(schemes.touchedSchemes()), userEvents(tenantId, users, mappings),
                departmentEvents, schemes.reassignments());
    }

    private static List<Map<String, Object>> userEvents(int tenantId, UserDirectory users, UserSchemeMappings mappings) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (UserRow user : users.users(users.createdIds())) {
            events.add(userEvent("USER_CREATED", tenantId, user));
        }
        for (UserRow user : users.users(users.updatedIds())) {
            events.add(userEvent("USER_UPDATED", tenantId, user));
        }
        for (Integer userId : mappings.touchedUsers()) {
            users.byId(userId).ifPresent(user -> {
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("eventType", "USER_SCHEME_MAPPINGS_REPLACED");
                event.put("userId", user.id());
                event.put("tenantId", tenantId);
                event.put("userUuid", user.uuid());
                event.put("schemeIds", List.copyOf(mappings.liveSchemes(user.id())));
                event.put("status", 1);
                events.add(event);
            });
        }
        return events;
    }

    /** No title: analytics keeps the name it has, and the sync never puts a plaintext name on the wire. */
    private static Map<String, Object> userEvent(String type, int tenantId, UserRow user) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventType", type);
        event.put("userId", user.id());
        event.put("tenantId", tenantId);
        event.put("email", null);
        event.put("userType", user.userTypeId());
        event.put("uuid", user.uuid());
        event.put("status", user.status());
        return event;
    }

    private static List<Map<String, Object>> departmentEvents(int tenantId, DepartmentOutcome outcome) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (Integer id : outcome.touched()) {
            LocationNode node = outcome.nodesById().get(id);
            if (node == null) {
                continue;
            }
            Map<Integer, Integer> levels = HierarchyReconciler.ancestorsByLevel(id, outcome.nodesById());
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventType", "DEPARTMENT_LOCATION_UPDATED");
            event.put("departmentId", id);
            event.put("tenantId", tenantId);
            event.put("departmentCName", node.title());
            event.put("title", node.title());
            event.put("departmentLevel", node.level());
            for (int level = 1; level <= 6; level++) {
                event.put("level" + level + "DeptId", levels.getOrDefault(level, 0));
            }
            events.add(event);
        }
        return events;
    }

    private static String owner() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isBlank()) {
            try {
                host = InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "unknown";
            }
        }
        return host;
    }
}
