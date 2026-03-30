package org.arghyam.jalsoochak.tenant.config;

import static org.junit.Assert.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.arghyam.jalsoochak.tenant.dto.response.TenantResponseDTO;
import org.arghyam.jalsoochak.tenant.repository.TenantCommonRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantSecurityEvaluator")
class TenantSecurityEvaluatorTest {

    @Mock
    private TenantCommonRepository tenantCommonRepository;

    @InjectMocks
    private TenantSecurityEvaluator tenantSecurityEvaluator;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void setUpJwtContext(String tenantStateCode) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-uuid");
        if (tenantStateCode != null) {
            builder.claim("tenant_state_code", tenantStateCode);
        }
        Jwt jwt = builder.build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    // ── isOwnTenant: matching tenant ─────────────────────────────────────────

    @Test
    @DisplayName("Returns true when caller state code matches tenant state code")
    void isOwnTenant_matchingStateCode_returnsTrue() {
        setUpJwtContext("mp");
        when(tenantCommonRepository.findById(1))
                .thenReturn(Optional.of(TenantResponseDTO.builder().stateCode("mp").build()));

        assertTrue(tenantSecurityEvaluator.isOwnTenant(1));
    }

    @Test
    @DisplayName("Comparison is case-insensitive")
    void isOwnTenant_caseInsensitiveMatch_returnsTrue() {
        setUpJwtContext("MP");
        when(tenantCommonRepository.findById(1))
                .thenReturn(Optional.of(TenantResponseDTO.builder().stateCode("mp").build()));

        assertTrue(tenantSecurityEvaluator.isOwnTenant(1));
    }

    // ── isOwnTenant: non-matching tenant ─────────────────────────────────────

    @Test
    @DisplayName("Returns false when caller state code does not match tenant state code")
    void isOwnTenant_mismatchedStateCode_returnsFalse() {
        setUpJwtContext("mp");
        when(tenantCommonRepository.findById(2))
                .thenReturn(Optional.of(TenantResponseDTO.builder().stateCode("tr").build()));

        assertFalse(tenantSecurityEvaluator.isOwnTenant(2));
    }

    // ── isOwnTenant: missing JWT claim ───────────────────────────────────────

    @Test
    @DisplayName("Returns false when tenant_state_code claim is absent from JWT")
    void isOwnTenant_noTenantClaimInJwt_returnsFalse() {
        setUpJwtContext(null);

        assertFalse(tenantSecurityEvaluator.isOwnTenant(1));
        verify(tenantCommonRepository, never()).findById(1);
    }

    @Test
    @DisplayName("Returns false when tenant_state_code claim is blank")
    void isOwnTenant_blankTenantClaim_returnsFalse() {
        setUpJwtContext("   ");

        assertFalse(tenantSecurityEvaluator.isOwnTenant(1));
        verify(tenantCommonRepository, never()).findById(1);
    }

    // ── isOwnTenant: tenant not found in DB ──────────────────────────────────

    @Test
    @DisplayName("Returns false when tenant ID does not exist — prevents probing via 404 vs 403")
    void isOwnTenant_tenantNotFound_returnsFalse() {
        setUpJwtContext("mp");
        when(tenantCommonRepository.findById(99)).thenReturn(Optional.empty());

        assertFalse(tenantSecurityEvaluator.isOwnTenant(99));
    }

    // ── isOwnTenant: no security context ─────────────────────────────────────

    @Test
    @DisplayName("Throws AuthenticationCredentialsNotFoundException when called outside auth context")
    void isOwnTenant_noSecurityContext_throwsAuthException() {
        // SecurityContextHolder is cleared by @AfterEach; no auth set up here
        assertThrows(AuthenticationCredentialsNotFoundException.class,
                () -> tenantSecurityEvaluator.isOwnTenant(1));
        verify(tenantCommonRepository, never()).findById(1);
    }
}
