package org.arghyam.jalsoochak.scheme.statesync.reconcile;

import org.arghyam.jalsoochak.scheme.service.PiiEncryptionService;
import org.arghyam.jalsoochak.scheme.statesync.model.UpstreamPerson;
import org.arghyam.jalsoochak.scheme.statesync.reconcile.StateSyncTenantRepository.UserRow;
import org.arghyam.jalsoochak.scheme.statesync.run.SyncReport;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The tenant's live users, indexed for one run, and the single place an upstream person becomes one
 * of our users — whether they arrive from the user master or embedded in a scheme.
 *
 * <p><b>Identity</b>: the upstream code ({@code state_user_id}) first, then the HMAC of the normalised
 * phone, which is how every other path in the platform identifies a person.
 *
 * <p><b>What changes on a matched user</b>: the name; {@code state_user_id} when we hold none; and the
 * role, except that an administrative account is never re-typed and a promotion into
 * {@code EXECUTIVE_ENGINEER} is withheld for a human to approve (both raise an issue). Phone, email,
 * password and status are never touched here — status only moves through the blocked list.
 *
 * <p><b>A new user</b> is inserted only for an allow-listed role with a valid Indian mobile and a name.
 */
public class UserDirectory {

    static final Set<String> PROTECTED_TYPES = Set.of("SUPER_USER", "STATE_ADMIN", "SUPER_STATE_ADMIN", "SUPPORT_ADMIN");
    static final String GATED_PROMOTION_TYPE = "EXECUTIVE_ENGINEER";

    private final StateSyncTenantRepository repository;
    private final PiiEncryptionService pii;
    private final String schema;
    private final int tenantId;
    private final int actor;
    private final Map<String, Integer> userTypeIds;

    private final Map<Integer, UserRow> byId = new HashMap<>();
    private final Map<String, Integer> idByStateCode = new HashMap<>();
    private final Map<String, List<Integer>> idsByPhoneHash = new HashMap<>();
    private final Set<String> reportedMissingTypes = new HashSet<>();
    private final Set<String> reportedUnknownRoles = new HashSet<>();
    /** Off for runs that see only part of a person's listings (hourly, refresh): a lower role waits for the nightly run. */
    private boolean allowRoleDowngrades = true;
    /** upstream code → outcome, so a person listed on 40 schemes is resolved (and reported) once. */
    private final Map<String, Optional<UserRow>> resolved = new HashMap<>();
    private final Map<Integer, String> typeAssignedThisRun = new HashMap<>();

    private final Set<Integer> created = new HashSet<>();
    private final Set<Integer> updated = new HashSet<>();

    public UserDirectory(StateSyncTenantRepository repository, PiiEncryptionService pii, String schema,
                         int tenantId, int actor) {
        this.repository = repository;
        this.pii = pii;
        this.schema = schema;
        this.tenantId = tenantId;
        this.actor = actor;
        this.userTypeIds = repository.userTypeIds();
        repository.liveUsers(schema).forEach(this::index);
    }

    /**
     * Matches, updates or creates the user for an upstream person.
     *
     * @return empty when the person is outside the allow-list or cannot be written; the reason is in
     *         the report (a role outside the allow-list is only counted, there are thousands)
     */
    public Optional<UserRow> resolve(UpstreamPerson person, SyncReport report) {
        if (person.code() == null) {
            report.issue("USER", null, "INVALID_UPSTREAM_ROW", Map.of("reason", "no code"));
            return Optional.empty();
        }
        Optional<UserRow> earlier = resolved.get(person.code());
        if (earlier == null) {
            Optional<UserRow> result = doResolve(person, report);
            resolved.put(person.code(), result);
            return result;
        }
        if (earlier.isEmpty()) {
            return earlier;
        }
        // Listed again (another scheme, perhaps another role): only the role can differ.
        UserRow current = byId.getOrDefault(earlier.get().id(), earlier.get());
        Optional<String> type = StateVocabulary.userType(person.role());
        Integer typeId = type.map(userTypeIds::get).orElse(null);
        if (typeId == null || type.get().equals(current.userTypeName())) {
            return Optional.of(current);
        }
        return Optional.of(applyDiff(current, person, type.get(), typeId, report));
    }

    /**
     * Whether a role may move down. Only a run that reads every listing (the nightly full run) knows a
     * person's highest role, so the others only ever raise one.
     */
    public void allowRoleDowngrades(boolean allow) {
        this.allowRoleDowngrades = allow;
    }

    /** Counts a role we do not onboard, and reports once per run a value outside the upstream's role set. */
    public void noteRoleNotIngested(String role, SyncReport report) {
        if (!StateVocabulary.isKnownRole(role) && role != null && !role.isBlank() && reportedUnknownRoles.add(role)) {
            report.issue("USER", null, "UNKNOWN_ROLE", Map.of("role", role));
        }
        report.count("users.role_not_ingested");
    }

    private Optional<UserRow> doResolve(UpstreamPerson person, SyncReport report) {
        Optional<String> type = StateVocabulary.userType(person.role());
        if (type.isEmpty()) {
            noteRoleNotIngested(person.role(), report);
            return Optional.empty();
        }
        Integer typeId = userTypeIds.get(type.get());
        if (typeId == null) {
            if (reportedMissingTypes.add(type.get())) {
                report.issue("USER", null, "USER_TYPE_MISSING", Map.of("userType", type.get()));
            }
            report.count("users.user_type_missing");
            return Optional.empty();
        }

        UserRow match = null;
        Integer byCode = idByStateCode.get(person.code());
        if (byCode != null) {
            match = byId.get(byCode);
        }
        Optional<String> phone = StateVocabulary.phone(person.phone());
        String phoneHash = phone.map(pii::hmac).orElse(null);
        if (match == null && phoneHash != null) {
            List<Integer> samePhone = idsByPhoneHash.getOrDefault(phoneHash, List.of());
            if (samePhone.size() > 1) {
                report.issue("USER", person.code(), "PHONE_SHARED_BY_SEVERAL_USERS", Map.of("userIds", samePhone));
                return Optional.empty();
            }
            if (samePhone.size() == 1) {
                match = byId.get(samePhone.get(0));
            }
        }

        if (match == null) {
            if (phone.isEmpty()) {
                report.issue("USER", person.code(), "INVALID_PHONE", Map.of("role", type.get()));
                return Optional.empty();
            }
            if (person.name() == null || person.name().isBlank()) {
                report.issue("USER", person.code(), "BLANK_NAME", Map.of());
                return Optional.empty();
            }
            UserRow inserted = repository.insertUser(schema, tenantId, pii.encrypt(person.name()),
                    pii.titleHash(person.name()), typeId, type.get(), pii.encrypt(phone.get()), phoneHash,
                    person.code(), actor);
            index(inserted);
            typeAssignedThisRun.put(inserted.id(), type.get());
            created.add(inserted.id());
            report.count("users.inserted");
            return Optional.of(inserted);
        }
        report.count("users.matched");
        return Optional.of(applyDiff(match, person, type.get(), typeId, report));
    }

    private UserRow applyDiff(UserRow user, UpstreamPerson person, String type, int typeId, SyncReport report) {
        String titleEncrypted = user.titleEncrypted();
        String titleHash = null;
        boolean changed = false;

        if (person.name() != null && !person.name().isBlank()) {
            String current = pii.safeDecrypt(user.titleEncrypted());
            if (!StateVocabulary.nameKey(current).equals(StateVocabulary.nameKey(person.name()))) {
                titleEncrypted = pii.encrypt(person.name());
                titleHash = pii.titleHash(person.name());
                changed = true;
                report.count("users.name_updated");
            }
        }

        String stateUserId = user.stateUserId();
        if (stateUserId == null) {
            stateUserId = person.code();
            changed = true;
            report.count("users.code_stamped");
        } else if (!stateUserId.equals(person.code())) {
            report.issue("USER", person.code(), "PHONE_MATCHES_USER_WITH_OTHER_CODE",
                    Map.of("userId", user.id(), "theirCode", stateUserId));
        }

        int newTypeId = user.userTypeId();
        String newType = user.userTypeName();
        if (!type.equals(user.userTypeName())) {
            // One person can hold several upstream roles (an in-charge officer); they keep the highest.
            boolean lower = StateVocabulary.roleRank(type) <= StateVocabulary.roleRank(user.userTypeName());
            String earlier = typeAssignedThisRun.get(user.id());
            if (user.userTypeName() != null && PROTECTED_TYPES.contains(user.userTypeName())) {
                report.issue("USER", person.code(), "ROLE_CHANGE_ON_ADMIN_WITHHELD",
                        Map.of("userId", user.id(), "current", user.userTypeName(), "upstream", type));
            } else if (lower && earlier != null) {
                report.count("users.role_kept_higher");
            } else if (lower && !allowRoleDowngrades) {
                report.count("users.role_downgrade_deferred");
            } else if (GATED_PROMOTION_TYPE.equals(type) && !created.contains(user.id())) {
                report.issue("USER", person.code(), "PROMOTION_WITHHELD",
                        Map.of("userId", user.id(), "current", String.valueOf(user.userTypeName()), "upstream", type));
            } else {
                newTypeId = typeId;
                newType = type;
                changed = true;
                report.count("users.role_updated");
            }
        }
        typeAssignedThisRun.put(user.id(), newType);

        if (!changed) {
            return user;
        }
        if (titleHash == null) {
            titleHash = pii.titleHash(pii.safeDecrypt(user.titleEncrypted()));
        }
        repository.updateUser(schema, user.id(), titleEncrypted, titleHash, newTypeId, stateUserId, actor);
        UserRow after = new UserRow(user.id(), user.uuid(), newTypeId, newType, titleEncrypted, user.phoneHash(),
                stateUserId, user.status());
        index(after);
        if (!created.contains(user.id())) {
            updated.add(user.id());
        }
        return after;
    }

    /** Sets status 0 on the user holding this upstream code. Administrative accounts are never blocked here. */
    public Optional<UserRow> block(String code, SyncReport report) {
        Integer id = idByStateCode.get(code);
        if (id == null) {
            report.count("users.blocked_not_ours");
            return Optional.empty();
        }
        UserRow user = byId.get(id);
        if (user.userTypeName() != null && PROTECTED_TYPES.contains(user.userTypeName())) {
            report.issue("USER", code, "BLOCK_ON_ADMIN_WITHHELD", Map.of("userId", id));
            return Optional.empty();
        }
        if (user.status() != 0) {
            repository.deactivateUser(schema, id, actor);
            user = new UserRow(user.id(), user.uuid(), user.userTypeId(), user.userTypeName(), user.titleEncrypted(),
                    user.phoneHash(), user.stateUserId(), 0);
            index(user);
            updated.add(id);
            report.count("users.blocked");
        }
        return Optional.of(user);
    }

    public Optional<UserRow> byId(int id) {
        return Optional.ofNullable(byId.get(id));
    }

    public Set<Integer> createdIds() {
        return Set.copyOf(created);
    }

    public Set<Integer> updatedIds() {
        return Set.copyOf(updated);
    }

    public List<UserRow> users(Set<Integer> ids) {
        List<UserRow> rows = new ArrayList<>();
        ids.forEach(id -> {
            UserRow row = byId.get(id);
            if (row != null) {
                rows.add(row);
            }
        });
        return rows;
    }

    private void index(UserRow row) {
        UserRow previous = byId.put(row.id(), row);
        if (previous != null && previous.stateUserId() != null) {
            idByStateCode.remove(previous.stateUserId(), previous.id());
        }
        if (row.stateUserId() != null) {
            idByStateCode.put(row.stateUserId(), row.id());
        }
        if (previous == null && row.phoneHash() != null) {
            idsByPhoneHash.computeIfAbsent(row.phoneHash(), k -> new ArrayList<>()).add(row.id());
        }
    }
}
