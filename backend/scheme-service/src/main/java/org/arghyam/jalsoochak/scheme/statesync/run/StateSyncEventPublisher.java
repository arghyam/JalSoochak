package org.arghyam.jalsoochak.scheme.statesync.run;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.scheme.kafka.KafkaProducer;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.HierarchyReconciler;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.SchemeReconciler.Reassignment;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.StateSyncTenantRepository;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.StateSyncTenantRepository.LocationMappingRow;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.StateSyncTenantRepository.LocationNode;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.StateSyncTenantRepository.SchemeDimensionAttributes;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Publishes the analytics events for what a run changed. Called only after the run's transaction has
 * committed — never for a DRY_RUN — so analytics never sees a row that was rolled back.
 *
 * <p>Schemes go out as {@code SCHEME_DIMENSION_REPLACED}: the scheme's attributes plus one row per
 * village × sub-division it is mapped to (one under its state placeholder when it has no village),
 * each with its ancestor ids by level. Analytics replaces the scheme's whole {@code dim_scheme_table}
 * set with it, which {@code SCHEME_UPDATED} — one parent location copied into every level — cannot.
 *
 * <p>{@code SCHEME_READINGS_REASSIGNED} follows, one per placeholder whose readings moved, after the
 * real scheme's dim rows so analytics never holds facts for a scheme with no dim row. Department
 * events on {@code scheme-service-topic}, user events on {@code user-service-topic}, as elsewhere.
 */
@Component
@Slf4j
public class StateSyncEventPublisher {

    static final String SCHEME_TOPIC = "scheme-service-topic";
    static final String USER_TOPIC = "user-service-topic";
    private static final int LEVELS = 6;

    /** Everything a run wants announced, gathered while it reconciles. */
    public record PendingEvents(List<Integer> schemeIds, List<Map<String, Object>> userEvents,
                                List<Map<String, Object>> departmentEvents, List<Reassignment> reassignments) {

        public static PendingEvents empty() {
            return new PendingEvents(List.of(), List.of(), List.of(), List.of());
        }
    }

    private final KafkaProducer kafkaProducer;
    private final StateSyncTenantRepository tenantRepository;

    public StateSyncEventPublisher(KafkaProducer kafkaProducer, StateSyncTenantRepository tenantRepository) {
        this.kafkaProducer = kafkaProducer;
        this.tenantRepository = tenantRepository;
    }

    /** @return how many events failed to publish (the next full run sends every scheme again) */
    public int publish(String schema, int tenantId, PendingEvents events) {
        int failures = 0;
        for (Map<String, Object> event : events.departmentEvents()) {
            failures += send(SCHEME_TOPIC, event);
        }
        for (Map<String, Object> event : events.userEvents()) {
            failures += send(USER_TOPIC, event);
        }
        for (Map<String, Object> event : schemeDimensionEvents(schema, tenantId, events.schemeIds())) {
            failures += send(SCHEME_TOPIC, event);
        }
        for (Reassignment move : events.reassignments()) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventType", "SCHEME_READINGS_REASSIGNED");
            event.put("tenantId", tenantId);
            event.put("fromSchemeId", move.fromSchemeId());
            event.put("toSchemeId", move.toSchemeId());
            failures += send(SCHEME_TOPIC, event);
        }
        if (failures > 0) {
            log.warn("[state-sync] {} analytics event(s) failed to publish for tenant {}", failures, tenantId);
        }
        return failures;
    }

    /** One SCHEME_DIMENSION_REPLACED per scheme, built from the tenant DB as committed. */
    List<Map<String, Object>> schemeDimensionEvents(String schema, int tenantId, List<Integer> schemeIds) {
        if (schemeIds.isEmpty()) {
            return List.of();
        }
        Set<Integer> wanted = new HashSet<>(schemeIds);
        Map<Integer, LocationNode> lgd = byId(tenantRepository.lgdNodes(schema));
        Map<Integer, LocationNode> dept = byId(tenantRepository.departmentNodes(schema));
        Map<Integer, List<Integer>> lgdByScheme = locationsByScheme(tenantRepository.liveLgdMappings(schema), wanted);
        Map<Integer, List<Integer>> deptByScheme = locationsByScheme(tenantRepository.liveDepartmentMappings(schema), wanted);

        List<Map<String, Object>> events = new ArrayList<>();
        for (int from = 0; from < schemeIds.size(); from += 500) {
            List<Integer> chunk = schemeIds.subList(from, Math.min(schemeIds.size(), from + 500));
            for (SchemeDimensionAttributes s : tenantRepository.schemeDimensionAttributes(schema, chunk)) {
                List<Map<String, Object>> rows = new ArrayList<>();
                List<Integer> departments = deptByScheme.getOrDefault(s.schemeId(), List.of());
                for (Integer lgdId : lgdByScheme.getOrDefault(s.schemeId(), List.of())) {
                    for (Integer deptId : departments.isEmpty() ? java.util.Collections.<Integer>singletonList(null) : departments) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("parentLgdLocationId", lgdId);
                        row.put("lgdLevels", levels(lgdId, lgd));
                        row.put("parentDepartmentLocationId", deptId);
                        row.put("deptLevels", deptId == null ? null : levels(deptId, dept));
                        rows.add(row);
                    }
                }
                Map<String, Object> event = new LinkedHashMap<>();
                event.put("eventType", "SCHEME_DIMENSION_REPLACED");
                event.put("tenantId", tenantId);
                event.put("schemeId", s.schemeId());
                event.put("schemeName", s.name());
                event.put("stateSchemeId", safeParseInt(s.stateSchemeId()));
                event.put("centreSchemeId", safeParseInt(s.centreSchemeId()));
                event.put("latitude", s.latitude());
                event.put("longitude", s.longitude());
                event.put("operatingStatus", s.operatingStatus());
                event.put("workStatus", s.workStatus());
                event.put("fhtcCount", s.fhtcCount());
                event.put("plannedFhtc", s.plannedFhtc());
                event.put("houseHoldCount", s.houseHoldCount());
                event.put("rows", rows);
                events.add(event);
            }
        }
        return events;
    }

    /** Ancestor id at levels 1..6 (index 0 = level 1), {@code null} where the tree has no node. */
    private static List<Integer> levels(int id, Map<Integer, LocationNode> nodes) {
        Map<Integer, Integer> byLevel = HierarchyReconciler.ancestorsByLevel(id, nodes);
        List<Integer> levels = new ArrayList<>(LEVELS);
        for (int level = 1; level <= LEVELS; level++) {
            levels.add(byLevel.get(level));
        }
        return levels;
    }

    private static Map<Integer, LocationNode> byId(List<LocationNode> nodes) {
        Map<Integer, LocationNode> map = new HashMap<>();
        nodes.forEach(n -> map.put(n.id(), n));
        return map;
    }

    private static Map<Integer, List<Integer>> locationsByScheme(List<LocationMappingRow> rows, Set<Integer> wanted) {
        Map<Integer, List<Integer>> map = new HashMap<>();
        for (LocationMappingRow row : rows) {
            if (wanted.contains(row.schemeId())) {
                List<Integer> ids = map.computeIfAbsent(row.schemeId(), k -> new ArrayList<>());
                if (!ids.contains(row.locationId())) {
                    ids.add(row.locationId());
                }
            }
        }
        return map;
    }

    private int send(String topic, Map<String, Object> event) {
        return kafkaProducer.publishJson(topic, event) ? 0 : 1;
    }

    static Integer safeParseInt(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
