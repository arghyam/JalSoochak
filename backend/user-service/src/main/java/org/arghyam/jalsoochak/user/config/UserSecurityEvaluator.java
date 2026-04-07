package org.arghyam.jalsoochak.user.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.enums.AdminUserStatus;
import org.arghyam.jalsoochak.user.enums.TenantAccessRole;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.records.AdminUserRow;
import org.arghyam.jalsoochak.user.util.SecurityUtils;
import org.arghyam.jalsoochak.user.util.TenantAccessValidator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Security evaluator used in {@code @PreAuthorize} SpEL expressions to enforce
 * object-level (tenant-scoped) authorization for STATE_ADMIN callers.
 *
 * <p>Usage in controllers:
 * <pre>
 * {@code @PreAuthorize("hasAnyRole('SUPER_USER', 'STATE_ADMIN') and @userSecurity.canAccessUser(#id, authentication)")}
 * </pre>
 *
 * <p>SUPER_USER short-circuits the expression and bypasses the tenant DB lookup entirely.
 * STATE_ADMIN callers are checked against the {@code tenant_state_code} claim in their JWT.
 */
@Component("userSecurity")
@RequiredArgsConstructor
@Slf4j
public class UserSecurityEvaluator {

    private final UserCommonRepository userCommonRepository;

    /**
     * Returns {@code true} if the authenticated caller has access to the user identified by {@code userId}.
     *
     * <p>For SUPER_USER: Always returns {@code true}.
     * For STATE_ADMIN: Returns {@code true} only if the target user belongs to the same tenant.
     *
     * <p>Returns {@code false} (→ 403) when:
     * <ul>
     *   <li>The caller is not found in the database or is not active (stale/orphaned token)</li>
     *   <li>The user with the given ID does not exist — intentionally returns 403, not 404,
     *       to prevent STATE_ADMIN callers from probing valid user IDs via differing
     *       error codes (OWASP API1 — Broken Object Level Authorization)</li>
     *   <li>The caller's tenant does not match the user's tenant</li>
     *   <li>The caller is STATE_ADMIN but has no tenant_state_code claim</li>
     *   <li>Any unexpected exception occurs during evaluation</li>
     * </ul>
     *
     * <p>NOTE: This method must never throw. A {@code RuntimeException} thrown inside a
     * {@code @PreAuthorize} SpEL expression is wrapped by Spring in a
     * {@code SpelEvaluationException}, which bypasses domain-specific
     * {@code @ExceptionHandler} mappings and results in an unexpected 500 response.
     *
     * @param userId User ID to check access for
     * @param authentication Spring Authentication object
     * @return true if caller can access the user, false otherwise
     */
    public boolean canAccessUser(Long userId, Authentication authentication) {
        try {
            // Verify caller exists and is active — rejects stale/orphaned tokens regardless of role
            String callerUuid = SecurityUtils.getKeycloakId(authentication);
            Optional<AdminUserRow> caller = userCommonRepository.findAdminUserByUuid(callerUuid);
            if (caller.isEmpty() || caller.get().status() != AdminUserStatus.ACTIVE) {
                log.warn("Request to user {} denied: caller '{}' is missing or inactive", userId, callerUuid);
                return false;
            }

            Optional<String> callerRole = SecurityUtils.extractRole(authentication);

            // SUPER_USER and SUPER_STATE_ADMIN can access any user
            if (callerRole.map(r -> r.equals("SUPER_USER") || r.equals("SUPER_STATE_ADMIN")).orElse(false)) {
                return true;
            }

            // STATE_ADMIN must have matching tenant in a lifecycle state that permits access
            if (callerRole.map("STATE_ADMIN"::equals).orElse(false)) {
                String callerTenantCode = SecurityUtils.extractTenantCode(authentication);
                if (callerTenantCode == null || callerTenantCode.isBlank()) {
                    log.warn("STATE_ADMIN request to user {} denied: tenant_state_code claim absent from JWT", userId);
                    return false;
                }

                Integer callerTenantId = caller.get().tenantId();
                Optional<Integer> tenantStatusOpt = userCommonRepository.findTenantStatusByTenantId(callerTenantId);
                if (tenantStatusOpt.isEmpty() || !TenantAccessValidator.isAccessibleToSystemUser(tenantStatusOpt.get(), TenantAccessRole.STATE_ADMIN)) {
                    log.warn("STATE_ADMIN with tenant_state_code='{}' denied access to user {}: tenant status does not permit access",
                            callerTenantCode, userId);
                    return false;
                }

                boolean allowed = userCommonRepository.userBelongsToTenant(userId, callerTenantCode);
                if (!allowed) {
                    log.warn("STATE_ADMIN with tenant_state_code='{}' denied access to user {}",
                            callerTenantCode, userId);
                }
                return allowed;
            }

            log.warn("Request to user {} denied: caller has neither SUPER_USER nor STATE_ADMIN role", userId);
            return false;
        } catch (Exception e) {
            log.error("Unexpected error evaluating access to user {}: {}", userId, e.getMessage(), e);
            return false;
        }
    }
}
