package org.arghyam.jalsoochak.user.service.serviceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.config.KeycloakProvider;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.request.UpdateStaffRoleRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.RoleCountDTO;
import org.arghyam.jalsoochak.user.dto.response.TenantStaffResponseDTO;
import org.arghyam.jalsoochak.user.enums.TenantUserStatus;
import org.arghyam.jalsoochak.user.event.UserAnalyticsEventPublisher;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.exceptions.ForbiddenAccessException;
import org.arghyam.jalsoochak.user.exceptions.ResourceNotFoundException;
import org.arghyam.jalsoochak.user.repository.TenantStaffRepository;
import org.arghyam.jalsoochak.user.repository.TenantUserRecord;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.service.KeycloakAdminHelper;
import org.arghyam.jalsoochak.user.service.StaffKeycloakService;
import org.arghyam.jalsoochak.user.service.TenantStaffService;
import org.arghyam.jalsoochak.user.util.SecurityUtils;
import org.arghyam.jalsoochak.user.util.TenantSchemaResolver;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantStaffServiceImpl implements TenantStaffService {

    private final TenantStaffRepository tenantStaffRepository;
    private final UserTenantRepository userTenantRepository;
    private final UserCommonRepository userCommonRepository;
    private final KeycloakAdminHelper keycloakAdminHelper;
    private final KeycloakProvider keycloakProvider;
    private final UserAnalyticsEventPublisher userAnalyticsEventPublisher;
    private final StaffKeycloakService staffKeycloakService;

    @Value("${staff.allowed-update-roles:SECTION_OFFICER,DISTRICT_OFFICER}")
    private List<String> allowedUpdateRoles;

    @Override
    public PageResponseDTO<TenantStaffResponseDTO> listStaff(
            String tenantCode,
            int page,
            int limit,
            String sortBy,
            String sortDir,
            List<String> role,
            String status,
            String name
    ) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);

        int p = Math.max(0, page);
        int size = clampLimit(limit);
        int offset = p * size;

        List<String> normalizedRoles = normalizeRoles(role);
        Integer statusCode = parseStatus(status);
        TenantStaffRepository.StaffPage result = tenantStaffRepository.listStaffPage(
                schemaName, normalizedRoles, statusCode, name, sortBy, sortDir, offset, size);
        return PageResponseDTO.of(result.items(), result.total(), p, size);
    }

    @Override
    public List<RoleCountDTO> countStaffByRole(String tenantCode, String status, String name) {
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        Integer statusCode = parseStatus(status);
        return tenantStaffRepository.countByRole(schemaName, statusCode, name);
    }

    @Override
    @Transactional
    public TenantStaffResponseDTO updateStaffRole(Long id, UpdateStaffRoleRequestDTO request, Authentication caller) {
        String callerTenantCode = SecurityUtils.extractTenantCode(caller);
        if (callerTenantCode == null || !callerTenantCode.equalsIgnoreCase(request.tenantCode())) {
            throw new ForbiddenAccessException("State admin can only update staff within their own state");
        }

        if (!allowedUpdateRoles.contains(request.newRole())) {
            throw new IllegalArgumentException("Role must be one of: " + allowedUpdateRoles);
        }

        String schema = TenantSchemaResolver.requireSchemaNameFromTenantCode(request.tenantCode());
        TenantUserRecord user = userTenantRepository.findUserById(schema, id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));

        String currentRole = user.cName();
        if (currentRole == null || currentRole.isBlank()) {
            throw new IllegalArgumentException("Current user role is missing for user: " + id);
        }
        if (Objects.equals(currentRole, request.newRole())) {
            throw new IllegalArgumentException("User already has role: " + request.newRole());
        }

        // Look up DB type ID before touching Keycloak — fail fast if role is unknown
        Long newUserTypeId = userCommonRepository.findUserTypeIdByName(request.newRole())
                .map(Integer::longValue)
                .orElseThrow(() -> new ResourceNotFoundException("Unknown role: " + request.newRole()));

        String keycloakUuid = user.keycloakUuid();
        if (keycloakUuid == null || keycloakUuid.isBlank()) {
            throw new IllegalStateException("User " + id + " has no Keycloak UUID");
        }

        var usersResource = keycloakProvider.getAdminInstance().realm(keycloakProvider.getRealm()).users();

        try {
            keycloakAdminHelper.removeRoleFromUser(keycloakUuid, currentRole);
            keycloakAdminHelper.assignRoleToUser(keycloakUuid, request.newRole());

            var userResource = usersResource.get(keycloakUuid);
            UserRepresentation rep = userResource.toRepresentation();
            Map<String, List<String>> attrs = new HashMap<>(
                    rep.getAttributes() != null ? rep.getAttributes() : Map.of());
            attrs.put("user_type", List.of(request.newRole()));
            rep.setAttributes(attrs);
            userResource.update(rep);

            int rowsAffected = userTenantRepository.updateUserRole(schema, id, newUserTypeId);
            if (rowsAffected == 0) {
                throw new ResourceNotFoundException("User not found during role update: " + id);
            }

            Integer tenantId = userCommonRepository.findTenantIdByStateCode(request.tenantCode())
                    .orElse(null);
            if (tenantId == null) {
                log.warn("Cannot publish staff user analytics event: tenantId not found for stateCode={}", request.tenantCode());
            } else {
                userAnalyticsEventPublisher.publishStaffUserUpdatedAfterCommit(
                        id, tenantId, newUserTypeId.intValue(), keycloakUuid, user.email(), 1);
            }

            return tenantStaffRepository.findStaffById(schema, id)
                    .orElseThrow(() -> new ResourceNotFoundException("Staff not found after update: " + id));
        } catch (Exception e) {
            try {
                keycloakAdminHelper.assignRoleToUser(keycloakUuid, currentRole);
            } catch (Exception ce) {
                log.error("Compensation failed - assignRole {} for user {}: {}", currentRole, keycloakUuid, ce.getMessage(), ce);
            }
            try {
                keycloakAdminHelper.removeRoleFromUser(keycloakUuid, request.newRole());
            } catch (Exception ce) {
                log.error("Compensation failed - removeRole {} for user {}: {}", request.newRole(), keycloakUuid, ce.getMessage(), ce);
            }
            try {
                var userResource = usersResource.get(keycloakUuid);
                UserRepresentation rep = userResource.toRepresentation();
                Map<String, List<String>> rollbackAttrs = new HashMap<>(
                        rep.getAttributes() != null ? rep.getAttributes() : Map.of());
                rollbackAttrs.put("user_type", List.of(currentRole));
                rep.setAttributes(rollbackAttrs);
                userResource.update(rep);
            } catch (Exception ce) {
                log.error("Compensation failed - updateAttributes for user {}: {}", keycloakUuid, ce.getMessage(), ce);
            }
            throw e;
        }
    }

    @Override
    public void deactivateStaff(Long id, String tenantCode, Authentication caller) {
        String callerTenantCode = SecurityUtils.extractTenantCode(caller);
        String callerRole = SecurityUtils.extractRole(caller).orElse(null);

        boolean isSuperUser = "SUPER_USER".equalsIgnoreCase(callerRole);
        boolean isStateAdmin = "STATE_ADMIN".equalsIgnoreCase(callerRole);
        if (!isSuperUser && (!isStateAdmin || callerTenantCode == null || !callerTenantCode.equalsIgnoreCase(tenantCode))) {
            throw new ForbiddenAccessException("Only a STATE_ADMIN within their own tenant or a SUPER_USER may deactivate staff");
        }

        String schema = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        TenantUserRecord user = userTenantRepository.findUserById(schema, id)
                .orElseThrow(() -> new ResourceNotFoundException("Staff user not found: " + id));

        if (user.status() != null && user.status() == TenantUserStatus.INACTIVE.code) {
            throw new BadRequestException("Staff user is already deactivated");
        }

        Integer tenantId = userCommonRepository.findTenantIdByStateCode(tenantCode).orElse(null);
        Long actorId = resolveActorId(caller, tenantId);

        // Deactivate in DB first (auto-commits via JdbcTemplate) before touching Keycloak,
        // so DB and Keycloak states do not diverge on transaction rollback.
        int affected = userTenantRepository.deactivateStaffUser(schema, id, actorId);
        if (affected == 0) {
            // Concurrent deactivation won the race — not an error, outcome is the same.
            log.info("Staff deactivation: user already deactivated by a concurrent request — staffUserId={}", id);
        }

        // Revoke Keycloak account outside the transactional boundary. Best-effort — failure is
        // logged inside revokeKeycloakAccount but does not roll back the already-committed DB change.
        staffKeycloakService.revokeKeycloakAccount(user, schema, actorId);

        if (affected > 0) {
            if (tenantId != null) {
                userAnalyticsEventPublisher.publishStaffUserUpdatedAfterCommit(
                        id, tenantId,
                        user.userTypeId() != null ? user.userTypeId().intValue() : null,
                        user.keycloakUuid(),
                        user.email(),
                        TenantUserStatus.INACTIVE.code
                );
            } else {
                log.warn("Cannot publish staff deactivation analytics: tenantId not found for tenantCode={}", tenantCode);
            }
        }

        log.info("Staff user deactivated: staffUserId={} tenantCode={}", id, tenantCode);
    }

    /** Resolves the DB actor id from the JWT; falls back to null (system action) if not found. */
    private Long resolveActorId(Authentication caller, Integer tenantId) {
        if (tenantId == null) {
            return null;
        }
        String callerTenantCode = SecurityUtils.extractTenantCode(caller);
        if (callerTenantCode == null) {
            return null;  // SUPER_USER has no tenant — actor id not resolvable from tenant schema
        }
        String callerUuid;
        try {
            callerUuid = SecurityUtils.getKeycloakId(caller);
        } catch (Exception e) {
            return null;
        }
        // Staff callers are in the tenant schema; look up by Keycloak UUID.
        String schema = TenantSchemaResolver.requireSchemaNameFromTenantCode(callerTenantCode);
        return userTenantRepository.findUserByKeycloakUuid(schema, callerUuid)
                .map(TenantUserRecord::id)
                .orElse(null);
    }

    private int clampLimit(int limit) {
        if (limit < 1) {
            return 1;
        }
        return Math.min(limit, 100);
    }

    private List<String> normalizeRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String entry : roles) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String[] parts = entry.split(",");
            for (String part : parts) {
                String value = part == null ? "" : part.trim();
                if (!value.isEmpty()) {
                    normalized.add(value.toLowerCase(Locale.ROOT));
                }
            }
        }
        return List.copyOf(normalized);
    }

    private Integer parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String normalized = status.trim();
        if (normalized.matches("^[0-9]+$")) {
            return Integer.parseInt(normalized);
        }
        return switch (normalized.toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> 1;
            case "INACTIVE" -> 0;
            default -> throw new IllegalArgumentException("Unknown status: " + status);
        };
    }
}
