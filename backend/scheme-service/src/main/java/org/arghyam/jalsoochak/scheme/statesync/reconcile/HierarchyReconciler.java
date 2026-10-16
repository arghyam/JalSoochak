package org.arghyam.jalsoochak.scheme.statesync.reconcile;

import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamNode;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.StateSyncTenantRepository.LocationNode;
import org.arghyam.jalsoochak.scheme.statesync.run.SyncReport;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reconciles the two upstream location trees into ours and stamps each matched node with its
 * upstream code, so every later run (and the scheme reconcile) matches on the code alone.
 *
 * <p><b>Departmental tree</b> (zone 2 → circle 3 → division 4 → sub-division 5) — the upstream is the
 * master: an unmatched node is inserted, and a node whose parent differs is re-parented. Matched
 * titles are left alone, because scheme uploads still resolve departments by title.
 *
 * <p><b>LGD tree</b> (district 2 → block 3 → panchayat 4 → village 5) — match only. Our tree carries
 * the national LGD code ({@code lgd_code}, NOT NULL), which the upstream does not publish, so an
 * upstream node we cannot match is reported, never invented. A parent disagreement is reported too:
 * the national directory, not the state system, owns that shape.
 *
 * <p>Matching, per level, first hit wins: our {@code state_*_id} equals the code; else exactly one
 * not-yet-coded node with the same name (level suffixes such as "Division" ignored) under the same
 * parent; else — departments and districts only — exactly one such node by name alone. Two
 * candidates is an issue, never a guess.
 */
@Component
public class HierarchyReconciler {

    public static final int DEPT_ZONE = 2;
    public static final int DEPT_CIRCLE = 3;
    public static final int DEPT_DIVISION = 4;
    public static final int DEPT_SUB_DIVISION = 5;
    public static final int LGD_DISTRICT = 2;
    public static final int LGD_BLOCK = 3;
    public static final int LGD_PANCHAYAT = 4;
    public static final int LGD_VILLAGE = 5;

    private static final Map<Integer, List<String>> DEPT_SUFFIXES = Map.of(
            DEPT_ZONE, List.of("zone"),
            DEPT_CIRCLE, List.of("circle"),
            DEPT_DIVISION, List.of("division", "div"),
            DEPT_SUB_DIVISION, List.of("sub division", "subdivision", "sub div"));
    private static final Map<Integer, List<String>> LGD_SUFFIXES = Map.of(
            LGD_DISTRICT, List.of("district"),
            LGD_BLOCK, List.of("block", "dev block"),
            LGD_PANCHAYAT, List.of("gram panchayat", "panchayat", "gp"),
            LGD_VILLAGE, List.of());

    /** The departmental tree as upstream publishes it, one list per level. */
    public record DepartmentTree(List<UpstreamNode> zones, List<UpstreamNode> circles,
                                 List<UpstreamNode> divisions, List<UpstreamNode> subdivisions) {
    }

    public record LgdTree(List<UpstreamNode> districts, List<UpstreamNode> blocks,
                          List<UpstreamNode> panchayats, List<UpstreamNode> villages) {
    }

    /**
     * Result of a department reconcile: upstream code → our id, plus every node created or
     * re-parented (with the tree as it now stands) for the analytics events.
     */
    public record DepartmentOutcome(Map<String, Integer> idsByCode, Set<Integer> touched,
                                    Map<Integer, LocationNode> nodesById) {
    }

    private final StateSyncTenantRepository repository;

    public HierarchyReconciler(StateSyncTenantRepository repository) {
        this.repository = repository;
    }

    public DepartmentOutcome reconcileDepartments(String schema, DepartmentTree tree, int actor, SyncReport report) {
        Map<Integer, Integer> configIds = repository.locationConfigIds(schema, StateSyncTenantRepository.REGION_DEPARTMENT);
        Index index = new Index(repository.departmentNodes(schema), DEPT_SUFFIXES);
        Integer root = index.singleAtLevel(1);
        Set<Integer> touched = new HashSet<>();

        Map<Integer, List<UpstreamNode>> levels = new LinkedHashMap<>();
        levels.put(DEPT_ZONE, tree.zones());
        levels.put(DEPT_CIRCLE, tree.circles());
        levels.put(DEPT_DIVISION, tree.divisions());
        levels.put(DEPT_SUB_DIVISION, tree.subdivisions());

        for (Map.Entry<Integer, List<UpstreamNode>> level : levels.entrySet()) {
            int lvl = level.getKey();
            for (UpstreamNode up : level.getValue()) {
                if (blank(up.code()) || blank(up.name())) {
                    report.issue("DEPARTMENT", up.code(), "INVALID_UPSTREAM_ROW", Map.of("level", lvl));
                    continue;
                }
                Integer parentId = lvl == DEPT_ZONE ? root : index.idsByCode.get(up.parentCode());
                if (lvl != DEPT_ZONE && parentId == null) {
                    report.issue("DEPARTMENT", up.code(), "PARENT_UNRESOLVED",
                            detail("level", lvl, "parentCode", up.parentCode()));
                    continue;
                }
                Match match = index.match(lvl, up, parentId, true);
                if (match.ambiguous) {
                    report.issue("DEPARTMENT", up.code(), "AMBIGUOUS_NAME",
                            detail("level", lvl, "name", up.name(), "candidates", match.candidates));
                    continue;
                }
                if (match.node == null) {
                    Integer configId = configIds.get(lvl);
                    if (configId == null) {
                        report.issue("DEPARTMENT", up.code(), "LEVEL_NOT_CONFIGURED", Map.of("level", lvl));
                        continue;
                    }
                    int id = repository.insertDepartment(schema, up.name().trim(), configId, parentId, up.code(), actor);
                    index.add(new LocationNode(id, up.name().trim(), lvl, parentId, up.code()));
                    touched.add(id);
                    report.count("department.inserted");
                    continue;
                }
                LocationNode node = match.node;
                report.count(match.byCode ? "department.matched_by_code" : "department.matched_by_name");
                if (node.stateCode() == null) {
                    repository.setDepartmentStateCode(schema, node.id(), up.code(), actor);
                    node = index.replace(node, new LocationNode(node.id(), node.title(), lvl, node.parentId(), up.code()));
                    report.count("department.code_stamped");
                }
                if (parentId != null && !Objects.equals(node.parentId(), parentId)) {
                    repository.setDepartmentParent(schema, node.id(), parentId, actor);
                    index.replace(node, new LocationNode(node.id(), node.title(), lvl, parentId, node.stateCode()));
                    touched.add(node.id());
                    report.count("department.reparented");
                }
            }
        }
        return new DepartmentOutcome(Map.copyOf(index.idsByCode), touched, Map.copyOf(index.byId));
    }

    /** @return upstream code → our LGD node id, for every node matched */
    public Map<String, Integer> reconcileLgd(String schema, LgdTree tree, int actor, SyncReport report) {
        Index index = new Index(repository.lgdNodes(schema), LGD_SUFFIXES);

        Map<Integer, List<UpstreamNode>> levels = new LinkedHashMap<>();
        levels.put(LGD_DISTRICT, tree.districts());
        levels.put(LGD_BLOCK, tree.blocks());
        levels.put(LGD_PANCHAYAT, tree.panchayats());
        levels.put(LGD_VILLAGE, tree.villages());

        for (Map.Entry<Integer, List<UpstreamNode>> level : levels.entrySet()) {
            int lvl = level.getKey();
            for (UpstreamNode up : level.getValue()) {
                if (blank(up.code()) || blank(up.name())) {
                    report.issue("LGD", up.code(), "INVALID_UPSTREAM_ROW", Map.of("level", lvl));
                    continue;
                }
                Integer parentId = null;
                if (lvl != LGD_DISTRICT) {
                    parentId = index.idsByCode.get(up.parentCode());
                    if (parentId == null) {
                        // The parent itself did not match; its own issue already explains why.
                        report.count("lgd.parent_unresolved");
                        continue;
                    }
                }
                Match match = index.match(lvl, up, parentId, lvl == LGD_DISTRICT);
                if (match.ambiguous) {
                    report.issue("LGD", up.code(), "AMBIGUOUS_NAME",
                            detail("level", lvl, "name", up.name(), "candidates", match.candidates));
                    continue;
                }
                if (match.node == null) {
                    report.issue("LGD", up.code(), "LGD_UNMATCHED",
                            detail("level", lvl, "name", up.name(), "parentCode", up.parentCode()));
                    continue;
                }
                LocationNode node = match.node;
                report.count(match.byCode ? "lgd.matched_by_code" : "lgd.matched_by_name");
                if (node.stateCode() == null) {
                    repository.setLgdStateCode(schema, node.id(), up.code(), actor);
                    index.replace(node, new LocationNode(node.id(), node.title(), lvl, node.parentId(), up.code()));
                    report.count("lgd.code_stamped");
                }
                if (parentId != null && !Objects.equals(node.parentId(), parentId)) {
                    report.issue("LGD", up.code(), "LGD_PARENT_MISMATCH",
                            detail("level", lvl, "ourParentId", node.parentId(), "upstreamParentCode", up.parentCode()));
                }
            }
        }
        return Map.copyOf(index.idsByCode);
    }

    /** Every ancestor's id by level (own level included), for the analytics level columns. */
    public static Map<Integer, Integer> ancestorsByLevel(int id, Map<Integer, LocationNode> nodesById) {
        Map<Integer, Integer> byLevel = new HashMap<>();
        Integer cursor = id;
        Set<Integer> seen = new HashSet<>();
        while (cursor != null && seen.add(cursor)) {
            LocationNode node = nodesById.get(cursor);
            if (node == null) {
                break;
            }
            byLevel.put(node.level(), node.id());
            cursor = node.parentId();
        }
        return byLevel;
    }

    // ── matching index ──────────────────────────────────────────────────────

    private record Match(LocationNode node, boolean byCode, boolean ambiguous, List<Integer> candidates) {
        static final Match NONE = new Match(null, false, false, List.of());
    }

    private static final class Index {
        final Map<Integer, LocationNode> byId = new HashMap<>();
        final Map<String, Integer> idsByCode = new HashMap<>();
        /** level|nameKey → ids of nodes that carry no upstream code yet (the only name-match candidates). */
        final Map<String, Set<Integer>> uncodedByName = new HashMap<>();
        final Map<Integer, List<String>> suffixes;

        Index(List<LocationNode> nodes, Map<Integer, List<String>> suffixes) {
            this.suffixes = suffixes;
            nodes.forEach(this::add);
        }

        void add(LocationNode node) {
            byId.put(node.id(), node);
            if (node.stateCode() != null) {
                idsByCode.put(node.stateCode(), node.id());
            } else {
                uncodedByName.computeIfAbsent(key(node.level(), node.title()), k -> new java.util.TreeSet<>())
                        .add(node.id());
            }
        }

        LocationNode replace(LocationNode old, LocationNode updated) {
            if (old.stateCode() == null) {
                Set<Integer> ids = uncodedByName.get(key(old.level(), old.title()));
                if (ids != null) {
                    ids.remove(old.id());
                }
            }
            byId.remove(old.id());
            add(updated);
            return updated;
        }

        Integer singleAtLevel(int level) {
            List<Integer> ids = byId.values().stream().filter(n -> n.level() == level).map(LocationNode::id).toList();
            return ids.size() == 1 ? ids.get(0) : null;
        }

        Match match(int level, UpstreamNode up, Integer parentId, boolean allowNameOnly) {
            Integer coded = idsByCode.get(up.code());
            if (coded != null) {
                LocationNode node = byId.get(coded);
                if (node != null && node.level() == level) {
                    return new Match(node, true, false, List.of());
                }
            }
            List<LocationNode> sameName = uncodedByName.getOrDefault(key(level, up.name()), Set.of()).stream()
                    .map(byId::get).filter(Objects::nonNull).toList();
            if (parentId != null) {
                List<LocationNode> underParent = sameName.stream()
                        .filter(n -> Objects.equals(n.parentId(), parentId)).toList();
                if (underParent.size() == 1) {
                    return new Match(underParent.get(0), false, false, List.of());
                }
                if (underParent.size() > 1) {
                    return new Match(null, false, true, ids(underParent));
                }
            }
            if (!allowNameOnly || sameName.isEmpty()) {
                return Match.NONE;
            }
            return sameName.size() == 1
                    ? new Match(sameName.get(0), false, false, List.of())
                    : new Match(null, false, true, ids(sameName));
        }

        private String key(int level, String name) {
            return level + "|" + StateVocabulary.nameKeyWithoutSuffix(name, suffixes.getOrDefault(level, List.of()));
        }

        private static List<Integer> ids(List<LocationNode> nodes) {
            List<Integer> ids = new ArrayList<>();
            nodes.forEach(n -> ids.add(n.id()));
            return ids;
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return map;
    }
}
