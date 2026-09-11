package org.arghyam.jalsoochak.user.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.arghyam.jalsoochak.user.config.PumpOperatorAccessGuard.CallerScope;
import org.arghyam.jalsoochak.user.constants.TenantStatusConstants;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.exceptions.ForbiddenAccessException;
import org.arghyam.jalsoochak.user.exceptions.ResourceNotFoundException;
import org.arghyam.jalsoochak.user.exceptions.UnauthorizedAccessException;
import org.arghyam.jalsoochak.user.repository.PumpOperatorAccessRepository;
import org.arghyam.jalsoochak.user.repository.TenantUserRecord;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PumpOperatorAccessGuard")
class PumpOperatorAccessGuardTest {

    private static final String KEYCLOAK_SUB = "11111111-2222-3333-4444-555555555555";
    private static final long CALLER_ID = 42L;

    @Mock
    private UserSecurityEvaluator userSecurity;

    @Mock
    private UserCommonRepository userCommonRepository;

    @Mock
    private UserTenantRepository userTenantRepository;

    @Mock
    private PumpOperatorAccessRepository accessRepository;

    /** A real registry: a mocked one would hand the guard a null counter on the failure path. */
    @Spy
    private MeterRegistry meterRegistry = new SimpleMeterRegistry();

    @InjectMocks
    private PumpOperatorAccessGuard guard;

    private static Authentication token(String tenantCode, String... roles) {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .subject(KEYCLOAK_SUB)
                .claim("preferred_username", "officer@example.test")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        List<SimpleGrantedAuthority> authorities = Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .toList();
        List<SimpleGrantedAuthority> all = new java.util.ArrayList<>(authorities);
        if (tenantCode != null) {
            all.add(new SimpleGrantedAuthority("TENANT_" + tenantCode));
        }
        return new JwtAuthenticationToken(jwt, all, "officer@example.test");
    }

    private void staffCallerExists(String cName, Integer status) {
        when(userCommonRepository.findTenantIdByStateCode(anyString())).thenReturn(Optional.of(7));
        when(userCommonRepository.findTenantStatusByTenantId(7))
                .thenReturn(Optional.of(TenantStatusConstants.ACTIVE));
        when(userTenantRepository.findUserByKeycloakUuid(anyString(), eq(KEYCLOAK_SUB)))
                .thenReturn(Optional.of(new TenantUserRecord(
                        CALLER_ID, 7, "910000000001", "officer@example.test",
                        3L, cName, "Officer Name", KEYCLOAK_SUB, status, null)));
    }

    @Nested
    @DisplayName("tenant resolution")
    class TenantResolution {

        @Test
        @DisplayName("rejects an unauthenticated caller")
        void rejectsAnonymous() {
            assertThatThrownBy(() -> guard.resolve(null, "AS"))
                    .isInstanceOf(UnauthorizedAccessException.class);
        }

        @Test
        @DisplayName("pins a staff caller to the tenant in their token, ignoring a matching parameter")
        void pinsStaffToTokenTenant() {
            staffCallerExists("SECTION_OFFICER", 1);

            CallerScope scope = guard.resolve(token("AS", "USER_TYPE_SECTION_OFFICER"), "as");

            assertThat(scope.tenantCode()).isEqualTo("AS");
            assertThat(scope.schemaName()).isEqualTo("tenant_as");
            assertThat(scope.tenantWideAccess()).isFalse();
            assertThat(scope.callerUserId()).isEqualTo(CALLER_ID);
        }

        @Test
        @DisplayName("resolves the tenant from the token when no tenantCode parameter is sent")
        void resolvesTenantWithoutParameter() {
            staffCallerExists("SECTION_OFFICER", 1);

            CallerScope scope = guard.resolve(token("AS", "USER_TYPE_SECTION_OFFICER"), null);

            assertThat(scope.tenantCode()).isEqualTo("AS");
        }

        @Test
        @DisplayName("refuses a cross-tenant tenantCode — the reported enumeration vector")
        void refusesCrossTenantParameter() {
            assertThatThrownBy(() -> guard.resolve(token("AS", "USER_TYPE_SECTION_OFFICER"), "UP"))
                    .isInstanceOf(ForbiddenAccessException.class);
            verify(userTenantRepository, never()).findUserByKeycloakUuid(anyString(), anyString());
        }

        @Test
        @DisplayName("refuses a STATE_ADMIN naming another tenant")
        void refusesCrossTenantForStateAdmin() {
            assertThatThrownBy(() -> guard.resolve(token("AS", "ROLE_STATE_ADMIN"), "UP"))
                    .isInstanceOf(ForbiddenAccessException.class);
            verify(userSecurity, never()).canAccessTenant(anyString(), any());
        }

        @Test
        @DisplayName("lets a SUPER_USER name any tenant")
        void allowsSuperUserToNameTenant() {
            when(userSecurity.canAccessTenant(eq("UP"), any())).thenReturn(true);

            CallerScope scope = guard.resolve(token(null, "ROLE_SUPER_USER"), "up");

            assertThat(scope.tenantCode()).isEqualTo("UP");
            assertThat(scope.tenantWideAccess()).isTrue();
            assertThat(scope.callerUserId()).isNull();
        }

        @Test
        @DisplayName("requires tenantCode from a SUPER_USER, who carries no tenant claim")
        void requiresTenantCodeForSuperUser() {
            assertThatThrownBy(() -> guard.resolve(token(null, "ROLE_SUPER_USER"), null))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("denies a caller with neither an admin role nor a tenant claim")
        void deniesTenantlessNonAdmin() {
            assertThatThrownBy(() -> guard.resolve(token(null, "USER_TYPE_SECTION_OFFICER"), "AS"))
                    .isInstanceOf(ForbiddenAccessException.class);
        }

        @Test
        @DisplayName("denies an admin whose tenant access evaluator says no")
        void deniesAdminRejectedByEvaluator() {
            when(userSecurity.canAccessTenant(anyString(), any())).thenReturn(false);

            assertThatThrownBy(() -> guard.resolve(token("AS", "ROLE_STATE_ADMIN"), "AS"))
                    .isInstanceOf(ForbiddenAccessException.class);
        }
    }

    @Nested
    @DisplayName("staff caller resolution")
    class StaffResolution {

        @Test
        @DisplayName("denies a caller with no user row in the tenant schema")
        void deniesUnknownCaller() {
            when(userCommonRepository.findTenantIdByStateCode(anyString())).thenReturn(Optional.of(7));
            when(userCommonRepository.findTenantStatusByTenantId(7))
                    .thenReturn(Optional.of(TenantStatusConstants.ACTIVE));
            when(userTenantRepository.findUserByKeycloakUuid(anyString(), anyString()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> guard.resolve(token("AS", "USER_TYPE_SECTION_OFFICER"), "AS"))
                    .isInstanceOf(ForbiddenAccessException.class);
        }

        @Test
        @DisplayName("denies a deactivated staff account")
        void deniesInactiveCaller() {
            staffCallerExists("SECTION_OFFICER", 0);

            assertThatThrownBy(() -> guard.resolve(token("AS", "USER_TYPE_SECTION_OFFICER"), "AS"))
                    .isInstanceOf(ForbiddenAccessException.class);
        }

        @Test
        @DisplayName("denies a pump operator, who has no dashboard access")
        void deniesPumpOperator() {
            staffCallerExists("PUMP_OPERATOR", 1);

            assertThatThrownBy(() -> guard.resolve(token("AS", "USER_TYPE_PUMP_OPERATOR"), "AS"))
                    .isInstanceOf(ForbiddenAccessException.class);
        }

        @Test
        @DisplayName("denies staff when the tenant lifecycle state bars staff access")
        void deniesSuspendedTenant() {
            when(userCommonRepository.findTenantIdByStateCode(anyString())).thenReturn(Optional.of(7));
            when(userCommonRepository.findTenantStatusByTenantId(7))
                    .thenReturn(Optional.of(TenantStatusConstants.SUSPENDED));

            assertThatThrownBy(() -> guard.resolve(token("AS", "USER_TYPE_SECTION_OFFICER"), "AS"))
                    .isInstanceOf(ForbiddenAccessException.class);
        }
    }

    @Nested
    @DisplayName("object scope")
    class ObjectScope {

        private final CallerScope staff = new CallerScope("AS", "tenant_as", false, CALLER_ID);
        private final CallerScope admin = new CallerScope("AS", "tenant_as", true, null);

        @Test
        @DisplayName("lets a staff caller read only their own person views")
        void personScope() {
            assertThatCode(() -> guard.requirePersonAccess(staff, CALLER_ID)).doesNotThrowAnyException();
            assertThatThrownBy(() -> guard.requirePersonAccess(staff, CALLER_ID + 1))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("lets an admin read any person in the tenant")
        void personScopeForAdmin() {
            assertThatCode(() -> guard.requirePersonAccess(admin, 999L)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("allows a pump operator on a shared scheme and 404s one that is not")
        void pumpOperatorScope() {
            when(accessRepository.sharesActiveSchemeWith("tenant_as", CALLER_ID, 100L)).thenReturn(true);
            when(accessRepository.sharesActiveSchemeWith("tenant_as", CALLER_ID, 200L)).thenReturn(false);

            assertThatCode(() -> guard.requirePumpOperatorAccess(staff, 100L)).doesNotThrowAnyException();
            assertThatThrownBy(() -> guard.requirePumpOperatorAccess(staff, 200L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("returns 404 rather than 403 so the id cannot be probed for existence")
        void outOfScopeIsNotAnExistenceOracle() {
            when(accessRepository.sharesActiveSchemeWith(anyString(), anyLong(), anyLong())).thenReturn(false);

            assertThatThrownBy(() -> guard.requirePumpOperatorAccess(staff, 1L))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .isNotInstanceOf(ForbiddenAccessException.class);
        }

        @Test
        @DisplayName("allows only schemes mapped to the caller")
        void schemeScope() {
            when(accessRepository.isMappedToScheme("tenant_as", CALLER_ID, 5L)).thenReturn(true);
            when(accessRepository.isMappedToScheme("tenant_as", CALLER_ID, 6L)).thenReturn(false);

            assertThatCode(() -> guard.requireSchemeAccess(staff, 5L)).doesNotThrowAnyException();
            assertThatThrownBy(() -> guard.requireSchemeAccess(staff, 6L))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        @DisplayName("denies access when a scope lookup fails rather than surfacing a 500")
        void failsClosedOnLookupError() {
            when(accessRepository.sharesActiveSchemeWith(anyString(), anyLong(), anyLong()))
                    .thenThrow(new RuntimeException("db down"));

            assertThatThrownBy(() -> guard.requirePumpOperatorAccess(staff, 1L))
                    .isInstanceOf(ResourceNotFoundException.class);
            assertThat(meterRegistry.counter(PumpOperatorAccessGuard.SCOPE_CHECK_FAILURES_METRIC).count())
                    .as("a failed lookup must be countable, not only logged")
                    .isEqualTo(1.0);
        }

        @Test
        @DisplayName("restricts the unscoped tenant-wide listings to admins")
        void tenantWideListing() {
            assertThatCode(() -> guard.requireTenantWideAccess(admin)).doesNotThrowAnyException();
            assertThatThrownBy(() -> guard.requireTenantWideAccess(staff))
                    .isInstanceOf(ForbiddenAccessException.class);
        }

        @Test
        @DisplayName("never consults the scope repository for an admin")
        void adminSkipsScopeLookups() {
            guard.requirePumpOperatorAccess(admin, 1L);
            guard.requireSchemeAccess(admin, 1L);

            verify(accessRepository, never()).sharesActiveSchemeWith(anyString(), anyLong(), anyLong());
            verify(accessRepository, never()).isMappedToScheme(anyString(), anyLong(), anyLong());
        }
    }

    @Test
    @DisplayName("rejects a non-JWT authentication rather than trusting it")
    void rejectsNonJwtAuthentication() {
        Authentication basic = new UsernamePasswordAuthenticationToken(
                "someone", "pw", List.of(new SimpleGrantedAuthority("TENANT_AS")));
        when(userCommonRepository.findTenantIdByStateCode(anyString())).thenReturn(Optional.of(7));
        when(userCommonRepository.findTenantStatusByTenantId(7))
                .thenReturn(Optional.of(TenantStatusConstants.ACTIVE));

        assertThatThrownBy(() -> guard.resolve(basic, "AS"))
                .isInstanceOf(UnauthorizedAccessException.class);
    }
}
