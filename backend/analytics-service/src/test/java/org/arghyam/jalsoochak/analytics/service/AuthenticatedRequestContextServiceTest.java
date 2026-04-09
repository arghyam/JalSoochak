package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.entity.DimTenant;
import org.arghyam.jalsoochak.analytics.repository.DimTenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthenticatedRequestContextServiceTest {

    @Mock
    private DimTenantRepository dimTenantRepository;

    @InjectMocks
    private AuthenticatedRequestContextService service;

    @Test
    void extractAuthenticatedUserRef_setsTenantIdToZeroWhenTenantStateCodeMissing() {
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(buildJwtWithClaims(claims -> {
            claims.put("user_id", 5);
            // no tenant_state_code claim => SUPER_USER semantics
        }));

        var ref = service.extractAuthenticatedUserRef(authentication);

        assertEquals(5, ref.userId());
        assertEquals(0, ref.tenantId());
        verify(dimTenantRepository, never()).findByStateCode(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void extractAuthenticatedUserRef_resolvesTenantIdFromTenantStateCode() {
        when(dimTenantRepository.findByStateCode("mp"))
                .thenReturn(Optional.of(DimTenant.builder().tenantId(12).stateCode("mp").title("MP").status(1).createdAt(null).build()));

        JwtAuthenticationToken authentication = new JwtAuthenticationToken(buildJwtWithClaims(claims -> {
            claims.put("user_id", 7);
            claims.put("tenant_state_code", "mp");
        }));

        var ref = service.extractAuthenticatedUserRef(authentication);

        assertEquals(7, ref.userId());
        assertEquals(12, ref.tenantId());
        verify(dimTenantRepository).findByStateCode("mp");
    }

    @Test
    void extractAuthenticatedUserRef_throwsWhenTenantStateCodePresentButUnknown() {
        when(dimTenantRepository.findByStateCode("xx")).thenReturn(Optional.empty());

        JwtAuthenticationToken authentication = new JwtAuthenticationToken(buildJwtWithClaims(claims -> {
            claims.put("user_id", 7);
            claims.put("tenant_state_code", "xx");
        }));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.extractAuthenticatedUserRef(authentication));
        assertNotNull(ex.getMessage());
    }

    private static Jwt buildJwtWithClaims(java.util.function.Consumer<java.util.Map<String, Object>> claimsCustomizer) {
        return Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("7")
                .claims(claimsCustomizer)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}

