package org.arghyam.jalsoochak.user.service;

import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.config.KeycloakProvider;
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
     * @return the plaintext managed password to use for the Keycloak token request
     */
    public String ensureKeycloakAccount(TenantUserRecord user, String tenantCode, String schema) {
        // Fast path: existing managed password in DB
        String existingPassword = userTenantRepository.findPasswordByUserId(schema, user.id())
                .orElse(null);

        if (existingPassword != null && !existingPassword.trim().isBlank() && !PLACEHOLDER_PASSWORDS.contains(existingPassword)) {
            try {
                return passwordCipher.decrypt(existingPassword);
            } catch (IllegalStateException e) {
                log.warn("Failed to decrypt managed password for userId={} — reprovisioning Keycloak account",
                        user.id());
                // Fall through to provisioning
            }
        }

        // Slow path: create Keycloak account
        return provisionKeycloakAccount(user, tenantCode, schema);
    }

    private String provisionKeycloakAccount(TenantUserRecord user, String tenantCode, String schema) {
        String keycloakUuid = null;
        try {
            var usersResource = keycloakProvider.getAdminInstance()
                    .realm(keycloakProvider.getRealm()).users();

            UserRepresentation userRep = new UserRepresentation();
            userRep.setUsername(user.phoneNumber());  // phone number as Keycloak username
            userRep.setEnabled(true);
            userRep.setEmailVerified(true);  // staff auth via OTP, not email — verification not applicable
            String[] nameParts = splitName(user.title());
            userRep.setFirstName(nameParts[0]);
            userRep.setLastName(nameParts[1]);
            userRep.setAttributes(buildAttributes(tenantCode, user.cName()));

            try (Response createResponse = usersResource.create(userRep)) {
                if (createResponse.getStatus() == 409) {
                    // Path 1: concurrent caller may have just provisioned this user — re-read their password.
                    String concurrentPassword = userTenantRepository.findPasswordByUserId(schema, user.id()).orElse(null);
                    if (concurrentPassword != null && !concurrentPassword.isBlank()
                            && !PLACEHOLDER_PASSWORDS.contains(concurrentPassword)) {
                        try {
                            return passwordCipher.decrypt(concurrentPassword);
                        } catch (IllegalStateException decryptEx) {
                            log.warn("Failed to decrypt concurrent managed password for userId={}", user.id());
                        }
                    }
                    // Path 2: orphaned Keycloak user (created in a prior attempt that crashed before writing
                    // the managed password to DB) — find it by username, reset the password, and re-sync DB.
                    log.warn("Keycloak 409 for userId={}: no concurrent password found — attempting orphan recovery",
                            user.id());
                    return recoverOrphanedKeycloakAccount(usersResource, user, schema);
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
                keycloakUuid = location.substring(location.lastIndexOf('/') + 1);
            }

            String managedPassword = generateManagedPassword();

            CredentialRepresentation cred = new CredentialRepresentation();
            cred.setType(CredentialRepresentation.PASSWORD);
            cred.setValue(managedPassword);
            cred.setTemporary(false);
            usersResource.get(keycloakUuid).resetPassword(cred);

            String encryptedPassword = passwordCipher.encrypt(managedPassword);
            userTenantRepository.updateKeycloakUuidAndPassword(schema, user.id(), keycloakUuid, encryptedPassword);

            log.info("Keycloak account provisioned for staffUserId={} tenantCode={}", user.id(), tenantCode);
            return managedPassword;

        } catch (RuntimeException e) {
            if (keycloakUuid != null) {
                keycloakAdminHelper.deleteUser(keycloakUuid);
            }
            throw e;
        } catch (Exception e) {
            if (keycloakUuid != null) {
                keycloakAdminHelper.deleteUser(keycloakUuid);
            }
            throw new KeycloakOperationException("Failed to provision staff Keycloak account", e);
        }
    }

    /**
     * Recovers a Keycloak account that was created in a prior provisioning attempt but whose
     * managed password was never written to the DB (e.g. the service crashed between Keycloak
     * creation and the DB update). Looks up the orphaned user by username (phone number),
     * generates a fresh managed password, resets it in Keycloak, and persists the result.
     */
    private String recoverOrphanedKeycloakAccount(UsersResource usersResource,
                                                   TenantUserRecord user,
                                                   String schema) {
        List<UserRepresentation> existing = usersResource.searchByUsername(user.phoneNumber(), true);
        if (existing.size() != 1) {
            log.error("Orphan recovery failed: expected 1 Keycloak user for userId={}, found={}",
                    user.id(), existing.size());
            throw new KeycloakOperationException("Failed to create Keycloak user for staff: HTTP 409");
        }
        String orphanUuid = existing.get(0).getId();
        String managedPassword = generateManagedPassword();

        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(managedPassword);
        cred.setTemporary(false);
        usersResource.get(orphanUuid).resetPassword(cred);

        String encryptedPassword = passwordCipher.encrypt(managedPassword);
        userTenantRepository.updateKeycloakUuidAndPassword(schema, user.id(), orphanUuid, encryptedPassword);

        log.info("Orphan Keycloak account recovered for staffUserId={}", user.id());
        return managedPassword;
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

    private Map<String, List<String>> buildAttributes(String tenantCode, String userType) {
        Map<String, List<String>> attrs = new HashMap<>();
        attrs.put("tenant_state_code", List.of(tenantCode.toUpperCase()));
        attrs.put("user_type", List.of(userType));
        return attrs;
    }

    private String generateManagedPassword() {
        byte[] bytes = new byte[MANAGED_PASSWORD_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
