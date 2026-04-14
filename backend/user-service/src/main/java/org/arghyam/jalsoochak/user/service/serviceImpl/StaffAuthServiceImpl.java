package org.arghyam.jalsoochak.user.service.serviceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.clients.KeycloakClient;
import org.arghyam.jalsoochak.user.clients.KeycloakTokenResponse;
import org.arghyam.jalsoochak.user.dto.internal.AuthResult;
import org.arghyam.jalsoochak.user.dto.request.StaffOtpRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.StaffOtpVerifyDTO;
import org.arghyam.jalsoochak.user.dto.response.OtpRequestResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.TokenResponseDTO;
import org.arghyam.jalsoochak.user.enums.OtpType;
import org.arghyam.jalsoochak.user.enums.TenantUserStatus;
import org.arghyam.jalsoochak.user.event.SendLoginOtpEvent;
import org.arghyam.jalsoochak.user.event.UserAnalyticsEventPublisher;
import org.arghyam.jalsoochak.user.event.UserNotificationEventPublisher;
import org.arghyam.jalsoochak.user.config.properties.OtpProperties;
import org.arghyam.jalsoochak.user.exceptions.AccountDeactivatedException;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.repository.TenantUserRecord;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.service.OtpService;
import org.arghyam.jalsoochak.user.service.StaffAuthService;
import org.arghyam.jalsoochak.user.service.StaffKeycloakService;
import org.arghyam.jalsoochak.user.util.TenantAccessValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.Optional;


@Slf4j
@Service
@RequiredArgsConstructor
public class StaffAuthServiceImpl implements StaffAuthService {

    private static final String ROLE_SUPER_USER = "SUPER_USER";
    private static final String ROLE_STATE_ADMIN = "STATE_ADMIN";
    private static final String ROLE_PUMP_OPERATOR = "PUMP_OPERATOR";

    private final UserCommonRepository userCommonRepository;
    private final UserTenantRepository userTenantRepository;
    private final OtpProperties otpProperties;
    private final OtpService otpService;
    private final StaffKeycloakService staffKeycloakService;
    private final KeycloakClient keycloakClient;
    private final UserNotificationEventPublisher eventPublisher;
    private final UserAnalyticsEventPublisher userAnalyticsEventPublisher;
    private final TransactionTemplate transactionTemplate;

    @Override
    @Transactional
    public OtpRequestResponseDTO requestOtp(StaffOtpRequestDTO request) {
        String tenantCode = request.getTenantCode().trim().toUpperCase();
        String phone = request.getPhoneNumber().trim();

        Optional<Integer> tenantIdOpt = userCommonRepository.findTenantIdByStateCode(tenantCode);
        if (tenantIdOpt.isEmpty()) {
            // Log at INFO for production visibility, but without sensitive data
            log.info("OTP_REQUEST_DENIED reason=tenant_not_found");
            return new OtpRequestResponseDTO(otpProperties.otpLength()); // Anti-enumeration: don't reveal whether tenant exists
        }
        int tenantId = tenantIdOpt.get();
        String schema = "tenant_" + tenantCode.toLowerCase();

        // Validate tenant status for staff user access (ACTIVE or DEGRADED only)
        Optional<Integer> tenantStatusOpt = userCommonRepository.findTenantStatusByTenantId(tenantId);
        if (tenantStatusOpt.isEmpty() || !TenantAccessValidator.isAccessibleToStaff(tenantStatusOpt.get())) {
            // Log at INFO for production visibility, but without sensitive data
            log.info("OTP_REQUEST_DENIED reason=tenant_not_accessible");
            return new OtpRequestResponseDTO(otpProperties.otpLength()); // Anti-enumeration: don't reveal whether tenant is accessible
        }

        Optional<TenantUserRecord> userOpt = userTenantRepository.findUserByPhone(schema, phone);
        if (userOpt.isEmpty()) {
            // Log at INFO for production visibility, but without sensitive data
            log.info("OTP_REQUEST_DENIED reason=phone_not_registered");
            return new OtpRequestResponseDTO(otpProperties.otpLength()); // Anti-enumeration: don't reveal whether phone is registered
        }
        TenantUserRecord user = userOpt.get();

        // System users (Super User, State Admin) must use email/password login.
        // This intentionally breaks anti-enumeration for these roles to provide clear UX guidance.
        if (isSystemAdminRole(user.cName())) {
            // Log at WARN for security-relevant event (system user misusing endpoint)
            log.warn("OTP_REQUEST_REJECTED reason=system_user_attempted_staff_login role={}", user.cName());
            throw new BadRequestException(
                    "This login is for staff users. Please sign in with your email and password.");
        }

        // Pump operators do not have OTP login access — silently ignore to preserve anti-enumeration.
        if (ROLE_PUMP_OPERATOR.equalsIgnoreCase(user.cName())) {
            // Log at INFO for production visibility, but without sensitive data
            log.info("OTP_REQUEST_DENIED reason=pump_operator_role_not_eligible");
            return new OtpRequestResponseDTO(otpProperties.otpLength());
        }

        if (user.status() == null || user.status() != TenantUserStatus.ACTIVE.code) {
            // Log at INFO for production visibility, but without sensitive data
            log.info("OTP_REQUEST_DENIED reason=user_inactive");
            return new OtpRequestResponseDTO(otpProperties.otpLength()); // Anti-enumeration
        }

        String rawOtp;
        try {
            rawOtp = otpService.requestOtp(user.id(), tenantId, OtpType.LOGIN);
        } catch (BadRequestException e) {
            // Swallow to preserve anti-enumeration: exposing cooldown/errors would confirm the phone is registered.
            log.debug("OTP request suppressed for staffUserId={} tenantCode={}: {}", user.id(), tenantCode, e.getMessage());
            // Log at INFO for production visibility, but without sensitive data
            log.info("OTP_REQUEST_DENIED reason=cooldown_active");
            return new OtpRequestResponseDTO(otpProperties.otpLength());
        } catch (Exception e) {
            // Log infrastructure errors at ERROR level for alerting, but without sensitive data
            log.error("OTP_REQUEST_FAILED reason=infrastructure_error", e);
            return new OtpRequestResponseDTO(otpProperties.otpLength());
        }

        boolean isWhatsapp = "WHATSAPP".equalsIgnoreCase(otpProperties.deliveryChannel());
        SendLoginOtpEvent event = SendLoginOtpEvent.builder()
                .eventType("SEND_LOGIN_OTP")
                .officerPhoneNumber(user.phoneNumber())
                .glificId(isWhatsapp ? user.whatsappConnectionId() : null)
                .otp(rawOtp)
                .expiryMinutes(otpProperties.expiryMinutes())
                .deliveryChannel(otpProperties.deliveryChannel())
                .build();

        eventPublisher.publishLoginOtpAfterCommit(event, user.id(), tenantCode);
        return new OtpRequestResponseDTO(otpProperties.otpLength());
    }

    private static boolean isSystemAdminRole(String cName) {
        return ROLE_SUPER_USER.equalsIgnoreCase(cName) || ROLE_STATE_ADMIN.equalsIgnoreCase(cName);
    }

    @Override
    public AuthResult verifyOtp(StaffOtpVerifyDTO request) {
        String tenantCode = request.getTenantCode().trim().toUpperCase();
        String schema = "tenant_" + tenantCode.toLowerCase();

        // Resolve tenant and user in a short-lived transaction. OTP verification runs
        // outside the transaction so a Keycloak failure cannot leave the OTP permanently consumed.
        record UserResolution(TenantUserRecord user, int tenantId) {}

        UserResolution resolution = Objects.requireNonNull(transactionTemplate.execute(status -> {
            int tenantId = userCommonRepository.findTenantIdByStateCode(tenantCode)
                    .orElseThrow(() -> new BadRequestException("Invalid or expired OTP"));
            
            // Validate tenant status for staff user access (ACTIVE or DEGRADED only)
            int tenantStatus = userCommonRepository.findTenantStatusByTenantId(tenantId)
                    .orElseThrow(() -> new BadRequestException("Invalid or expired OTP"));
            if (!TenantAccessValidator.isAccessibleToStaff(tenantStatus)) {
                throw new BadRequestException("Invalid or expired OTP");
            }
            
            TenantUserRecord u = userTenantRepository.findUserByPhone(schema, request.getPhoneNumber().trim())
                    .orElseThrow(() -> new BadRequestException("Invalid or expired OTP"));
            return new UserResolution(u, tenantId);
        }));

        TenantUserRecord user = resolution.user();
        int tenantId = resolution.tenantId();

        // Verify OTP before checking account status to avoid leaking whether an account is deactivated.
        // Returns the consumed OTP ID for potential reversion if Keycloak fails.
        Long consumedOtpId = otpService.verifyOtp(user.id(), tenantId, OtpType.LOGIN, request.getOtp());

        // Re-fetch to avoid acting on a stale snapshot: a concurrent deactivation could have changed
        // the status between the initial resolution and now.
        TenantUserRecord freshUser = userTenantRepository.findUserByPhone(schema, request.getPhoneNumber().trim())
                .orElseThrow(() -> new BadRequestException("Invalid or expired OTP"));

        if (freshUser.status() == null || freshUser.status() != TenantUserStatus.ACTIVE.code) {
            throw new AccountDeactivatedException("Account is deactivated");
        }

        // Keycloak provisioning and token exchange run outside the DB transaction.
        // If they fail, revert OTP consumption so the user can retry without requesting a new OTP.
        StaffKeycloakService.ProvisionResult provisionResult = null;
        KeycloakTokenResponse token;
        try {
            // Re-validate tenant status post-OTP consumption to prevent concurrent tenant suspend/archive
            int freshTenantStatus = userCommonRepository.findTenantStatusByTenantId(tenantId)
                    .orElseThrow(() -> new BadRequestException("Invalid or expired OTP"));
            if (!TenantAccessValidator.isAccessibleToStaff(freshTenantStatus)) {
                throw new BadRequestException("Invalid or expired OTP");
            }

            provisionResult = staffKeycloakService.ensureKeycloakAccount(freshUser, tenantCode, schema);
            token = keycloakClient.obtainToken(freshUser.phoneNumber(), provisionResult.managedPassword());
        } catch (RuntimeException e) {
            otpService.revertOtpConsumption(consumedOtpId);
            throw e;
        } finally {
            // Sync the newly-provisioned Keycloak UUID to analytics_schema.dim_user_table.
            // The DB write in updateKeycloakUuidAndPassword is permanent (not rolled back even if the
            // token exchange fails), so we publish here unconditionally whenever a new account was created.
            // keycloakUuid is null on the fast path (existing account), so this is a no-op in that case.
            if (provisionResult != null && provisionResult.keycloakUuid() != null) {
                userAnalyticsEventPublisher.publishUserUpdatedAfterCommit(
                        freshUser.id(),
                        freshUser.tenantId(),
                        freshUser.userTypeId() != null ? freshUser.userTypeId().intValue() : null,
                        provisionResult.keycloakUuid(),
                        freshUser.email(),
                        freshUser.title(),
                        freshUser.status()
                );
            }
        }

        TokenResponseDTO resp = new TokenResponseDTO();
        resp.setAccessToken(token.accessToken());
        resp.setExpiresIn(token.expiresIn());
        resp.setTokenType(token.tokenType());
        resp.setPersonId(freshUser.id());
        resp.setTenantId(String.valueOf(freshUser.tenantId()));
        resp.setTenantCode(tenantCode);
        resp.setRole(freshUser.cName());
        resp.setPhoneNumber(freshUser.phoneNumber());
        resp.setName(freshUser.title());

        log.info("Staff OTP login successful: staffUserId={} tenantCode={}", freshUser.id(), tenantCode);
        return new AuthResult(resp, token.refreshToken(), token.refreshExpiresIn());
    }
}
