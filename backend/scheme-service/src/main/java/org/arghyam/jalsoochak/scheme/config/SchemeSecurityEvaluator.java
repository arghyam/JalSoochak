package org.arghyam.jalsoochak.scheme.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.scheme.repository.SchemeDbRepository;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Security evaluator used in {@code @PreAuthorize} SpEL expressions to enforce tenant-scoped
 * authorization on endpoints that take a caller-supplied {@code tenantCode} or {@code tenantId}.
 *
 * <p>Usage in controllers:
 * <pre>
 * {@code @RequiresTenantAccess}                                                    // #tenantCode
 * {@code @PreAuthorize("@schemeSecurity.canAccessTenantId(#tenantId, authentication)")}
 * </pre>
 *
 * <p>{@link org.arghyam.jalsoochak.scheme.util.TenantSchemaResolver} only validates the
 * <em>shape</em> of a tenant code; on its own it lets any authenticated user address any tenant's
 * schema. Every endpoint that derives a schema from request input therefore needs this evaluator.
 *
 * <p>Access rule — unlike the tenant-service and user-service evaluators, this one is not gated on
 * an admin role. Scheme endpoints are also served to Section Officer staff tokens, so the rule is
 * role-agnostic: any authenticated caller may reach the tenant its own JWT is scoped to.
 * <ul>
 *   <li>{@code ROLE_SUPER_USER} is national in scope, carries no {@code tenant_state_code} claim,
 *       and reaches any tenant.</li>
 *   <li>Every other caller must present a {@code tenant_state_code} claim matching the requested
 *       tenant.</li>
 * </ul>
 *
 * <p>{@code SUPER_STATE_ADMIN} is deliberately not a bypass here. {@link JwtAuthConverter} expands
 * it to {@code ROLE_SUPER_USER} only in Single Tenant Mode, where there is a single tenant to
 * reach; in multi-tenant mode it stays scoped to its own state.
 *
 * <p>NOTE: No method here may throw. A {@code RuntimeException} raised inside a
 * {@code @PreAuthorize} SpEL expression is wrapped by Spring in a {@code SpelEvaluationException},
 * which bypasses {@link org.arghyam.jalsoochak.scheme.exception.GlobalExceptionHandler} and
 * surfaces as 500 instead of 403.
 */
@Component("schemeSecurity")
@RequiredArgsConstructor
@Slf4j
public class SchemeSecurityEvaluator {

    private static final String SUPER_USER_AUTHORITY = "ROLE_SUPER_USER";
    private static final String TENANT_CLAIM = "tenant_state_code";
    private static final String SCHEMA_PREFIX = "tenant_";

    private final SchemeDbRepository schemeDbRepository;

    /**
     * Returns {@code true} if the caller may operate on data scoped to {@code tenantCode}.
     *
     * <p>Returns {@code false} (→ 403) when {@code tenantCode} is blank, the request carries no
     * JWT, the caller has no {@code tenant_state_code} claim, or that claim names another tenant.
     */
    public boolean canAccessTenant(String tenantCode, Authentication authentication) {
        try {
            if (tenantCode == null || tenantCode.isBlank()) {
                log.warn("Tenant access denied: tenantCode is null or blank");
                return false;
            }
            if (isSuperUser(authentication)) {
                return true;
            }
            String callerTenantCode = callerTenantCode(authentication);
            if (callerTenantCode == null) {
                log.warn("Access to tenant '{}' denied: {} claim absent from JWT", tenantCode, TENANT_CLAIM);
                return false;
            }
            if (!callerTenantCode.equalsIgnoreCase(tenantCode.trim())) {
                log.warn("Caller with {}='{}' denied access to tenant '{}'", TENANT_CLAIM, callerTenantCode, tenantCode);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Unexpected error evaluating tenant access for '{}': {}", tenantCode, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Returns {@code true} if the caller may operate on data scoped to the tenant identified by
     * {@code tenantId}. Used by endpoints that address a tenant by its numeric id rather than its
     * state code.
     *
     * <p>An unknown {@code tenantId} intentionally yields {@code false} (→ 403, not 404) so that
     * callers cannot probe for valid tenant ids via differing error codes — OWASP API1, Broken
     * Object Level Authorization.
     */
    public boolean canAccessTenantId(Integer tenantId, Authentication authentication) {
        try {
            if (tenantId == null) {
                log.warn("Tenant access denied: tenantId is null");
                return false;
            }
            if (isSuperUser(authentication)) {
                return true;
            }
            String callerTenantCode = callerTenantCode(authentication);
            if (callerTenantCode == null) {
                log.warn("Access to tenant {} denied: {} claim absent from JWT", tenantId, TENANT_CLAIM);
                return false;
            }
            String targetSchema = schemeDbRepository.findSchemaNameByTenantId(tenantId);
            if (targetSchema == null || targetSchema.isBlank()) {
                log.warn("Caller with {}='{}' attempted access to unknown tenant {}",
                        TENANT_CLAIM, callerTenantCode, tenantId);
                return false;
            }
            if (!targetSchema.equals(schemaOf(callerTenantCode))) {
                log.warn("Caller with {}='{}' denied access to tenant {} (schema '{}')",
                        TENANT_CLAIM, callerTenantCode, tenantId, targetSchema);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Unexpected error evaluating tenant access for id {}: {}", tenantId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Returns {@code true} if the caller is entitled to the already-resolved {@code schemaName}.
     *
     * <p>Exposed for the service layer, which resolves a schema before it can look the caller up.
     * Unlike the {@code canAccess*} methods this is a plain predicate — callers decide how to
     * signal denial, so it is safe to use outside a SpEL expression.
     */
    public static boolean isCallerScopedToSchema(Authentication authentication, String schemaName) {
        if (isSuperUser(authentication)) {
            return true;
        }
        String callerTenantCode = callerTenantCode(authentication);
        return callerTenantCode != null && schemaOf(callerTenantCode).equals(schemaName);
    }

    private static boolean isSuperUser(Authentication authentication) {
        if (authentication == null) {
            return false;
        }
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            if (SUPER_USER_AUTHORITY.equals(authority.getAuthority())) {
                return true;
            }
        }
        return false;
    }

    /** Returns the caller's {@code tenant_state_code} claim, or {@code null} if absent or blank. */
    private static String callerTenantCode(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            return null;
        }
        String tenantCode = jwtAuth.getToken().getClaimAsString(TENANT_CLAIM);
        if (tenantCode == null || tenantCode.isBlank()) {
            return null;
        }
        return tenantCode.trim();
    }

    /** Mirrors {@code TenantSchemaResolver}'s mapping without its throwing format validation. */
    private static String schemaOf(String tenantCode) {
        return SCHEMA_PREFIX + tenantCode.toLowerCase(Locale.ROOT);
    }
}
