package org.arghyam.jalsoochak.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.arghyam.jalsoochak.user.service.StaffKeycloakService.ProvisionResult;

import java.util.Optional;
import java.util.UUID;

import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import org.arghyam.jalsoochak.user.clients.KeycloakClient;
import org.arghyam.jalsoochak.user.clients.KeycloakTokenResponse;
import org.arghyam.jalsoochak.user.config.properties.OtpProperties;
import org.arghyam.jalsoochak.user.dto.internal.AuthResult;
import org.arghyam.jalsoochak.user.dto.request.StaffOtpRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.StaffOtpVerifyDTO;
import org.arghyam.jalsoochak.user.enums.OtpType;
import org.arghyam.jalsoochak.user.enums.TenantUserStatus;
import org.arghyam.jalsoochak.user.event.SendLoginOtpEvent;
import org.arghyam.jalsoochak.user.event.UserAnalyticsEventPublisher;
import org.arghyam.jalsoochak.user.event.UserNotificationEventPublisher;
import org.arghyam.jalsoochak.user.exceptions.AccountDeactivatedException;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.exceptions.CaptchaVerificationException;
import org.arghyam.jalsoochak.user.exceptions.ResourceNotFoundException;
import org.arghyam.jalsoochak.user.service.CaptchaVerificationService;
import org.arghyam.jalsoochak.user.repository.TenantUserRecord;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.service.serviceImpl.StaffAuthServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("StaffAuthServiceImpl")
class StaffAuthServiceImplTest {

    @Mock UserCommonRepository userCommonRepository;
    @Mock UserTenantRepository userTenantRepository;
    @Mock OtpService otpService;
    @Mock StaffKeycloakService staffKeycloakService;
    @Mock KeycloakClient keycloakClient;
    @Mock UserNotificationEventPublisher eventPublisher;
    @Mock UserAnalyticsEventPublisher userAnalyticsEventPublisher;
    @Mock TransactionTemplate transactionTemplate;
    @Mock CaptchaVerificationService captchaVerificationService;

    StaffAuthServiceImpl service;

    private static final TenantUserRecord ACTIVE_USER = new TenantUserRecord(
            10L, 1, "919876543210", "test@test.com", 3L, "SECTION_OFFICER",
            "Test Officer", "kc-uuid", TenantUserStatus.ACTIVE.code, 12345L);

    private static final TenantUserRecord INACTIVE_USER = new TenantUserRecord(
            10L, 1, "919876543210", "test@test.com", 3L, "SECTION_OFFICER",
            "Test Officer", "kc-uuid", TenantUserStatus.INACTIVE.code, null);

    private static final TenantUserRecord SUPER_USER = new TenantUserRecord(
            11L, 1, "919876543211", "super@test.com", 3L, "SUPER_USER",
            "Super Admin", "kc-uuid-super", TenantUserStatus.ACTIVE.code, 12345L);

    private static final TenantUserRecord STATE_ADMIN = new TenantUserRecord(
            12L, 1, "919876543212", "admin@test.com", 3L, "STATE_ADMIN",
            "State Admin", "kc-uuid-admin", TenantUserStatus.ACTIVE.code, 12345L);

    private static final TenantUserRecord PUMP_OPERATOR = new TenantUserRecord(
            13L, 1, "919876543213", "pump@test.com", 3L, "PUMP_OPERATOR",
            "Pump Op", "kc-uuid-pump", TenantUserStatus.ACTIVE.code, 12345L);

    private static final KeycloakTokenResponse TOKEN_RESPONSE =
            new KeycloakTokenResponse("at", "rt", 300, 1800, "Bearer", null, null, "openid");

    @BeforeEach
    void setUp() {
        OtpProperties otpProps = new OtpProperties(10, 5, 60, 6, "WHATSAPP", null);
        service = new StaffAuthServiceImpl(userCommonRepository, userTenantRepository,
                otpProps, otpService, staffKeycloakService, keycloakClient, eventPublisher,
                userAnalyticsEventPublisher, transactionTemplate, captchaVerificationService);
    }

    @Nested
    @DisplayName("requestOtp")
    class RequestOtp {

        StaffOtpRequestDTO request;

        @BeforeEach
        void setUp() {
            request = new StaffOtpRequestDTO();
            request.setPhoneNumber("919876543210");
            request.setTenantCode("mp");
            // requestOtp now runs its DB work inside transactionTemplate.execute(...); make the mock
            // invoke the callback. lenient() because the captcha-failure test short-circuits first.
            lenient().when(transactionTemplate.execute(any())).thenAnswer(inv ->
                    ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        }

        @Test
        @DisplayName("generates and publishes OTP for active user")
        void generatesOtpForActiveUser() {
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543210"))
                    .thenReturn(Optional.of(ACTIVE_USER));
            when(otpService.requestOtp(10L, 1, OtpType.LOGIN)).thenReturn("123456");

            service.requestOtp(request);

            verify(otpService).requestOtp(10L, 1, OtpType.LOGIN);
            verify(eventPublisher).publishLoginOtpAfterCommit(any(SendLoginOtpEvent.class), eq(10L), eq("MP"));
        }

        @Test
        @DisplayName("CAPTCHA failure short-circuits before any DB work or OTP send")
        void captchaFailure_shortCircuits() {
            doThrow(new CaptchaVerificationException("CAPTCHA verification failed"))
                    .when(captchaVerificationService).verify(any(), eq("staff_otp"));

            assertThatThrownBy(() -> service.requestOtp(request))
                    .isInstanceOf(CaptchaVerificationException.class);

            verifyNoInteractions(userCommonRepository, userTenantRepository, otpService, eventPublisher);
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when tenant not found (same response as unregistered phone)")
        void throwsWhenTenantNotFound() {
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.requestOtp(request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("couldn't find an account");

            verify(otpService, never()).requestOtp(any(), any(), any());
            verify(eventPublisher, never()).publishLoginOtpAfterCommit(any(), any(), any());
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when phone not registered (user-friendly re-check guidance)")
        void throwsWhenPhoneNotFound() {
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543210"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.requestOtp(request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("couldn't find an account");

            verify(otpService, never()).requestOtp(any(), any(), any());
        }

        @Test
        @DisplayName("returns silently when user is inactive (anti-enumeration)")
        void silentWhenUserInactive() {
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543210"))
                    .thenReturn(Optional.of(INACTIVE_USER));

            service.requestOtp(request);

            verify(otpService, never()).requestOtp(any(), any(), any());
        }

        @Test
        @DisplayName("returns silently on OTP cooldown (anti-enumeration)")
        void silentOnCooldown() {
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543210"))
                    .thenReturn(Optional.of(ACTIVE_USER));
            doThrow(new BadRequestException("Please wait 50 second(s)"))
                    .when(otpService).requestOtp(10L, 1, OtpType.LOGIN);

            service.requestOtp(request); // must not throw

            verify(eventPublisher, never()).publishLoginOtpAfterCommit(any(), any(), any());
        }

        @Test
        @DisplayName("throws ResourceNotFoundException when tenant is not accessible (same response as unregistered phone)")
        void throwsWhenTenantNotAccessible() {
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(4)); // SUSPENDED

            assertThatThrownBy(() -> service.requestOtp(request))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("couldn't find an account");

            verify(otpService, never()).requestOtp(any(), any(), any());
            verify(eventPublisher, never()).publishLoginOtpAfterCommit(any(), any(), any());
        }

        @Test
        @DisplayName("normalises tenantCode to uppercase before lookup")
        void normalisesToUppercase() {
            request.setTenantCode("mp");
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.requestOtp(request))
                    .isInstanceOf(ResourceNotFoundException.class);

            verify(userCommonRepository).findTenantIdByStateCode("MP");
        }

        @Test
        @DisplayName("throws BadRequestException when phone belongs to SUPER_USER (intentional anti-enumeration break)")
        void throwsWhenSuperUser() {
            request.setPhoneNumber("919876543211");
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543211"))
                    .thenReturn(Optional.of(SUPER_USER));

            assertThatThrownBy(() -> service.requestOtp(request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("This login is for staff users")
                    .hasMessageContaining("email and password");

            verify(otpService, never()).requestOtp(any(), any(), any());
        }

        @Test
        @DisplayName("throws BadRequestException when phone belongs to STATE_ADMIN (intentional anti-enumeration break)")
        void throwsWhenStateAdmin() {
            request.setPhoneNumber("919876543212");
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543212"))
                    .thenReturn(Optional.of(STATE_ADMIN));

            assertThatThrownBy(() -> service.requestOtp(request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("This login is for staff users")
                    .hasMessageContaining("email and password");

            verify(otpService, never()).requestOtp(any(), any(), any());
        }

        @Test
        @DisplayName("returns silently when phone belongs to PUMP_OPERATOR (anti-enumeration preserved)")
        void silentWhenPumpOperator() {
            request.setPhoneNumber("919876543213");
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543213"))
                    .thenReturn(Optional.of(PUMP_OPERATOR));

            service.requestOtp(request); // must not throw

            verify(otpService, never()).requestOtp(any(), any(), any());
            verify(eventPublisher, never()).publishLoginOtpAfterCommit(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("verifyOtp")
    class VerifyOtp {

        StaffOtpVerifyDTO request;

        @BeforeEach
        void setUp() {
            request = new StaffOtpVerifyDTO();
            request.setPhoneNumber("919876543210");
            request.setTenantCode("MP");
            request.setOtp("123456");
            when(transactionTemplate.execute(any())).thenAnswer(inv ->
                    ((org.springframework.transaction.support.TransactionCallback<?>) inv.getArgument(0))
                            .doInTransaction(null));
        }

        @Test
        @DisplayName("returns AuthResult with token on valid OTP (existing Keycloak account — no analytics event)")
        void returnsAuthResultOnSuccess() {
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543210"))
                    .thenReturn(Optional.of(ACTIVE_USER));
            when(otpService.verifyOtp(10L, 1, OtpType.LOGIN, "123456")).thenReturn(99L);
            // Fast path: existing account — keycloakUuid is null
            when(staffKeycloakService.ensureKeycloakAccount(ACTIVE_USER, "MP", "tenant_mp"))
                    .thenReturn(new ProvisionResult("managed-pw", null));
            when(keycloakClient.obtainToken("919876543210", "managed-pw"))
                    .thenReturn(TOKEN_RESPONSE);

            AuthResult result = service.verifyOtp(request);

            assertThat(result.tokenResponse().getAccessToken()).isEqualTo("at");
            assertThat(result.tokenResponse().getTenantCode()).isEqualTo("MP");
            assertThat(result.tokenResponse().getRole()).isEqualTo("SECTION_OFFICER");
            assertThat(result.refreshToken()).isEqualTo("rt");
            verifyNoInteractions(userAnalyticsEventPublisher);
        }

        @Test
        @DisplayName("publishes USER_UPDATED analytics event when new Keycloak account is provisioned")
        void publishesAnalyticsEventOnNewKeycloakAccount() {
            String newKeycloakUuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543210"))
                    .thenReturn(Optional.of(ACTIVE_USER));
            when(otpService.verifyOtp(10L, 1, OtpType.LOGIN, "123456")).thenReturn(99L);
            // Slow path: new account provisioned — keycloakUuid is set
            when(staffKeycloakService.ensureKeycloakAccount(ACTIVE_USER, "MP", "tenant_mp"))
                    .thenReturn(new ProvisionResult("managed-pw", UUID.fromString(newKeycloakUuid)));
            when(keycloakClient.obtainToken("919876543210", "managed-pw"))
                    .thenReturn(TOKEN_RESPONSE);

            service.verifyOtp(request);

            verify(userAnalyticsEventPublisher).publishUserUpdatedAfterCommit(
                    eq(10L),
                    eq(1),
                    eq(3),
                    eq(UUID.fromString(newKeycloakUuid)),
                    eq("test@test.com"),
                    eq("Test Officer"),
                    eq(TenantUserStatus.ACTIVE.code)
            );
        }

        @Test
        @DisplayName("publishes USER_UPDATED analytics event even when token exchange fails")
        void publishesAnalyticsEventEvenOnTokenExchangeFailure() {
            String newKeycloakUuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543210"))
                    .thenReturn(Optional.of(ACTIVE_USER));
            when(otpService.verifyOtp(10L, 1, OtpType.LOGIN, "123456")).thenReturn(99L);
            when(staffKeycloakService.ensureKeycloakAccount(ACTIVE_USER, "MP", "tenant_mp"))
                    .thenReturn(new ProvisionResult("managed-pw", UUID.fromString(newKeycloakUuid)));
            doThrow(new RuntimeException("token exchange failed"))
                    .when(keycloakClient).obtainToken("919876543210", "managed-pw");

            assertThatThrownBy(() -> service.verifyOtp(request))
                    .isInstanceOf(RuntimeException.class);

            // UUID write to DB is permanent — analytics must still be synced
            verify(userAnalyticsEventPublisher).publishUserUpdatedAfterCommit(
                    eq(10L),
                    eq(1),
                    eq(3),
                    eq(UUID.fromString(newKeycloakUuid)),
                    eq("test@test.com"),
                    eq("Test Officer"),
                    eq(TenantUserStatus.ACTIVE.code)
            );
            verify(otpService).revertOtpConsumption(99L);
        }

        @Test
        @DisplayName("throws BadRequestException when tenant is not accessible")
        void throwsWhenTenantNotAccessible() {
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(4)); // SUSPENDED

            assertThatThrownBy(() -> service.verifyOtp(request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid or expired OTP");
        }

        @Test
        @DisplayName("throws BadRequestException when tenant not found")
        void throwsWhenTenantNotFound() {
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.verifyOtp(request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid or expired OTP");
        }

        @Test
        @DisplayName("throws BadRequestException when user not found")
        void throwsWhenUserNotFound() {
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543210"))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.verifyOtp(request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid or expired OTP");
        }

        @Test
        @DisplayName("throws AccountDeactivatedException when user is inactive")
        void throwsWhenUserInactive() {
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543210"))
                    .thenReturn(Optional.of(INACTIVE_USER));
            when(otpService.verifyOtp(10L, 1, OtpType.LOGIN, "123456")).thenReturn(99L);

            assertThatThrownBy(() -> service.verifyOtp(request))
                    .isInstanceOf(AccountDeactivatedException.class);
        }

        @Test
        @DisplayName("reverts OTP consumption when Keycloak provisioning fails")
        void revertsOtpOnKeycloakFailure() {
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543210"))
                    .thenReturn(Optional.of(ACTIVE_USER));
            when(otpService.verifyOtp(10L, 1, OtpType.LOGIN, "123456")).thenReturn(99L);
            doThrow(new RuntimeException("Keycloak unreachable"))
                    .when(staffKeycloakService).ensureKeycloakAccount(ACTIVE_USER, "MP", "tenant_mp");

            assertThatThrownBy(() -> service.verifyOtp(request))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Keycloak unreachable");

            verify(otpService).revertOtpConsumption(99L);
        }

        @Test
        @DisplayName("propagates BadRequestException from OtpService on wrong OTP")
        void propagatesOtpServiceException() {
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543210"))
                    .thenReturn(Optional.of(ACTIVE_USER));
            doThrow(new BadRequestException("Invalid or expired OTP"))
                    .when(otpService).verifyOtp(10L, 1, OtpType.LOGIN, "123456");

            assertThatThrownBy(() -> service.verifyOtp(request))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        @DisplayName("throws AccountDeactivatedException when user is deactivated concurrently after OTP verify")
        void throwsWhenUserDeactivatedConcurrentlyAfterOtpVerify() {
            // Initial lookup (inside transaction) returns active; re-fetch after OTP verify returns inactive
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543210"))
                    .thenReturn(Optional.of(ACTIVE_USER))   // first call — inside transaction
                    .thenReturn(Optional.of(INACTIVE_USER)); // second call — re-fetch after OTP verify
            when(otpService.verifyOtp(10L, 1, OtpType.LOGIN, "123456")).thenReturn(99L);

            assertThatThrownBy(() -> service.verifyOtp(request))
                    .isInstanceOf(AccountDeactivatedException.class);
        }

        @Test
        @DisplayName("throws BadRequestException when tenant is suspended concurrently after OTP verify")
        void throwsWhenTenantSuspendedConcurrentlyAfterOtpVerify() {
            // Initial lookup (inside transaction) returns ACTIVE; re-fetch after OTP verify returns SUSPENDED
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543210"))
                    .thenReturn(Optional.of(ACTIVE_USER));
            when(userCommonRepository.findTenantStatusByTenantId(1))
                    .thenReturn(Optional.of(3))  // first call — inside transaction (ACTIVE)
                    .thenReturn(Optional.of(4)); // second call — post-OTP re-validation (SUSPENDED)
            when(otpService.verifyOtp(10L, 1, OtpType.LOGIN, "123456")).thenReturn(99L);

            assertThatThrownBy(() -> service.verifyOtp(request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid or expired OTP");

            verify(otpService).revertOtpConsumption(99L);
        }

        @Test
        @DisplayName("OTP failure takes precedence over deactivation check (regression: verify ordering)")
        void otpFailureTakesPrecedenceOverDeactivationCheck() {
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543210"))
                    .thenReturn(Optional.of(INACTIVE_USER));
            doThrow(new BadRequestException("Invalid or expired OTP"))
                    .when(otpService).verifyOtp(10L, 1, OtpType.LOGIN, "123456");

            assertThatThrownBy(() -> service.verifyOtp(request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid or expired OTP");

            verify(otpService).verifyOtp(10L, 1, OtpType.LOGIN, "123456");
        }
    }
}
