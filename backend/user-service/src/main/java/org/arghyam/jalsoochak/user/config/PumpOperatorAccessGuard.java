package org.arghyam.jalsoochak.user.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.enums.TenantUserStatus;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.exceptions.ForbiddenAccessException;
import org.arghyam.jalsoochak.user.exceptions.ResourceNotFoundException;
import org.arghyam.jalsoochak.user.exceptions.UnauthorizedAccessException;
import org.arghyam.jalsoochak.user.repository.PumpOperatorAccessRepository;
import org.arghyam.jalsoochak.user.repository.TenantUserRecord;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.util.SecurityUtils;
import org.arghyam.jalsoochak.user.util.TenantAccessValidator;
import org.arghyam.jalsoochak.user.util.TenantSchemaResolver;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;

/**
 * Access control for the officer-facing {@code /api/v1/pumpoperator/**} endpoints.
 *
 * <p>These endpoints were historically {@code permitAll} and took the tenant as a query
 * parameter, which let anyone on the internet walk sequential {@code pumpOperatorId} values
 * across every tenant and read operator PII (CWE-284, OWASP API1). Two rules close that:
 *
 * <ol>
 *   <li><b>The tenant comes from the token, not the query string.</b> The token's tenant is the
 *       first {@code TENANT_} authority, read by {@link SecurityUtils#extractTenantCode};
 *       {@code JwtAuthConverter} grants that authority from the {@code tenant_state_code} claim.
 *       A caller holding one is pinned to that tenant; supplying a different {@code tenantCode} is
 *       a 403, not a silent cross-tenant read. Only global admins (SUPER_USER /
 *       SUPER_STATE_ADMIN), who hold no {@code TENANT_} authority, may name a tenant.</li>
 *   <li><b>Every object id is checked against the caller's own scope.</b> Tenant admins see
 *       their whole tenant; a staff officer sees only the schemes mapped to them, the pump
 *       operators on those schemes, and their own person record.</li>
 * </ol>
 *
 * <p>Object-scope failures return <b>404, not 403</b>: a 403 would confirm that the id exists,
 * turning the authorization check back into the enumeration oracle it is meant to remove.
 * Tenant and role failures return 403 — the caller already knows their own tenant and role, so
 * there is nothing to leak.
 *
 * <p>Every check fails closed. An unresolvable caller, an unreadable mapping table or an
 * unexpected error denies access.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PumpOperatorAccessGuard {

    /**
     * Pump operators submit readings over WhatsApp and have no dashboard login
     * ({@code StaffAuthServiceImpl} refuses them an OTP). Denying the role here means that even
     * if one ever obtains a token, it cannot be used to read peers' names and phone numbers.
     */
    private static final String ROLE_PUMP_OPERATOR = "PUMP_OPERATOR";

    private static final String NOT_IN_SCOPE_PERSON = "Person not found";
    private static final String NOT_IN_SCOPE_OPERATOR = "Pump operator not found";
    private static final String NOT_IN_SCOPE_SCHEME = "Scheme not found";

    /**
     * Counts scope lookups that threw. Each one denies access, so a broken lookup otherwise just
     * looks like officers suddenly getting 404s — this is the signal to alert on.
     */
    static final String SCOPE_CHECK_FAILURES_METRIC = "user.pumpoperator.scope.check.failures";

    private final UserSecurityEvaluator userSecurity;
    private final UserCommonRepository userCommonRepository;
    private final UserTenantRepository userTenantRepository;
    private final PumpOperatorAccessRepository accessRepository;
    private final MeterRegistry meterRegistry;

    /**
     * The tenant and object scope a request is allowed to operate within.
     *
     * @param tenantCode       effective tenant, resolved from the token (never from the query string
     *                         for tenant-bound callers)
     * @param schemaName       {@code tenant_<code>}, ready for the repositories
     * @param tenantWideAccess {@code true} for tenant admins, who may read any object in the tenant
     * @param callerUserId     the caller's {@code <tenant>.user_table.id}; {@code null} for admins,
     *                         who have no row in the tenant schema
     */
    public record CallerScope(
            String tenantCode,
            String schemaName,
            boolean tenantWideAccess,
            Long callerUserId
    ) {
    }

    /**
     * Resolves the tenant and object scope for the current caller.
     *
     * @param authentication      the authenticated caller; never trusted for tenant selection
     * @param requestedTenantCode the {@code tenantCode} query parameter, honoured only for global
     *                            admins and otherwise required to match the token's tenant
     * @throws UnauthorizedAccessException if there is no authenticated caller (401)
     * @throws ForbiddenAccessException    if the caller may not act in the resolved tenant (403)
     * @throws BadRequestException         if a global admin omits {@code tenantCode} (400)
     */
    public CallerScope resolve(Authentication authentication, String requestedTenantCode) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new UnauthorizedAccessException("Authentication is required");
        }

        Optional<String> role = SecurityUtils.extractRole(authentication);
        boolean globalAdmin = role
                .map(r -> r.equals("SUPER_USER") || r.equals("SUPER_STATE_ADMIN"))
                .orElse(false);
        boolean tenantAdmin = role.map("STATE_ADMIN"::equals).orElse(false);

        String tokenTenantCode = SecurityUtils.extractTenantCode(authentication);
        String effectiveTenantCode = resolveTenantCode(requestedTenantCode, tokenTenantCode, globalAdmin);
        String schemaName = TenantSchemaResolver.requireSchemaNameFromTenantCode(effectiveTenantCode);

        if (globalAdmin || tenantAdmin) {
            // canAccessTenant re-checks that the admin still exists, is ACTIVE, and that the
            // tenant's lifecycle state permits access.
            if (!userSecurity.canAccessTenant(effectiveTenantCode, authentication)) {
                throw new ForbiddenAccessException("Access denied for the requested tenant");
            }
            return new CallerScope(effectiveTenantCode, schemaName, true, null);
        }

        return resolveStaffScope(authentication, effectiveTenantCode, schemaName);
    }

    private String resolveTenantCode(String requestedTenantCode, String tokenTenantCode, boolean globalAdmin) {
        String requested = normalize(requestedTenantCode);
        String fromToken = normalize(tokenTenantCode);

        if (globalAdmin) {
            // Global admins hold no TENANT_ authority, so the query parameter is the only signal.
            String effective = requested != null ? requested : fromToken;
            if (effective == null) {
                throw new BadRequestException("tenantCode is required");
            }
            return effective;
        }

        if (fromToken == null) {
            log.warn("Pump operator API denied: caller has neither an admin role nor a TENANT_ authority");
            throw new ForbiddenAccessException("Access denied for the requested tenant");
        }
        if (requested != null && !requested.equalsIgnoreCase(fromToken)) {
            log.warn("Cross-tenant pump operator request denied: token tenant '{}' does not match requested '{}'",
                    fromToken, requested);
            throw new ForbiddenAccessException("Access denied for the requested tenant");
        }
        return fromToken;
    }

    private CallerScope resolveStaffScope(Authentication authentication, String tenantCode, String schemaName) {
        Integer tenantId = userCommonRepository.findTenantIdByStateCode(tenantCode).orElse(null);
        if (tenantId == null) {
            throw new ForbiddenAccessException("Access denied for the requested tenant");
        }
        Integer tenantStatus = userCommonRepository.findTenantStatusByTenantId(tenantId).orElse(null);
        if (tenantStatus == null || !TenantAccessValidator.isAccessibleToStaff(tenantStatus)) {
            log.warn("Pump operator API denied for tenant '{}': tenant status does not permit staff access", tenantCode);
            throw new ForbiddenAccessException("Access denied for the requested tenant");
        }

        String callerUuid = SecurityUtils.getKeycloakId(authentication);
        TenantUserRecord caller = userTenantRepository.findUserByKeycloakUuid(schemaName, callerUuid).orElse(null);
        if (caller == null || caller.id() == null) {
            log.warn("Pump operator API denied: caller has no user record in tenant '{}'", tenantCode);
            throw new ForbiddenAccessException("Access denied for the requested tenant");
        }
        if (caller.status() == null || caller.status() != TenantUserStatus.ACTIVE.code) {
            log.warn("Pump operator API denied for staffUserId={}: account is not active", caller.id());
            throw new ForbiddenAccessException("Access denied for the requested tenant");
        }
        if (ROLE_PUMP_OPERATOR.equalsIgnoreCase(caller.cName())) {
            log.warn("Pump operator API denied for staffUserId={}: pump operators have no dashboard access", caller.id());
            throw new ForbiddenAccessException("Access denied for the requested tenant");
        }

        return new CallerScope(tenantCode, schemaName, false, caller.id());
    }

    /**
     * Guards the endpoints that read the whole tenant rather than a single object — the
     * unscoped operator list and the all-schemes listing. Only tenant admins may call these;
     * an officer's equivalent view is {@code /person/{personId}/pump-operators}, which is
     * scoped to their own assignments.
     */
    public void requireTenantWideAccess(CallerScope scope) {
        if (!scope.tenantWideAccess()) {
            log.warn("Tenant-wide pump operator listing denied for staffUserId={} in tenant '{}'",
                    scope.callerUserId(), scope.tenantCode());
            throw new ForbiddenAccessException("Tenant administrator access is required for this endpoint");
        }
    }

    /** Allows a staff caller to read only their own person-scoped views. */
    public void requirePersonAccess(CallerScope scope, long personId) {
        if (scope.tenantWideAccess()) {
            return;
        }
        if (scope.callerUserId() != null && scope.callerUserId() == personId) {
            return;
        }
        log.warn("Person access denied for staffUserId={} in tenant '{}'", scope.callerUserId(), scope.tenantCode());
        throw new ResourceNotFoundException(NOT_IN_SCOPE_PERSON);
    }

    /** Allows a staff caller to read a pump operator only on a scheme they are assigned to. */
    public void requirePumpOperatorAccess(CallerScope scope, long pumpOperatorId) {
        if (scope.tenantWideAccess()) {
            return;
        }
        if (scope.callerUserId() != null
                && safely(() -> accessRepository.sharesActiveSchemeWith(
                        scope.schemaName(), scope.callerUserId(), pumpOperatorId))) {
            return;
        }
        log.warn("Pump operator access denied for staffUserId={} in tenant '{}'",
                scope.callerUserId(), scope.tenantCode());
        throw new ResourceNotFoundException(NOT_IN_SCOPE_OPERATOR);
    }

    /** Allows a staff caller to read only the schemes assigned to them. */
    public void requireSchemeAccess(CallerScope scope, long schemeId) {
        if (scope.tenantWideAccess()) {
            return;
        }
        if (scope.callerUserId() != null
                && safely(() -> accessRepository.isMappedToScheme(
                        scope.schemaName(), scope.callerUserId(), schemeId))) {
            return;
        }
        log.warn("Scheme access denied for staffUserId={} in tenant '{}'", scope.callerUserId(), scope.tenantCode());
        throw new ResourceNotFoundException(NOT_IN_SCOPE_SCHEME);
    }

    /**
     * Runs a scope lookup so that an infrastructure failure denies access rather than
     * propagating a 500 that would leave the caller's scope undetermined. Each failure also
     * increments {@value #SCOPE_CHECK_FAILURES_METRIC}.
     */
    private boolean safely(java.util.function.BooleanSupplier check) {
        try {
            return check.getAsBoolean();
        } catch (Exception e) {
            meterRegistry.counter(SCOPE_CHECK_FAILURES_METRIC).increment();
            log.error("Pump operator scope check failed; denying access: {}", e.getMessage(), e);
            return false;
        }
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }
}
