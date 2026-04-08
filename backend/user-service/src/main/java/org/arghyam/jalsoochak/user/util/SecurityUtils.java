package org.arghyam.jalsoochak.user.util;

import lombok.experimental.UtilityClass;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.exceptions.UnauthorizedAccessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@UtilityClass
public class SecurityUtils {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Extracts the Keycloak user UUID (JWT 'sub' claim) from the Authentication.
     * IMPORTANT: auth.getName() returns 'preferred_username' (email), NOT the UUID.
     */
    public static String getKeycloakId(Authentication auth) {
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            return jwtAuth.getToken().getSubject();
        }
        throw new UnauthorizedAccessException("No valid JWT authentication found");
    }

    /**
     * Extracts the tenant state code from the TENANT_XX authority in the JWT.
     * Returns null if the caller has no tenant authority (e.g. SUPER_USER).
     */
    public static String extractTenantCode(Authentication auth) {
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("TENANT_"))
                .map(a -> a.substring("TENANT_".length()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Extracts the 'sub' claim (Keycloak UUID) from a raw JWT string without signature verification.
     * Only use this for tokens freshly obtained from Keycloak — never for untrusted input.
     */
    public static String extractSubFromTrustedKeycloakJwt(String accessToken) {
        try {
            String payload = accessToken.split("\\.")[1];
            String decoded = new String(Base64.getUrlDecoder().decode(payload));
            String sub = MAPPER.readTree(decoded).path("sub").asText(null);
            if (sub == null || sub.isEmpty()) {
                throw new BadRequestException("Could not parse access token: missing sub claim");
            }
            return sub;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Could not parse access token");
        }
    }

    /**
     * Extracts an arbitrary string claim from a raw JWT string without signature verification.
     * Returns {@code null} if the claim is absent or blank.
     * Only use this for tokens freshly obtained from Keycloak — never for untrusted input.
     */
    public static String extractClaimFromTrustedKeycloakJwt(String accessToken, String claimName) {
        try {
            String payload = accessToken.split("\\.")[1];
            String decoded = new String(Base64.getUrlDecoder().decode(payload));
            String value = MAPPER.readTree(decoded).path(claimName).asText(null);
            return (value == null || value.isBlank()) ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the highest-priority role name (SUPER_STATE_ADMIN, SUPER_USER, or STATE_ADMIN)
     * from the ROLE_XX authorities. Returns an empty Optional if no matching role is found.
     */
    public static Optional<String> extractRole(Authentication auth) {
        Set<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .filter(r -> r.equals("SUPER_USER") || r.equals("STATE_ADMIN") || r.equals("SUPER_STATE_ADMIN"))
                .collect(Collectors.toSet());
        return List.of("SUPER_STATE_ADMIN", "SUPER_USER", "STATE_ADMIN").stream()
                .filter(roles::contains)
                .findFirst();
    }
}
