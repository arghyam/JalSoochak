package org.arghyam.jalsoochak.scheme.statesync.reconcile;

import org.arghyam.jalsoochak.scheme.statesync.reconcile.StateSyncTenantRepository.UserMappingRow;
import org.arghyam.jalsoochak.scheme.statesync.run.SyncReport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code user_scheme_mapping_table} for one run, live and retired rows alike, so that:
 * <ul>
 *   <li>a wanted pair that only exists retired is <b>revived</b> (earliest row) rather than duplicated;</li>
 *   <li>a pair already live is left alone — a second run writes nothing;</li>
 *   <li>every user whose mappings moved is remembered, for the analytics replace event.</li>
 * </ul>
 */
public class UserSchemeMappings {

    private final StateSyncTenantRepository repository;
    private final String schema;
    private final int actor;

    /** "user:scheme" → rows in id order. */
    private final Map<String, List<UserMappingRow>> byPair = new HashMap<>();
    private final Map<Integer, Set<Integer>> liveUsersByScheme = new HashMap<>();
    private final Map<Integer, Set<Integer>> liveSchemesByUser = new HashMap<>();
    private final Set<Integer> touchedUsers = new HashSet<>();

    public UserSchemeMappings(StateSyncTenantRepository repository, String schema, int actor) {
        this.repository = repository;
        this.schema = schema;
        this.actor = actor;
        for (UserMappingRow row : repository.allUserSchemeMappings(schema)) {
            byPair.computeIfAbsent(key(row.userId(), row.schemeId()), k -> new ArrayList<>()).add(row);
            if (isLive(row)) {
                link(row.userId(), row.schemeId());
            }
        }
    }

    public Set<Integer> liveUsers(int schemeId) {
        return Set.copyOf(liveUsersByScheme.getOrDefault(schemeId, Set.of()));
    }

    public Set<Integer> liveSchemes(int userId) {
        return new TreeSet<>(liveSchemesByUser.getOrDefault(userId, Set.of()));
    }

    /** Makes the pair live: no-op when it already is, revive when only retired rows exist, insert otherwise. */
    public void ensure(int userId, int schemeId, SyncReport report) {
        List<UserMappingRow> rows = byPair.computeIfAbsent(key(userId, schemeId), k -> new ArrayList<>());
        if (rows.stream().anyMatch(UserSchemeMappings::isLive)) {
            return;
        }
        if (!rows.isEmpty()) {
            UserMappingRow earliest = rows.get(0);
            repository.reviveUserSchemeMapping(schema, earliest.id(), actor);
            rows.set(0, new UserMappingRow(earliest.id(), userId, schemeId, 1, false));
            report.count("user_scheme_mappings.revived");
        } else {
            int id = repository.insertUserSchemeMapping(schema, userId, schemeId, actor);
            rows.add(new UserMappingRow(id, userId, schemeId, 1, false));
            report.count("user_scheme_mappings.inserted");
        }
        link(userId, schemeId);
        touchedUsers.add(userId);
    }

    /** Retires every live row of the pair. */
    public void retire(int userId, int schemeId, SyncReport report) {
        List<UserMappingRow> rows = byPair.getOrDefault(key(userId, schemeId), List.of());
        boolean any = false;
        for (int i = 0; i < rows.size(); i++) {
            UserMappingRow row = rows.get(i);
            if (isLive(row) || (!row.deleted() && row.status() != 1)) {
                repository.retireUserSchemeMapping(schema, row.id(), actor);
                rows.set(i, new UserMappingRow(row.id(), userId, schemeId, 0, true));
                any |= isLive(row);
            }
        }
        if (any) {
            report.count("user_scheme_mappings.retired");
            touchedUsers.add(userId);
        }
        liveUsersByScheme.getOrDefault(schemeId, new HashSet<>()).remove(userId);
        liveSchemesByUser.getOrDefault(userId, new HashSet<>()).remove(schemeId);
    }

    public void retireAllForUser(int userId, SyncReport report) {
        for (Integer schemeId : liveSchemes(userId)) {
            retire(userId, schemeId, report);
        }
    }

    public Set<Integer> touchedUsers() {
        return Set.copyOf(touchedUsers);
    }

    private void link(int userId, int schemeId) {
        liveUsersByScheme.computeIfAbsent(schemeId, k -> new HashSet<>()).add(userId);
        liveSchemesByUser.computeIfAbsent(userId, k -> new HashSet<>()).add(schemeId);
    }

    /** Every read path demands both guards; a row failing either is not live. */
    private static boolean isLive(UserMappingRow row) {
        return !row.deleted() && row.status() == 1;
    }

    private static String key(int userId, int schemeId) {
        return userId + ":" + schemeId;
    }
}
