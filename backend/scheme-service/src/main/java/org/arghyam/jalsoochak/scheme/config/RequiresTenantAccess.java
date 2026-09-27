package org.arghyam.jalsoochak.scheme.config;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Composed security annotation enforcing tenant-scoped access control on scheme endpoints.
 *
 * <p>SUPER_USER may reach any tenant. Every other caller — STATE_ADMIN, SUPER_STATE_ADMIN in
 * multi-tenant mode, and Section Officer staff — may only reach the tenant named by their own
 * {@code tenant_state_code} JWT claim. See {@link SchemeSecurityEvaluator} for the full rule.
 *
 * <p>Usage — the method must have a {@code tenantCode} parameter:
 * <pre>{@code
 * @RequiresTenantAccess
 * public ResponseEntity<?> myEndpoint(@RequestParam String tenantCode) { ... }
 * }</pre>
 *
 * <p>Cannot be combined with a second {@code @PreAuthorize} on the same method; where a role check
 * is also needed, write the whole expression inline instead.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("@schemeSecurity.canAccessTenant(#tenantCode, authentication)")
public @interface RequiresTenantAccess {
}
