package org.arghyam.jalsoochak.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.config.KeycloakProvider;
import org.arghyam.jalsoochak.user.dto.response.AdminUserResponseDTO;
import org.arghyam.jalsoochak.user.enums.AdminUserStatus;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.records.AdminUserRow;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class KeycloakAdminHelper {

    private final KeycloakProvider keycloakProvider;
    private final UserCommonRepository userCommonRepository;
    private final MetadataDecryptionHelper metadataDecryptionHelper;

    /**
     * Short-TTL cache of brute-force lock status, keyed by Keycloak user id. Collapses the burst of
     * attack-detection admin probes that a password-spray against a single account would otherwise
     * trigger (one per failed login) down to roughly one probe per TTL per account. Per-instance and
     * purely advisory: a slightly stale entry only affects the friendly-message decision, never
     * security enforcement, which Keycloak still owns. Bounded by {@code lockoutProbeCacheMaxSize}.
     */
    private final ConcurrentHashMap<String, CachedLockStatus> lockStatusCache = new ConcurrentHashMap<>();

    private record CachedLockStatus(boolean locked, long expiresAtNanos) {}

    /** How long a brute-force status probe result is reused before re-querying Keycloak. */
    @Value("${security.login.lockout-probe-cache-ttl-seconds:15}")
    private long lockoutProbeCacheTtlSeconds;

    /** Upper bound on cached entries so a wide credential-stuffing spread cannot grow memory unbounded. */
    @Value("${security.login.lockout-probe-cache-max-size:500}")
    private int lockoutProbeCacheMaxSize;

    /**
     * Builds a full AdminUserResponseDTO by enriching an AdminUserRow with
     * firstName/lastName from Keycloak and resolving role/tenantCode names.
     */
    public AdminUserResponseDTO buildAdminUserResponse(AdminUserRow user) {
        String roleName = user.userTypeCName();
        String tenantCode = user.tenantId() != 0
                ? userCommonRepository.findTenantStateCodeById(user.tenantId()).orElse(null)
                : null;

        String firstName = null;
        String lastName = null;
        if (user.status() == AdminUserStatus.PENDING) {
            // No Keycloak account exists yet — read names from the invite token metadata
            var tokenOpt = userCommonRepository.findInviteTokenByEmail(user.email());
            if (tokenOpt.isPresent()) {
                firstName = metadataDecryptionHelper.parseAndDecrypt(tokenOpt.get().metadata(), "firstName");
                lastName = metadataDecryptionHelper.parseAndDecrypt(tokenOpt.get().metadata(), "lastName");
            }
        } else if (user.uuid() != null) {
            try {
                UserRepresentation rep = keycloakProvider.getAdminInstance()
                        .realm(keycloakProvider.getRealm())
                        .users().get(user.uuid()).toRepresentation();
                firstName = rep.getFirstName();
                lastName = rep.getLastName();
            } catch (Exception e) {
                log.warn("Could not fetch Keycloak profile for user {}: {}", user.id(), e.getMessage());
            }
        }

        return AdminUserResponseDTO.builder()
                .id(user.id())
                .email(user.email())
                .firstName(firstName)
                .lastName(lastName)
                .phoneNumber(user.phoneNumber())
                .role(roleName)
                .tenantCode(tenantCode)
                .status(user.status().name())
                .createdAt(user.createdAt())
                .build();
    }

    /**
     * Assigns a Keycloak realm role to a user by role name.
     */
    public void assignRoleToUser(String keycloakId, String roleName) {
        try {
            var realmResource = keycloakProvider.getAdminInstance().realm(keycloakProvider.getRealm());
            RoleRepresentation role = realmResource.roles().get(roleName).toRepresentation();
            realmResource.users().get(keycloakId).roles().realmLevel().add(List.of(role));
            log.debug("Assigned role '{}' to Keycloak user {}", roleName, keycloakId);
        } catch (Exception e) {
            log.error("Failed to assign role '{}' to Keycloak user {}: {}", roleName, keycloakId, e.getMessage(), e);
            throw new RuntimeException("Failed to assign role '" + roleName + "' to user in Keycloak", e);
        }
    }

    /**
     * Removes a Keycloak realm role from a user by role name.
     */
    public void removeRoleFromUser(String keycloakId, String roleName) {
        if (keycloakId == null || keycloakId.isBlank()) {
            throw new IllegalArgumentException("keycloakId must not be null or blank");
        }
        if (roleName == null || roleName.isBlank()) {
            throw new IllegalArgumentException("roleName must not be null or blank");
        }
        try {
            var realmResource = keycloakProvider.getAdminInstance().realm(keycloakProvider.getRealm());
            RoleRepresentation role = realmResource.roles().get(roleName).toRepresentation();
            realmResource.users().get(keycloakId).roles().realmLevel().remove(List.of(role));
            log.debug("Removed role '{}' from Keycloak user {}", roleName, keycloakId);
        } catch (Exception e) {
            log.error("Failed to remove role '{}' from Keycloak user {}: {}", roleName, keycloakId, e.getMessage(), e);
            throw new RuntimeException("Failed to remove role '" + roleName + "' from user in Keycloak", e);
        }
    }

    /**
     * Deletes a Keycloak user — used for compensation on failed account creation.
     */
    public void deleteUser(String keycloakId) {
        if (keycloakId == null) return;
        try {
            keycloakProvider.getAdminInstance().realm(keycloakProvider.getRealm())
                    .users().get(keycloakId).remove();
            log.info("Compensated: deleted Keycloak user {}", keycloakId);
        } catch (Exception e) {
            log.error("Failed to compensate Keycloak user {}", keycloakId, e);
        }
    }

    /**
     * Reports whether realm brute-force detection has temporarily locked the given Keycloak user.
     * <p>
     * The token endpoint returns a generic {@code invalid_grant / "Invalid user credentials"} for a
     * locked account (indistinguishable from a wrong password), so lockout can only be observed via
     * the admin attack-detection API. Fails open — a status-check error returns {@code false} so a
     * transient admin-API problem never blocks an otherwise-valid login attempt.
     */
    public boolean isTemporarilyLockedByBruteForce(String keycloakId) {
        if (keycloakId == null || keycloakId.isBlank()) return false;

        long now = System.nanoTime();
        CachedLockStatus cached = lockStatusCache.get(keycloakId);
        if (cached != null && cached.expiresAtNanos() > now) {
            return cached.locked();
        }

        Boolean locked = queryBruteForceStatus(keycloakId);
        if (locked == null) {
            // Probe failed — fail open to "not locked" but do NOT cache this "don't know",
            // so the next attempt re-queries once the admin API recovers.
            return false;
        }
        cacheLockStatus(keycloakId, locked, now);
        return locked;
    }

    /**
     * Queries Keycloak's attack-detection API for the user's current brute-force lock state.
     * Returns {@code null} when the probe itself fails, so the caller can fail open without
     * caching an unknown result — a transient admin-API problem never blocks an otherwise-valid
     * login attempt, nor does it get "stuck" as unlocked for the cache TTL.
     */
    private Boolean queryBruteForceStatus(String keycloakId) {
        try {
            Map<String, Object> status = keycloakProvider.getAdminInstance()
                    .realm(keycloakProvider.getRealm())
                    .attackDetection()
                    .bruteForceUserStatus(keycloakId);
            return Boolean.TRUE.equals(status.get("disabled"));
        } catch (Exception e) {
            log.warn("Could not read brute-force status for Keycloak user {}: {}", keycloakId, e.getMessage());
            return null;
        }
    }

    private void cacheLockStatus(String keycloakId, boolean locked, long now) {
        if (lockStatusCache.size() >= lockoutProbeCacheMaxSize) {
            // Purge expired entries first; if the cache is still full of live entries, skip caching
            // so memory stays bounded. Correctness is unaffected — the next probe simply re-queries.
            lockStatusCache.entrySet().removeIf(e -> e.getValue().expiresAtNanos() <= now);
            if (lockStatusCache.size() >= lockoutProbeCacheMaxSize) return;
        }
        long expiresAtNanos = now + TimeUnit.SECONDS.toNanos(lockoutProbeCacheTtlSeconds);
        lockStatusCache.put(keycloakId, new CachedLockStatus(locked, expiresAtNanos));
    }
}
