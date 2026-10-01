package org.arghyam.jalsoochak.scheme.statesync.reconcile;

import org.arghyam.jalsoochak.scheme.enums.SchemeOperatingStatus;
import org.arghyam.jalsoochak.scheme.enums.SchemeWorkStatus;
import org.arghyam.jalsoochak.scheme.statesync.config.StateSyncProperties;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamPerson;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamScheme;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.StateSyncTenantRepository.LocationMappingRow;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.StateSyncTenantRepository.LocationNode;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.StateSyncTenantRepository.SchemeRow;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.StateSyncTenantRepository.UserRow;
import org.arghyam.jalsoochak.scheme.statesync.run.SyncReport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Reconciles upstream schemes into {@code scheme_master_table} and the three tables that hang off it.
 * One instance serves one run against one tenant.
 *
 * <h2>Matching (first rule that fires wins; live, non-placeholder schemes only)</h2>
 * <ol>
 *   <li>{@code state_scheme_code} equals the upstream code;</li>
 *   <li>exactly one scheme holds both the IMIS ({@code centre_scheme_id}) and SMT ({@code state_scheme_id}) id;</li>
 *   <li>exactly one holds one of them and nobody holds the other — the other is adopted;</li>
 *   <li>the two ids point at different schemes, or one id at several → an issue, nothing written;</li>
 *   <li>neither id is known → a new scheme.</li>
 * </ol>
 * A scheme already claimed by an earlier upstream row in the same run is not matched again
 * ({@code SEVERAL_UPSTREAM_ROWS_MATCH_ONE_SCHEME}).
 *
 * <h2>Field ownership</h2>
 * Name, both ids, the public code, work / operating status, planned and achieved FHTC and coordinates
 * come from upstream, but a blank or unrecognised upstream value never overwrites what we hold.
 * Everything else on the row ({@code k_factor}, channel, household count…) is ours and untouched.
 *
 * <h2>Mappings</h2>
 * The scheme payload is the full current list of its villages, sub-divisions and officers. Missing
 * pairs are added (a retired officer pair is revived); pairs the payload no longer lists are retired —
 * <b>except</b> when the upstream list is empty and {@code retire-on-empty-list} is off, or when part of
 * the list could not be resolved (an unmatched village, an officer that could not be written), in
 * which case what we hold is kept. Only rows the sync owns are managed: LGD rows pointing at a village
 * (or the state placeholder), department rows pointing at a sub-division, and officer rows whose user
 * is one of {@link StateVocabulary#MANAGED_USER_TYPES}.
 */
public class SchemeReconciler {

    static final String LGD_VILLAGE_LEVEL = "VILLAGE";
    static final String LGD_STATE_LEVEL = "STATE";
    static final String DEPT_SUB_DIVISION_LEVEL = "Sub-division";

    private final StateSyncTenantRepository repository;
    private final StateSyncProperties properties;
    private final String schema;
    private final int actor;
    private final UserDirectory users;
    private final UserSchemeMappings userMappings;

    private final Map<Integer, SchemeRow> schemesById = new HashMap<>();
    private final Map<String, Integer> idByCode = new HashMap<>();
    private final Map<String, Set<Integer>> idsByCentre = new HashMap<>();
    private final Map<String, Set<Integer>> idsByState = new HashMap<>();
    /** IMIS id → live placeholders carrying it (one per submitted id pair, so possibly several). */
    private final Map<String, List<Integer>> placeholdersByCentre = new HashMap<>();
    private final Set<Integer> claimedThisRun = new HashSet<>();

    private final Map<String, Integer> lgdIdsByCode;
    private final Map<String, Integer> deptIdsByCode;
    private final Map<Integer, Integer> lgdLevelById = new HashMap<>();
    private final Map<Integer, Integer> deptLevelById = new HashMap<>();
    private final Integer stateLgdNodeId;
    private final Map<Integer, List<LocationMappingRow>> lgdMappingsByScheme = new HashMap<>();
    private final Map<Integer, List<LocationMappingRow>> deptMappingsByScheme = new HashMap<>();

    private final Set<Integer> touchedSchemes = new LinkedHashSet<>();
    private final List<Reassignment> reassignments = new ArrayList<>();

    public SchemeReconciler(StateSyncTenantRepository repository, StateSyncProperties properties, String schema,
                            int actor, UserDirectory users, UserSchemeMappings userMappings) {
        this.repository = repository;
        this.properties = properties;
        this.schema = schema;
        this.actor = actor;
        this.users = users;
        this.userMappings = userMappings;

        for (SchemeRow row : repository.liveSchemes(schema)) {
            if (row.autoProvisioned()) {
                if (!blank(row.centreSchemeId())) {
                    placeholdersByCentre.computeIfAbsent(row.centreSchemeId(), k -> new ArrayList<>()).add(row.id());
                }
                continue;
            }
            index(row);
        }

        List<LocationNode> lgdNodes = repository.lgdNodes(schema);
        Map<String, Integer> lgdCodes = new HashMap<>();
        List<Integer> stateNodes = new ArrayList<>();
        for (LocationNode node : lgdNodes) {
            lgdLevelById.put(node.id(), node.level());
            if (node.stateCode() != null) {
                lgdCodes.put(node.stateCode(), node.id());
            }
            if (node.level() == 1) {
                stateNodes.add(node.id());
            }
        }
        this.lgdIdsByCode = lgdCodes;
        this.stateLgdNodeId = stateNodes.size() == 1 ? stateNodes.get(0) : null;

        Map<String, Integer> deptCodes = new HashMap<>();
        for (LocationNode node : repository.departmentNodes(schema)) {
            deptLevelById.put(node.id(), node.level());
            if (node.stateCode() != null) {
                deptCodes.put(node.stateCode(), node.id());
            }
        }
        this.deptIdsByCode = deptCodes;

        repository.liveLgdMappings(schema).forEach(m ->
                lgdMappingsByScheme.computeIfAbsent(m.schemeId(), k -> new ArrayList<>()).add(m));
        repository.liveDepartmentMappings(schema).forEach(m ->
                deptMappingsByScheme.computeIfAbsent(m.schemeId(), k -> new ArrayList<>()).add(m));
    }

    /** Reconciles each scheme in turn; one bad row never stops the rest. */
    public void reconcile(Collection<UpstreamScheme> schemes, SyncReport report) {
        for (UpstreamScheme scheme : schemes) {
            report.seenSourceUpdatedAt(scheme.updatedAt());
            reconcileOne(scheme, report);
        }
    }

    /** @return our scheme id when the upstream scheme was written (or already matched), else empty */
    public Optional<Integer> reconcileOne(UpstreamScheme up, SyncReport report) {
        if (up.code() == null) {
            report.issue("SCHEME", null, "INVALID_UPSTREAM_ROW", Map.of("reason", "no code"));
            return Optional.empty();
        }
        Resolution resolution = match(up);
        if (resolution.issue != null) {
            report.issue("SCHEME", up.code(), resolution.issue, resolution.detail);
            return Optional.empty();
        }

        Optional<SchemeWorkStatus> work = StateVocabulary.workStatus(up.workStatus());
        Optional<SchemeOperatingStatus> operating = StateVocabulary.operatingStatus(up.operatingStatus());
        if (work.isEmpty() && up.workStatus() != null) {
            report.issue("SCHEME", up.code(), "UNKNOWN_WORK_STATUS", Map.of("value", up.workStatus()));
        }
        if (operating.isEmpty() && up.operatingStatus() != null) {
            report.issue("SCHEME", up.code(), "UNKNOWN_OPERATING_STATUS", Map.of("value", up.operatingStatus()));
        }

        int schemeId;
        boolean isNew = resolution.scheme == null;
        if (isNew) {
            if (blank(up.name()) || blank(up.centreSchemeId()) || blank(up.stateSchemeId()) || work.isEmpty()) {
                report.issue("SCHEME", up.code(), "CANNOT_CREATE_INCOMPLETE",
                        detail("hasName", !blank(up.name()), "hasCentreId", !blank(up.centreSchemeId()),
                                "hasStateId", !blank(up.stateSchemeId()), "hasWorkStatus", work.isPresent()));
                return Optional.empty();
            }
            if (operating.isEmpty()) {
                report.count("schemes.inserted_with_default_operating_status");
            }
            SchemeRow row = new SchemeRow(0, up.stateSchemeId(), up.centreSchemeId(), up.code(), up.name().trim(),
                    work.get().getCode(), operating.orElse(SchemeOperatingStatus.OPERATIVE).getCode(),
                    nz(up.plannedFhtc()), nz(up.achievedFhtc()),
                    StateVocabulary.coordinate(up.latitude()).orElse(null),
                    StateVocabulary.coordinate(up.longitude()).orElse(null), false);
            schemeId = repository.insertScheme(schema, row, actor);
            index(new SchemeRow(schemeId, row.stateSchemeId(), row.centreSchemeId(), row.stateSchemeCode(),
                    row.name(), row.workStatus(), row.operatingStatus(), row.plannedFhtc(), row.fhtcCount(),
                    row.latitude(), row.longitude(), false));
            touchedSchemes.add(schemeId);
            report.count("schemes.inserted");
        } else {
            SchemeRow old = resolution.scheme;
            schemeId = old.id();
            report.count("schemes.matched." + resolution.rule);
            SchemeRow merged = new SchemeRow(old.id(),
                    pick(up.stateSchemeId(), old.stateSchemeId()),
                    pick(up.centreSchemeId(), old.centreSchemeId()),
                    up.code(),
                    blank(up.name()) ? old.name() : up.name().trim(),
                    work.map(SchemeWorkStatus::getCode).orElse(old.workStatus()),
                    operating.map(SchemeOperatingStatus::getCode).orElse(old.operatingStatus()),
                    up.plannedFhtc() != null ? up.plannedFhtc() : old.plannedFhtc(),
                    up.achievedFhtc() != null ? up.achievedFhtc() : old.fhtcCount(),
                    StateVocabulary.coordinate(up.latitude()).orElse(old.latitude()),
                    StateVocabulary.coordinate(up.longitude()).orElse(old.longitude()),
                    false);
            if (!merged.equals(old)) {
                repository.updateScheme(schema, merged, actor);
                unindex(old);
                index(merged);
                touchedSchemes.add(schemeId);
                report.count("schemes.updated");
            } else {
                report.count("schemes.unchanged");
            }
        }
        claimedThisRun.add(schemeId);
        supersedePlaceholder(schemeId, up, report);

        reconcileVillages(schemeId, up, isNew, report);
        reconcileSubdivisions(schemeId, up, report);
        reconcileOfficers(schemeId, up, report);
        return Optional.of(schemeId);
    }

    // ── matching ────────────────────────────────────────────────────────────

    private record Resolution(SchemeRow scheme, String rule, String issue, Map<String, Object> detail) {
        static Resolution matched(SchemeRow scheme, String rule) {
            return new Resolution(scheme, rule, null, Map.of());
        }

        static Resolution create() {
            return new Resolution(null, null, null, Map.of());
        }

        static Resolution problem(String issue, Map<String, Object> detail) {
            return new Resolution(null, null, issue, detail);
        }
    }

    private Resolution match(UpstreamScheme up) {
        Integer byCode = idByCode.get(up.code());
        if (byCode != null) {
            return claim(schemesById.get(byCode), "by_code");
        }
        Set<Integer> centre = up.centreSchemeId() == null ? Set.of() : idsByCentre.getOrDefault(up.centreSchemeId(), Set.of());
        Set<Integer> state = up.stateSchemeId() == null ? Set.of() : idsByState.getOrDefault(up.stateSchemeId(), Set.of());

        Set<Integer> both = new HashSet<>(centre);
        both.retainAll(state);
        if (both.size() == 1) {
            return claim(schemesById.get(both.iterator().next()), "by_id_pair");
        }
        if (centre.size() > 1 || state.size() > 1 || both.size() > 1) {
            return Resolution.problem("AMBIGUOUS_ID_MATCHES_MULTIPLE_SCHEMES",
                    detail("centreMatches", List.copyOf(centre), "stateMatches", List.copyOf(state)));
        }
        if (centre.size() == 1 && state.size() == 1) {
            return Resolution.problem("CONFLICT_IDS_POINT_TO_DIFFERENT_SCHEMES",
                    detail("centreMatch", centre.iterator().next(), "stateMatch", state.iterator().next()));
        }
        if (centre.size() == 1) {
            return claim(schemesById.get(centre.iterator().next()), "by_centre_id");
        }
        if (state.size() == 1) {
            return claim(schemesById.get(state.iterator().next()), "by_state_id");
        }
        return Resolution.create();
    }

    private Resolution claim(SchemeRow scheme, String rule) {
        if (scheme.stateSchemeCode() != null && !"by_code".equals(rule)) {
            return Resolution.problem("SCHEME_HOLDS_OTHER_CODE",
                    Map.of("schemeId", scheme.id(), "ourCode", scheme.stateSchemeCode()));
        }
        if (claimedThisRun.contains(scheme.id())) {
            return Resolution.problem("SEVERAL_UPSTREAM_ROWS_MATCH_ONE_SCHEME", Map.of("schemeId", scheme.id()));
        }
        return Resolution.matched(scheme, rule);
    }

    private void index(SchemeRow row) {
        schemesById.put(row.id(), row);
        if (row.stateSchemeCode() != null) {
            idByCode.put(row.stateSchemeCode(), row.id());
        }
        if (row.centreSchemeId() != null) {
            idsByCentre.computeIfAbsent(row.centreSchemeId(), k -> new HashSet<>()).add(row.id());
        }
        if (row.stateSchemeId() != null) {
            idsByState.computeIfAbsent(row.stateSchemeId(), k -> new HashSet<>()).add(row.id());
        }
    }

    private void unindex(SchemeRow row) {
        schemesById.remove(row.id());
        if (row.stateSchemeCode() != null) {
            idByCode.remove(row.stateSchemeCode(), row.id());
        }
        Optional.ofNullable(idsByCentre.get(row.centreSchemeId())).ifPresent(s -> s.remove(row.id()));
        Optional.ofNullable(idsByState.get(row.stateSchemeId())).ifPresent(s -> s.remove(row.id()));
    }

    // ── villages ────────────────────────────────────────────────────────────

    private void reconcileVillages(int schemeId, UpstreamScheme up, boolean isNew, SyncReport report) {
        Set<Integer> desired = new LinkedHashSet<>();
        List<String> unresolved = new ArrayList<>();
        for (String code : up.villageCodes()) {
            Integer id = lgdIdsByCode.get(code);
            if (id != null && Objects.equals(lgdLevelById.get(id), HierarchyReconciler.LGD_VILLAGE)) {
                desired.add(id);
            } else {
                unresolved.add(code);
            }
        }
        if (!unresolved.isEmpty()) {
            report.issue("SCHEME", up.code(), "VILLAGE_UNRESOLVED", Map.of("schemeId", schemeId, "villageCodes", unresolved));
        }

        List<LocationMappingRow> live = lgdMappingsByScheme.computeIfAbsent(schemeId, k -> new ArrayList<>());
        Set<Integer> heldVillages = new HashSet<>();
        List<LocationMappingRow> villageRows = new ArrayList<>();
        List<LocationMappingRow> stateRows = new ArrayList<>();
        for (LocationMappingRow row : live) {
            Integer level = lgdLevelById.get(row.locationId());
            if (Objects.equals(level, HierarchyReconciler.LGD_VILLAGE)) {
                villageRows.add(row);
                heldVillages.add(row.locationId());
            } else if (Objects.equals(level, 1)) {
                stateRows.add(row);
            }
        }

        for (Integer villageId : desired) {
            if (!heldVillages.contains(villageId)) {
                repository.insertLgdMapping(schema, schemeId, villageId, LGD_VILLAGE_LEVEL, actor);
                live.add(new LocationMappingRow(0, schemeId, villageId, LGD_VILLAGE_LEVEL));
                touchedSchemes.add(schemeId);
                report.count("scheme_lgd_mappings.inserted");
            }
        }
        if (!desired.isEmpty()) {
            for (LocationMappingRow row : stateRows) {
                retireLgd(row, live, report, "scheme_lgd_mappings.state_placeholder_retired");
            }
        }

        boolean complete = unresolved.isEmpty();
        if (up.villageCodes().isEmpty() && !villageRows.isEmpty() && !properties.isRetireOnEmptyList()) {
            report.issue("SCHEME", up.code(), "EMPTY_VILLAGE_LIST_KEPT", Map.of("schemeId", schemeId, "held", villageRows.size()));
        } else if (complete) {
            for (LocationMappingRow row : villageRows) {
                if (!desired.contains(row.locationId())) {
                    retireLgd(row, live, report, "scheme_lgd_mappings.retired");
                }
            }
        }

        // A new scheme with no resolvable village still needs one location — the state node — or it has
        // no dim_scheme row. It is retired above as soon as a real village arrives.
        if (isNew && live.isEmpty() && stateLgdNodeId != null) {
            repository.insertLgdMapping(schema, schemeId, stateLgdNodeId, LGD_STATE_LEVEL, actor);
            live.add(new LocationMappingRow(0, schemeId, stateLgdNodeId, LGD_STATE_LEVEL));
            report.count("scheme_lgd_mappings.state_placeholder_inserted");
        }
    }

    private void retireLgd(LocationMappingRow row, List<LocationMappingRow> live, SyncReport report, String counter) {
        if (row.id() > 0) {
            repository.retireLgdMapping(schema, row.id(), actor);
        }
        live.remove(row);
        touchedSchemes.add(row.schemeId());
        report.count(counter);
    }

    // ── sub-divisions ───────────────────────────────────────────────────────

    private void reconcileSubdivisions(int schemeId, UpstreamScheme up, SyncReport report) {
        Set<Integer> desired = new LinkedHashSet<>();
        List<String> unresolved = new ArrayList<>();
        for (String code : up.subdivisionCodes()) {
            Integer id = deptIdsByCode.get(code);
            if (id != null && Objects.equals(deptLevelById.get(id), HierarchyReconciler.DEPT_SUB_DIVISION)) {
                desired.add(id);
            } else {
                unresolved.add(code);
            }
        }
        if (!unresolved.isEmpty()) {
            report.issue("SCHEME", up.code(), "SUBDIVISION_UNRESOLVED",
                    Map.of("schemeId", schemeId, "subdivisionCodes", unresolved));
        }
        List<LocationMappingRow> live = deptMappingsByScheme.computeIfAbsent(schemeId, k -> new ArrayList<>());
        List<LocationMappingRow> subdivisionRows = live.stream()
                .filter(r -> Objects.equals(deptLevelById.get(r.locationId()), HierarchyReconciler.DEPT_SUB_DIVISION))
                .toList();
        Set<Integer> held = new HashSet<>();
        subdivisionRows.forEach(r -> held.add(r.locationId()));

        for (Integer id : desired) {
            if (!held.contains(id)) {
                repository.insertDepartmentMapping(schema, schemeId, id, DEPT_SUB_DIVISION_LEVEL, actor);
                live.add(new LocationMappingRow(0, schemeId, id, DEPT_SUB_DIVISION_LEVEL));
                touchedSchemes.add(schemeId);
                report.count("scheme_department_mappings.inserted");
            }
        }
        if (up.subdivisionCodes().isEmpty() && !subdivisionRows.isEmpty() && !properties.isRetireOnEmptyList()) {
            report.issue("SCHEME", up.code(), "EMPTY_SUBDIVISION_LIST_KEPT",
                    Map.of("schemeId", schemeId, "held", subdivisionRows.size()));
        } else if (unresolved.isEmpty()) {
            for (LocationMappingRow row : subdivisionRows) {
                if (!desired.contains(row.locationId())) {
                    if (row.id() > 0) {
                        repository.retireDepartmentMapping(schema, row.id(), actor);
                    }
                    live.remove(row);
                    touchedSchemes.add(schemeId);
                    report.count("scheme_department_mappings.retired");
                }
            }
        }
    }

    // ── officers ────────────────────────────────────────────────────────────

    private void reconcileOfficers(int schemeId, UpstreamScheme up, SyncReport report) {
        Set<Integer> desired = new LinkedHashSet<>();
        Set<String> incompleteTypes = new HashSet<>();
        boolean anyManaged = false;
        for (UpstreamPerson person : up.officers()) {
            Optional<String> type = StateVocabulary.userType(person.role());
            if (type.isEmpty()) {
                continue;
            }
            anyManaged = true;
            Optional<UserRow> user = users.resolve(person, report);
            if (user.isEmpty()) {
                incompleteTypes.add(type.get());
            } else if (user.get().status() == 1) {
                desired.add(user.get().id());
            } else {
                report.count("user_scheme_mappings.skipped_inactive_user");
            }
        }
        for (Integer userId : desired) {
            userMappings.ensure(userId, schemeId, report);
        }

        List<Integer> heldManaged = new ArrayList<>();
        for (Integer userId : userMappings.liveUsers(schemeId)) {
            users.byId(userId)
                    .filter(u -> u.userTypeName() != null && StateVocabulary.MANAGED_USER_TYPES.contains(u.userTypeName()))
                    .ifPresent(u -> heldManaged.add(u.id()));
        }
        if (!anyManaged && !heldManaged.isEmpty() && !properties.isRetireOnEmptyList()) {
            report.issue("SCHEME", up.code(), "EMPTY_OFFICER_LIST_KEPT", Map.of("schemeId", schemeId, "held", heldManaged.size()));
            return;
        }
        for (Integer userId : heldManaged) {
            if (desired.contains(userId)) {
                continue;
            }
            String type = users.byId(userId).map(UserRow::userTypeName).orElse(null);
            if (incompleteTypes.contains(type)) {
                report.count("user_scheme_mappings.kept_role_incomplete");
                continue;
            }
            userMappings.retire(userId, schemeId, report);
        }
    }

    // ── lenient-ingestion placeholders ──────────────────────────────────────

    /** A placeholder whose readings now belong to a real scheme. */
    public record Reassignment(int fromSchemeId, int toSchemeId) {
    }

    /**
     * LENIENT-INGEST follow-up. A placeholder carrying this scheme's IMIS id holds readings telemetry
     * could not attribute when they arrived; telemetry would attribute them to this scheme now. With
     * {@code move-placeholder-readings} on, its readings and anomalies are re-pointed here, the
     * placeholder is soft-deleted, and the move is announced to analytics after commit. Off, it is only
     * reported. An issue is raised either way, as an audit trail.
     */
    private void supersedePlaceholder(int schemeId, UpstreamScheme up, SyncReport report) {
        List<Integer> placeholders = blank(up.centreSchemeId()) ? null : placeholdersByCentre.remove(up.centreSchemeId());
        if (placeholders == null) {
            return;
        }
        for (Integer placeholder : placeholders) {
            if (!properties.isMovePlaceholderReadings()) {
                report.issue("SCHEME", up.code(), "PLACEHOLDER_SUPERSEDED",
                        Map.of("schemeId", schemeId, "placeholderSchemeId", placeholder, "moved", false));
                continue;
            }
            StateSyncTenantRepository.MovedActivity moved = repository.moveSchemeActivity(schema, placeholder, schemeId, actor);
            repository.retirePlaceholderScheme(schema, placeholder, actor);
            reassignments.add(new Reassignment(placeholder, schemeId));
            touchedSchemes.add(schemeId);
            report.add("placeholders.readings_moved", moved.readings());
            report.add("placeholders.anomalies_moved", moved.anomalies());
            report.count("placeholders.retired");
            report.issue("SCHEME", up.code(), "PLACEHOLDER_SUPERSEDED", Map.of("schemeId", schemeId,
                    "placeholderSchemeId", placeholder, "moved", true, "readings", moved.readings(),
                    "anomalies", moved.anomalies()));
        }
    }

    public List<Reassignment> reassignments() {
        return List.copyOf(reassignments);
    }

    // ── archive / absence ───────────────────────────────────────────────────

    /**
     * Archived upstream schemes lose their officers (so nudges and escalations stop), but the scheme
     * row, its locations and its readings stay. A scheme still reporting readings is spared and
     * flagged instead — data is arriving, so the archive is what needs a second look.
     */
    public void applyArchived(List<String> archivedCodes, SyncReport report) {
        for (String code : archivedCodes) {
            Integer schemeId = idByCode.get(code);
            if (schemeId == null) {
                report.count("archived.not_ours");
                continue;
            }
            if (repository.hasReadingSince(schema, schemeId, properties.getArchiveSpareReadingDays())) {
                report.issue("SCHEME", code, "ARCHIVED_BUT_REPORTING",
                        Map.of("schemeId", schemeId, "windowDays", properties.getArchiveSpareReadingDays()));
                continue;
            }
            int before = report.get("user_scheme_mappings.retired");
            for (Integer userId : userMappings.liveUsers(schemeId)) {
                users.byId(userId)
                        .filter(u -> u.userTypeName() != null && StateVocabulary.MANAGED_USER_TYPES.contains(u.userTypeName()))
                        .ifPresent(u -> userMappings.retire(u.id(), schemeId, report));
            }
            report.count(report.get("user_scheme_mappings.retired") > before ? "archived.officers_retired" : "archived.already_clear");
        }
    }

    /** After a full crawl: coded schemes the upstream neither listed nor archived. Reported only. */
    public void reportAbsent(Set<String> listedCodes, Set<String> archivedCodes, SyncReport report) {
        for (Map.Entry<String, Integer> entry : idByCode.entrySet()) {
            if (!listedCodes.contains(entry.getKey()) && !archivedCodes.contains(entry.getKey())) {
                report.issue("SCHEME", entry.getKey(), "UPSTREAM_SCHEME_MISSING", Map.of("schemeId", entry.getValue()));
            }
        }
    }

    public Set<Integer> touchedSchemes() {
        return Set.copyOf(touchedSchemes);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static String pick(String upstream, String ours) {
        return blank(upstream) ? ours : upstream.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }

    private static Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
