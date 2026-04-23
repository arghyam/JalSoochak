package org.arghyam.jalsoochak.user.service;

import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.config.KeycloakProvider;
import org.arghyam.jalsoochak.user.enums.TenantUserStatus;
import org.arghyam.jalsoochak.user.exceptions.KeycloakOperationException;
import org.arghyam.jalsoochak.user.repository.TenantUserRecord;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.util.PasswordCipher;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Lazily provisions Keycloak accounts for staff users on their first successful OTP login.
 *
 * <p><b>Idempotency strategy:</b> The presence of an AES-GCM encrypted managed password
 * in {@code user_table.password} is the authoritative signal that a Keycloak account already
 * exists. Known placeholder values ({@code CSV_ONBOARDED}, {@code KEYCLOAK_MANAGED}) indicate
 * no Keycloak account yet. Any value that cannot be decrypted by {@link PasswordCipher} triggers
 * re-provisioning (with compensation cleanup of the orphaned Keycloak user if needed).
 *
 * <p><b>Security:</b> Managed passwords are 48-byte random secrets (64 base64-URL chars),
 * stored AES-256-GCM encrypted. They are never exposed to the user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffKeycloakService {

    /**
     * Result of {@link #ensureKeycloakAccount}. {@code keycloakUuid} is non-null only when a
     * <em>new</em> Keycloak account was just provisioned and its UUID written to the DB — callers
     * should use this to trigger downstream sync (e.g. analytics). On the fast path (existing
     * account), {@code keycloakUuid} is {@code null}.
     */
    public record ProvisionResult(String managedPassword, UUID keycloakUuid) {}

    /** Placeholder values set when the user was created without a Keycloak account. */
    private static final Set<String> PLACEHOLDER_PASSWORDS = Set.of("CSV_ONBOARDED", "KEYCLOAK_MANAGED");

    private static final int MANAGED_PASSWORD_BYTES = 48;

    private final KeycloakProvider keycloakProvider;
    private final KeycloakAdminHelper keycloakAdminHelper;
    private final UserTenantRepository userTenantRepository;
    private final PasswordCipher passwordCipher;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Ensures a Keycloak account exists for the given staff user and returns the managed password
     * (plaintext) required to obtain a token via password-grant.
     *
     * <p>If the user already has an encrypted managed password in the DB, it is decrypted
     * and returned directly (fast path). Otherwise a new Keycloak account is created,
     * a random managed password is set, and both are persisted.
     *
     * @param user       the staff user record (decrypted PII already available on the record)
     * @param tenantCode tenant state code (e.g. "MP")
     * @param schema     tenant schema name (e.g. "tenant_mp")
     * @return a {@link ProvisionResult} containing the plaintext managed password and, when a new
     *         account was just created, the Keycloak UUID written to the DB
     */
    public ProvisionResult ensureKeycloakAccount(TenantUserRecord user, String tenantCode, String schema) {
        // Fast path: existing managed password in DB
        String existingPassword = userTenantRepository.findPasswordByUserId(schema, user.id())
                .orElse(null);

        if (existingPassword != null && !existingPassword.trim().isBlank() && !PLACEHOLDER_PASSWORDS.contains(existingPassword)) {
            try {
                return new ProvisionResult(passwordCipher.decrypt(existingPassword), null);
            } catch (IllegalStateException e) {
                log.warn("Failed to decrypt managed password for userId={} — reprovisioning Keycloak account",
                        user.id());
                // Fall through to provisioning
            }
        }

        // Slow path: create Keycloak account
        return provisionKeycloakAccount(user, tenantCode, schema);
    }

    /**
     * Revokes the Keycloak account for a staff user and resets the managed-password record in the DB.
     *
     * <p>Call this on any lifecycle event that invalidates the phone-as-username credential vehicle:
     * phone number change, account deactivation, or hard deletion. The Keycloak deletion is
     * best-effort — a failure is logged but does not prevent the DB placeholder from being written.
     * The placeholder in DB ensures re-provisioning is triggered on the next OTP login regardless.
     *
     * @param user   the staff user record
     * @param schema the tenant schema name (e.g. "tenant_mp")
     */
    public void revokeKeycloakAccount(TenantUserRecord user, String schema) {
        String uuid = user.keycloakUuid();
        if (uuid != null && !uuid.isBlank()) {
            keycloakAdminHelper.deleteUser(uuid);  // best-effort; logs on failure, does not throw
        }
        userTenantRepository.resetKeycloakCredentials(schema, user.id());
        log.info("Keycloak account revoked for staffUserId={} schema={}", user.id(), schema);
    }

    private ProvisionResult provisionKeycloakAccount(TenantUserRecord user, String tenantCode, String schema) {
        UUID keycloakUuid = null;
        try {
            var usersResource = keycloakProvider.getAdminInstance()
                    .realm(keycloakProvider.getRealm()).users();

            UserRepresentation userRep = buildUserRepresentation(user, tenantCode);

            try (Response createResponse = usersResource.create(userRep)) {
                if (createResponse.getStatus() == 409) {
                    // Path 1: concurrent caller may have just provisioned this user — re-read their password.
                    String concurrentPassword = userTenantRepository.findPasswordByUserId(schema, user.id()).orElse(null);
                    if (concurrentPassword != null && !concurrentPassword.isBlank()
                            && !PLACEHOLDER_PASSWORDS.contains(concurrentPassword)) {
                        try {
                            // keycloakUuid=null: the concurrent winner is responsible for the analytics sync
                            return new ProvisionResult(passwordCipher.decrypt(concurrentPassword), null);
                        } catch (IllegalStateException decryptEx) {
                            log.warn("Failed to decrypt concurrent managed password for userId={}", user.id());
                        }
                    }
                    // Path 2: orphaned Keycloak user (created in a prior attempt that crashed before writing
                    // the managed password to DB) — find it by username, reset the password, and re-sync DB.
                    log.warn("Keycloak 409 for userId={}: no concurrent password found — attempting orphan recovery",
                            user.id());
                    return recoverOrphanedKeycloakAccount(usersResource, user, tenantCode, schema);
                }
                if (createResponse.getStatus() != 201) {
                    String body = createResponse.hasEntity()
                            ? createResponse.readEntity(String.class)
                            : "<no body>";
                    log.error("Keycloak user creation failed: HTTP {} reason={}", createResponse.getStatus(), body);
                    throw new KeycloakOperationException(
                            "Failed to create Keycloak user for staff: HTTP " + createResponse.getStatus());
                }
                URI locationUri = createResponse.getLocation();
                if (locationUri == null) {
                    throw new KeycloakOperationException(
                            "Keycloak returned 201 but no Location header — cannot extract user UUID");
                }
                String location = locationUri.toString();
                keycloakUuid = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
            }

            return setPasswordAndPersist(keycloakUuid, usersResource, user, tenantCode, schema);

        } catch (RuntimeException e) {
            if (keycloakUuid != null) {
                keycloakAdminHelper.deleteUser(keycloakUuid.toString());
            }
            throw e;
        } catch (Exception e) {
            if (keycloakUuid != null) {
                keycloakAdminHelper.deleteUser(keycloakUuid.toString());
            }
            throw new KeycloakOperationException("Failed to provision staff Keycloak account", e);
        }
    }

    /**
     * Recovers a Keycloak account that was created in a prior provisioning attempt but whose
     * managed password was never written to the DB. Looks up the orphaned user by username
     * (phone number), verifies ownership before touching credentials, generates a fresh managed
     * password, resets it in Keycloak, and conditionally persists the result.
     *
     * <p>If ownership verification detects a <em>stale</em> account (the stored
     * {@code database_user_id} no longer holds the phone), the stale Keycloak account is deleted
     * and a fresh one is provisioned for the current user.
     *
     * <p>The DB write uses a conditional update (placeholder-guarded) so a concurrent caller's
     * already-written managed password is never overwritten.
     */
    private ProvisionResult recoverOrphanedKeycloakAccount(UsersResource usersResource,
                                                           TenantUserRecord user,
                                                           String tenantCode,
                                                           String schema) {
        List<UserRepresentation> existing = usersResource.searchByUsername(user.phoneNumber(), true);
        if (existing.size() != 1) {
            log.error("Orphan recovery failed: expected 1 Keycloak user for userId={}, found={}",
                    user.id(), existing.size());
            throw new KeycloakOperationException("Failed to create Keycloak user for staff: HTTP 409");
        }

        UUID orphanUuid = UUID.fromString(existing.get(0).getId());

        UserRepresentation orphanRep = usersResource.get(orphanUuid.toString()).toRepresentation();
        OwnershipVerdict verdict = verifyOrphanOwnership(
                user, tenantCode, schema, orphanUuid.toString(), orphanRep.getAttributes());

        if (verdict == OwnershipVerdict.STALE) {
            // Stale account: the previous owner is gone or has a different phone number.
            // Delete the abandoned Keycloak account and provision a fresh one for the current user.
            keycloakAdminHelper.deleteUser(orphanUuid.toString());
            return createFreshAccountAfterStaleDeletion(usersResource, user, tenantCode, schema);
        }

        // PROCEED: this is genuinely our user's orphaned account — reset the password and persist.
        String managedPassword = generateManagedPassword();

        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(managedPassword);
        cred.setTemporary(false);
        usersResource.get(orphanUuid.toString()).resetPassword(cred);

        String encryptedPassword = passwordCipher.encrypt(managedPassword);
        int affected = userTenantRepository.updateKeycloakUuidAndPasswordIfUnmanaged(
                schema, user.id(), orphanUuid.toString(), encryptedPassword);

        if (affected == 0) {
            // A concurrent caller already wrote a managed password to the DB.
            String concurrentPassword = userTenantRepository.findPasswordByUserId(schema, user.id()).orElse(null);
            if (concurrentPassword != null && !concurrentPassword.isBlank()
                    && !PLACEHOLDER_PASSWORDS.contains(concurrentPassword)) {
                try {
                    log.info("Orphan recovery: concurrent writer won DB race for staffUserId={}", user.id());
                    // keycloakUuid=null: the concurrent winner is responsible for the analytics sync
                    return new ProvisionResult(passwordCipher.decrypt(concurrentPassword), null);
                } catch (IllegalStateException e) {
                    log.warn("Orphan recovery: failed to decrypt concurrent managed password for userId={}", user.id());
                }
            }
            log.error("Orphan recovery: conditional DB update returned 0 rows and no usable concurrent password for userId={}",
                    user.id());
            throw new KeycloakOperationException("Failed to sync orphan recovery to DB for staffUserId=" + user.id());
        }

        log.info("Orphan Keycloak account recovered for staffUserId={} tenantCode={}", user.id(), tenantCode);
        return new ProvisionResult(managedPassword, orphanUuid);
    }

    /**
     * Creates a fresh Keycloak account for the current user after a stale account was deleted.
     * Includes compensation: if {@code resetPassword} or the DB write fails, the newly-created
     * Keycloak account is deleted to avoid leaving another orphan.
     */
    private ProvisionResult createFreshAccountAfterStaleDeletion(UsersResource usersResource,
                                                                  TenantUserRecord user,
                                                                  String tenantCode,
                                                                  String schema) {
        UserRepresentation userRep = buildUserRepresentation(user, tenantCode);
        UUID keycloakUuid = null;
        try (Response createResponse = usersResource.create(userRep)) {
            if (createResponse.getStatus() != 201) {
                String body = createResponse.hasEntity()
                        ? createResponse.readEntity(String.class)
                        : "<no body>";
                log.error("Re-provisioning after stale orphan deletion failed: HTTP {} reason={}",
                        createResponse.getStatus(), body);
                throw new KeycloakOperationException(
                        "Failed to re-provision staff Keycloak account after stale orphan deletion: HTTP "
                                + createResponse.getStatus());
            }
            URI locationUri = createResponse.getLocation();
            if (locationUri == null) {
                throw new KeycloakOperationException(
                        "Keycloak returned 201 but no Location header on re-provision after stale deletion");
            }
            String location = locationUri.toString();
            keycloakUuid = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        }

        try {
            ProvisionResult result = setPasswordAndPersist(keycloakUuid, usersResource, user, tenantCode, schema);
            log.info("Fresh Keycloak account provisioned after stale orphan deletion for staffUserId={} tenantCode={}",
                    user.id(), tenantCode);
            return result;
        } catch (RuntimeException e) {
            keycloakAdminHelper.deleteUser(keycloakUuid.toString());
            throw e;
        }
    }

    /**
     * Sets the managed password in Keycloak and persists both the Keycloak UUID and the
     * AES-encrypted password to the DB. Compensation (deleting the Keycloak user on failure)
     * is the <em>caller's</em> responsibility.
     */
    private ProvisionResult setPasswordAndPersist(UUID keycloakUuid, UsersResource usersResource,
                                                   TenantUserRecord user, String tenantCode, String schema) {
        String managedPassword = generateManagedPassword();

        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(managedPassword);
        cred.setTemporary(false);
        usersResource.get(keycloakUuid.toString()).resetPassword(cred);

        String encryptedPassword = passwordCipher.encrypt(managedPassword);
        userTenantRepository.updateKeycloakUuidAndPassword(schema, user.id(), keycloakUuid.toString(), encryptedPassword);

        log.info("Keycloak account provisioned for staffUserId={} tenantCode={}", user.id(), tenantCode);
        return new ProvisionResult(managedPassword, keycloakUuid);
    }

    /** Builds a {@link UserRepresentation} for Keycloak account creation. */
    private UserRepresentation buildUserRepresentation(TenantUserRecord user, String tenantCode) {
        UserRepresentation userRep = new UserRepresentation();
        userRep.setUsername(user.phoneNumber());
        userRep.setEnabled(true);
        userRep.setEmailVerified(true);
        String[] nameParts = splitName(user.title());
        userRep.setFirstName(nameParts[0]);
        userRep.setLastName(nameParts[1]);
        userRep.setAttributes(buildAttributes(tenantCode, user.cName(), user.id()));
        return userRep;
    }

    /**
     * Verifies that the Keycloak user identified by {@code orphanUuid} is safe to recover
     * for the current user and tenant.
     *
     * <p>Returns {@link OwnershipVerdict#PROCEED} when the account belongs to {@code user}, or
     * {@link OwnershipVerdict#STALE} when the stored {@code database_user_id} no longer holds
     * the phone (old owner is gone or re-assigned). Throws {@link KeycloakOperationException}
     * for cross-tenant violations or genuine phone conflicts.
     *
     * <p>{@code tenant_state_code} is always required — it establishes the tenant boundary and
     * prevents cross-tenant password reset in a shared Keycloak realm. If {@code database_user_id}
     * is also present (users provisioned after the attribute was introduced), it is checked as an
     * additional per-user constraint. User IDs are scoped to a tenant schema and are not globally
     * unique, so {@code database_user_id} alone is insufficient without the tenant check.
     */
    private OwnershipVerdict verifyOrphanOwnership(TenantUserRecord user, String tenantCode, String schema,
                                                    String orphanUuid, Map<String, List<String>> attributes) {
        boolean ownedByTenant = attributes != null
                && attributes.containsKey("tenant_state_code")
                && attributes.get("tenant_state_code").stream().anyMatch(tenantCode::equalsIgnoreCase);
        if (!ownedByTenant) {
            log.error("Orphan recovery ownership check failed: keycloakUuid={} tenant_state_code does not match "
                            + "expected={} for userId={}",
                    orphanUuid, tenantCode, user.id());
            throw new KeycloakOperationException(
                    "Orphan recovery aborted: Keycloak user does not belong to expected tenant");
        }

        if (attributes != null && attributes.containsKey("database_user_id")) {
            List<String> userIdAttr = attributes.get("database_user_id");
            String expectedUserId = String.valueOf(user.id());
            String storedIdStr = userIdAttr.isEmpty() ? null : userIdAttr.get(0);

            if (storedIdStr == null || !expectedUserId.equals(storedIdStr)) {
                // database_user_id is present but doesn't match. Determine if this is a stale
                // account (old owner gone/re-assigned) or a genuine conflict (both users active
                // with the same phone).
                Long storedUserId = null;
                if (storedIdStr != null) {
                    try {
                        storedUserId = Long.parseLong(storedIdStr);
                    } catch (NumberFormatException e) {
                        log.warn("Orphan recovery: database_user_id='{}' is not a valid Long for keycloakUuid={}",
                                storedIdStr, orphanUuid);
                    }
                }

                if (storedUserId != null) {
                    TenantUserRecord owner = userTenantRepository.findUserById(schema, storedUserId).orElse(null);
                    boolean ownerIsActive = owner != null
                            && user.phoneNumber().equals(owner.phoneNumber())
                            && owner.status() != null
                            && owner.status() == TenantUserStatus.ACTIVE.code;
                    if (ownerIsActive) {
                        // Genuine conflict: the old owner is still active and holds this phone number.
                        // This requires admin intervention — do not reset credentials.
                        log.error("Genuine ownership conflict: keycloakUuid={} database_user_id={} still holds "
                                        + "phone for current userId={}",
                                orphanUuid, storedUserId, user.id());
                        throw new KeycloakOperationException(
                                "Orphan recovery aborted: Keycloak user does not match expected database user");
                    }
                    // owner is null, inactive, or holds a different phone — treat as abandoned
                }

                // Old owner is gone, inactive, or has a different phone — treat as abandoned.
                log.warn("Stale ownership on orphaned keycloakUuid={}: storedUserId={} no longer holds phone "
                                + "— treating as abandoned for staffUserId={}",
                        orphanUuid, storedUserId, user.id());
                return OwnershipVerdict.STALE;
            }
        }

        return OwnershipVerdict.PROCEED;
    }

    /** Return value of {@link #verifyOrphanOwnership}. */
    private enum OwnershipVerdict {
        /** The orphaned account belongs to {@code user} — proceed with password reset. */
        PROCEED,
        /** The account belonged to a different user who no longer holds this phone — delete and re-provision. */
        STALE
    }

    /** Returns [firstName, lastName]. If title has no space, lastName is empty string. */
    private String[] splitName(String title) {
        if (title == null || title.isBlank()) {
            return new String[]{"Staff", ""};
        }
        int idx = title.indexOf(' ');
        if (idx < 0) {
            return new String[]{title, ""};
        }
        return new String[]{title.substring(0, idx), title.substring(idx + 1)};
    }

    private Map<String, List<String>> buildAttributes(String tenantCode, String userType, Long userId) {
        Map<String, List<String>> attrs = new HashMap<>();
        attrs.put("tenant_state_code", List.of(tenantCode.toUpperCase()));
        attrs.put("user_type", List.of(userType));
        if (userId != null) {
            attrs.put("database_user_id", List.of(String.valueOf(userId)));
        }
        return attrs;
    }

    private String generateManagedPassword() {
        byte[] bytes = new byte[MANAGED_PASSWORD_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
