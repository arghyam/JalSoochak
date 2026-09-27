package org.arghyam.jalsoochak.tenant.util;

import lombok.experimental.UtilityClass;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

@UtilityClass
public class SecurityUtils {

    /**
     * Gets the current user's UUID (subject) from the JWT in SecurityContext.
     * Throws if called outside an authenticated request context.
     */
    public static String getCurrentUserUuid() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        throw new AuthenticationCredentialsNotFoundException("getCurrentUserUuid() called outside an authenticated request context");
    }

    /**
     * Gets the current user's tenant state code from the JWT {@code tenant_state_code} claim.
     * Returns {@code null} if the claim is absent (e.g. for SUPER_USER tokens that carry no tenant).
     * Throws if called outside an authenticated request context.
     */
    public static String getCurrentUserTenantStateCode() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("tenant_state_code");
        }
        throw new AuthenticationCredentialsNotFoundException("getCurrentUserTenantStateCode() called outside an authenticated request context");
    }
}