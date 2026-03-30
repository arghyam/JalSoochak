package org.arghyam.jalsoochak.tenant.config;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Composed security annotation enforcing tenant-scoped access control.
 *
 * <p>SUPER_USER may access any tenant. STATE_ADMIN may only access their own tenant,
 * verified by matching the {@code tenant_state_code} JWT claim against the tenant record
 * identified by the {@code tenantId} method parameter.
 *
 * <p>Usage:
 * <pre>{@code
 * @RequiresTenantAccess
 * public ResponseEntity<?> myEndpoint(@PathVariable Integer tenantId) { ... }
 * }</pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasRole('SUPER_USER') or (hasRole('STATE_ADMIN') and @tenantSecurity.isOwnTenant(#tenantId))")
public @interface RequiresTenantAccess {
}
