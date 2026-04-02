package org.arghyam.jalsoochak.tenant.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.arghyam.jalsoochak.tenant.enums.TenantAccessRole;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.exception.ForbiddenAccessException;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.arghyam.jalsoochak.tenant.util.SecurityUtils;
import org.arghyam.jalsoochak.tenant.util.TenantAccessValidator;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.stereotype.Component;

/**
 * Security evaluator used in {@code @PreAuthorize} SpEL expressions to enforce
 * object-level (tenant-scoped) authorization for STATE_ADMIN callers.
 *
 * <p>Usage in controllers:
 * <pre>
 * {@code @PreAuthorize("hasRole('SUPER_USER') or (hasRole('STATE_ADMIN') and @tenantSecurity.isOwnTenant(#tenantId))")}
 * </pre>
 *
 * <p>SUPER_USER short-circuits the expression and bypasses the DB lookup entirely.
 * STATE_ADMIN callers are checked against the {@code tenant_state_code} claim in their JWT.
 */
@Component("tenantSecurity")
@RequiredArgsConstructor
@Slf4j
public class TenantSecurityEvaluator {

    private final TenantCommonRepository tenantCommonRepository;

    /**
     * Returns {@code true} if the authenticated STATE_ADMIN's tenant state code matches
     * the state code of the tenant identified by {@code tenantId} and the tenant status
     * permits access.
     *
     * <p>Returns {@code false} (→ 403) when:
     * <ul>
     *   <li>The {@code tenant_state_code} claim is absent from the JWT</li>
     *   <li>No tenant with the given ID exists — intentionally returns 403, not 404,
     *       to prevent STATE_ADMIN callers from probing valid tenant IDs via differing
     *       error codes (OWASP API1 — Broken Object Level Authorization)</li>
     *   <li>The caller's state code does not match the tenant's state code</li>
     *   <li>The tenant status does not permit STATE_ADMIN access (e.g., ARCHIVED)</li>
     * </ul>
     *
     * <p>NOTE: Exceptions must not be thrown from this method. A {@code RuntimeException}
     * thrown inside a {@code @PreAuthorize} SpEL expression is wrapped by Spring in a
     * {@code SpelEvaluationException}, which bypasses domain-specific
     * {@code @ExceptionHandler} mappings and results in an unexpected 500 response.
     */
    public boolean isOwnTenant(Integer tenantId) {
        if (tenantId == null) {
            log.warn("STATE_ADMIN request denied: tenantId is null");
            return false;
        }
        String callerStateCode;
        try {
            callerStateCode = SecurityUtils.getCurrentUserTenantStateCode();
        } catch (AuthenticationCredentialsNotFoundException e) {
            log.warn("STATE_ADMIN request to tenant {} denied: authentication context unavailable", tenantId, e);
            return false;
        }
        if (callerStateCode == null || callerStateCode.isBlank()) {
            log.warn("STATE_ADMIN request to tenant {} denied: tenant_state_code claim absent from JWT", tenantId);
            return false;
        }
        try {
            return tenantCommonRepository.findById(tenantId)
                    .map(tenant -> {
                        // Check if state code matches
                        boolean stateCodeMatches = tenant.getStateCode() != null && callerStateCode.equalsIgnoreCase(tenant.getStateCode());
                        if (!stateCodeMatches) {
                            log.warn("STATE_ADMIN with tenant_state_code='{}' denied access to tenant {} (state_code='{}')",
                                    callerStateCode, tenantId, tenant.getStateCode());
                            return false;
                        }

                        // Check if tenant status permits STATE_ADMIN access
                        try {
                            TenantStatusEnum status = TenantStatusEnum.valueOf(tenant.getStatus());
                            TenantAccessValidator.validateSystemUserAccess(status, TenantAccessRole.STATE_ADMIN);
                            return true;
                        } catch (IllegalArgumentException e) {
                            log.warn("STATE_ADMIN with tenant_state_code='{}' denied access to tenant {}: unrecognised status value '{}'",
                                    callerStateCode, tenantId, tenant.getStatus());
                            return false;
                        } catch (ForbiddenAccessException e) {
                            log.warn("STATE_ADMIN with tenant_state_code='{}' denied access to tenant {} due to status '{}': {}",
                                    callerStateCode, tenantId, tenant.getStatus(), e.getMessage());
                            return false;
                        }
                    })
                    .orElseGet(() -> {
                        log.warn("STATE_ADMIN with tenant_state_code='{}' attempted access to non-existent tenant {}",
                                callerStateCode, tenantId);
                        return false;
                    });
        } catch (Exception e) {
            log.warn("STATE_ADMIN with tenant_state_code='{}' denied access to tenant {}: repository lookup failed",
                    callerStateCode, tenantId, e);
            return false;
        }
    }
}
