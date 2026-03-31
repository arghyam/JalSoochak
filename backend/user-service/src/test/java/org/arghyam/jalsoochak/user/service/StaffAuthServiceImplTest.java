package org.arghyam.jalsoochak.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

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
import org.arghyam.jalsoochak.user.event.UserNotificationEventPublisher;
import org.arghyam.jalsoochak.user.exceptions.AccountDeactivatedException;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
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
    @Mock TransactionTemplate transactionTemplate;

    StaffAuthServiceImpl service;

    private static final TenantUserRecord ACTIVE_USER = new TenantUserRecord(
            10L, 1, "919876543210", "test@test.com", 3L, "SECTION_OFFICER",
            "Test Officer", "kc-uuid", TenantUserStatus.ACTIVE.code, 12345L);

    private static final TenantUserRecord INACTIVE_USER = new TenantUserRecord(
            10L, 1, "919876543210", "test@test.com", 3L, "SECTION_OFFICER",
            "Test Officer", "kc-uuid", TenantUserStatus.INACTIVE.code, null);

    private static final KeycloakTokenResponse TOKEN_RESPONSE =
            new KeycloakTokenResponse("at", "rt", 300, 1800, "Bearer", null, null, "openid");

    @BeforeEach
    void setUp() {
        OtpProperties otpProps = new OtpProperties(10, 5, 60, 6, "WHATSAPP");
        service = new StaffAuthServiceImpl(userCommonRepository, userTenantRepository,
                otpProps, otpService, staffKeycloakService, keycloakClient, eventPublisher, transactionTemplate);
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
            verify(eventPublisher).publishLoginOtpAfterCommit(any(SendLoginOtpEvent.class));
        }

        @Test
        @DisplayName("returns silently when tenant not found (anti-enumeration)")
        void silentWhenTenantNotFound() {
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.empty());

            service.requestOtp(request); // must not throw

            verify(otpService, never()).requestOtp(any(), any(), any());
            verify(eventPublisher, never()).publishLoginOtpAfterCommit(any());
        }

        @Test
        @DisplayName("returns silently when phone not registered (anti-enumeration)")
        void silentWhenPhoneNotFound() {
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543210"))
                    .thenReturn(Optional.empty());

            service.requestOtp(request);

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

            verify(eventPublisher, never()).publishLoginOtpAfterCommit(any());
        }

        @Test
        @DisplayName("normalises tenantCode to uppercase before lookup")
        void normalisesToUppercase() {
            request.setTenantCode("mp");
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.empty());

            service.requestOtp(request);

            verify(userCommonRepository).findTenantIdByStateCode("MP");
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
        @DisplayName("returns AuthResult with token on valid OTP")
        void returnsAuthResultOnSuccess() {
            when(userCommonRepository.findTenantIdByStateCode("MP")).thenReturn(Optional.of(1));
            when(userCommonRepository.findTenantStatusByTenantId(1)).thenReturn(Optional.of(3)); // ACTIVE
            when(userTenantRepository.findUserByPhone("tenant_mp", "919876543210"))
                    .thenReturn(Optional.of(ACTIVE_USER));
            when(otpService.verifyOtp(10L, 1, OtpType.LOGIN, "123456")).thenReturn(99L);
            when(staffKeycloakService.ensureKeycloakAccount(ACTIVE_USER, "MP", "tenant_mp"))
                    .thenReturn("managed-pw");
            when(keycloakClient.obtainToken("919876543210", "managed-pw"))
                    .thenReturn(TOKEN_RESPONSE);

            AuthResult result = service.verifyOtp(request);

            assertThat(result.tokenResponse().getAccessToken()).isEqualTo("at");
            assertThat(result.tokenResponse().getTenantCode()).isEqualTo("MP");
            assertThat(result.tokenResponse().getRole()).isEqualTo("SECTION_OFFICER");
            assertThat(result.refreshToken()).isEqualTo("rt");
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
