package org.arghyam.jalsoochak.user.config;

import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Composed security annotation enforcing tenant-scoped access control for user resources.
 *
 * <p>SUPER_USER may access any user. STATE_ADMIN may only access users in their own tenant,
 * verified by matching the {@code tenant_state_code} JWT claim against the user's tenant.
 *
 * <p>Usage:
 * <pre>{@code
 * @RequiresUserAccess
 * public ResponseEntity<?> myEndpoint(@PathVariable Long id) { ... }
 * }</pre>
 *
 * <p>The method must have a {@code @PathVariable Long id} parameter representing the user ID.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@PreAuthorize("hasAnyRole('SUPER_USER', 'STATE_ADMIN') and @userSecurity.canAccessUser(#id, authentication)")
public @interface RequiresUserAccess {
}
