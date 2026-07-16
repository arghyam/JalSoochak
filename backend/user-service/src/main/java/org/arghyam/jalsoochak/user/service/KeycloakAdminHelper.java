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
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class KeycloakAdminHelper {

    private final KeycloakProvider keycloakProvider;
    private final UserCommonRepository userCommonRepository;
    private final MetadataDecryptionHelper metadataDecryptionHelper;

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
        try {
            Map<String, Object> status = keycloakProvider.getAdminInstance()
                    .realm(keycloakProvider.getRealm())
                    .attackDetection()
                    .bruteForceUserStatus(keycloakId);
            return Boolean.TRUE.equals(status.get("disabled"));
        } catch (Exception e) {
            log.warn("Could not read brute-force status for Keycloak user {}: {}", keycloakId, e.getMessage());
            return false;
        }
    }
}
