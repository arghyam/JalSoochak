package org.arghyam.jalsoochak.telemetry.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Locale;
import java.util.Set;

/**
 * Maps a Keycloak staff token's {@code user_type} claim to a {@code USER_TYPE_<TYPE>} authority, the
 * same authority scheme-service and analytics-service grant.
 *
 * <p>Deliberately narrower than their converters: the staff routes admit staff by user type alone, so
 * realm roles, client roles and the tenant authority are not read. The tenant comes from the
 * {@code tenant_state_code} claim at the point of use.
 */
public final class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    static final String USER_TYPE_CLAIM = "user_type";
    static final String USER_TYPE_AUTHORITY_PREFIX = "USER_TYPE_";

    @Override
    public AbstractAuthenticationToken convert(@NonNull Jwt jwt) {
        String userType = jwt.getClaimAsString(USER_TYPE_CLAIM);
        Set<GrantedAuthority> authorities = userType == null || userType.isBlank()
                ? Set.of()
                : Set.of(new SimpleGrantedAuthority(
                        USER_TYPE_AUTHORITY_PREFIX + userType.trim().toUpperCase(Locale.ROOT)));
        return new JwtAuthenticationToken(jwt, authorities, jwt.getSubject());
    }
}
