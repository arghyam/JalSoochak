package org.arghyam.jalsoochak.telemetry.config;

import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Component
public class JwtAuthConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtGrantedAuthoritiesConverter defaultConverter = new JwtGrantedAuthoritiesConverter();

    @Override
    public AbstractAuthenticationToken convert(@NonNull Jwt jwt) {
        Set<GrantedAuthority> authorities = Stream.of(
                        safe(defaultConverter.convert(jwt)),
                        extractUserTypeAuthority(jwt),
                        extractTenantAuthority(jwt),
                        extractRealmRoles(jwt)
                )
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());

        String principalName = jwt.getClaimAsString("preferred_username");
        if (principalName == null || principalName.isBlank()) {
            principalName = jwt.getSubject();
        }
        return new JwtAuthenticationToken(jwt, authorities, principalName);
    }

    private Collection<GrantedAuthority> safe(Collection<GrantedAuthority> auth) {
        return auth == null ? Collections.emptySet() : auth;
    }

    private Collection<GrantedAuthority> extractTenantAuthority(Jwt jwt) {
        String tenantCode = jwt.getClaimAsString("tenant_state_code");
        if (tenantCode == null || tenantCode.isBlank()) {
            return Collections.emptySet();
        }
        return Set.of(new SimpleGrantedAuthority("TENANT_" + tenantCode.toUpperCase()));
    }

    private Collection<GrantedAuthority> extractUserTypeAuthority(Jwt jwt) {
        String userType = jwt.getClaimAsString("user_type");
        if (userType == null || userType.isBlank()) {
            return Collections.emptySet();
        }
        return Set.of(new SimpleGrantedAuthority("USER_TYPE_" + userType.toUpperCase()));
    }

    private Collection<GrantedAuthority> extractRealmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess == null || !(realmAccess.get("roles") instanceof Collection<?> roles)) {
            return Collections.emptySet();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toSet());
    }
}

