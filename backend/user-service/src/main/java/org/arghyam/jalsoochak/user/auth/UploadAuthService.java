package org.arghyam.jalsoochak.user.auth;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.user.repository.UserUploadRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class UploadAuthService {

    private static final String REQUIRED_UPLOAD_ROLE = "STATE_ADMIN";
    private static final String REQUIRED_SUPER_UPLOAD_ROLE = "SUPER_STATE_ADMIN";

    private final JwtTokenValidator jwtTokenValidator;
    private final UserUploadRepository userUploadRepository;

    public int requireStateAdminUserId(String schemaName, String authorizationHeader) {
        Jwt jwt = requireJwt(authorizationHeader);
        requireStateAdminRole(jwt);
        int userId = requireUserId(schemaName, jwt);
        return userId;
    }

    private void requireStateAdminRole(Jwt jwt) {
        if (!hasRole(jwt, REQUIRED_UPLOAD_ROLE) && !hasRole(jwt, REQUIRED_SUPER_UPLOAD_ROLE)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only " + REQUIRED_UPLOAD_ROLE + " or " + REQUIRED_SUPER_UPLOAD_ROLE + " can upload pump operators"
            );
        }
    }

    @SuppressWarnings("unchecked")
    private boolean hasRole(Jwt jwt, String role) {
        // Realm roles: realm_access.roles
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null) {
            Object rolesObj = realmAccess.get("roles");
            if (rolesObj instanceof List<?> roles) {
                for (Object r : roles) {
                    if (r instanceof String s && s.equals(role)) {
                        return true;
                    }
                }
            }
        }

        // Client roles: resource_access.<client>.roles (search all clients to avoid config dependency here)
        Map<String, Object> resourceAccess = jwt.getClaim("resource_access");
        if (resourceAccess != null) {
            for (Object clientBlock : resourceAccess.values()) {
                if (clientBlock instanceof Map<?, ?> clientMap) {
                    Object rolesObj = clientMap.get("roles");
                    if (rolesObj instanceof List<?> roles) {
                        for (Object r : roles) {
                            if (r instanceof String s && s.equals(role)) {
                                return true;
                            }
                        }
                    }
                }
            }
        }
        return false;
    }

    private int requireUserId(String schemaName, Jwt jwt) {
        String email = jwt.getClaimAsString("email");
        String username = jwt.getClaimAsString("preferred_username");
        if ((email == null || email.isBlank()) && (username == null || username.isBlank())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Token missing user identity (email)");
        }

        Integer userId = userUploadRepository.findUserIdByEmailOrPhone(
                schemaName,
                email != null ? email.trim() : null,
                username != null ? username.trim() : null
        );
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found for token");
        }
        return userId;
    }

    private Jwt requireJwt(String authorizationHeader) {
        String token = extractBearerToken(authorizationHeader);
        try {
            return jwtTokenValidator.decodeAndValidate(token);
        } catch (JwtException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
        }
    }

    private static String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null || authorizationHeader.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing Authorization header");
        }

        String trimmed = authorizationHeader.trim();
        if (trimmed.length() < 7 || !trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authorization header must be: Bearer <token>");
        }

        String token = trimmed.substring(7).trim();
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing bearer token");
        }
        return token;
    }
}
