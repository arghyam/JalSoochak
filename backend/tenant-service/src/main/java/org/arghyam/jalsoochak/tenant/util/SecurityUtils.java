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

    /**
     * Gets the current user's display name from the JWT.
     * Fallback chain: "name" (full name) → "preferred_username" (always present in Keycloak) → subject UUID.
     * Throws if called outside an authenticated request context — callers with a known system identity
     * (schedulers, Kafka consumers) must not use this method.
     */
    public static String getCurrentUserName() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String name = jwt.getClaimAsString("name");
            if (name != null) return name;
            String preferredUsername = jwt.getClaimAsString("preferred_username");
            if (preferredUsername != null) return preferredUsername;
            return jwt.getSubject();
        }
        throw new AuthenticationCredentialsNotFoundException("getCurrentUserName() called outside an authenticated request context");
    }
}