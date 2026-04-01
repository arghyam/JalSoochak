package org.arghyam.jalsoochak.user.service.serviceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.clients.KeycloakClient;
import org.arghyam.jalsoochak.user.clients.KeycloakTokenResponse;
import org.arghyam.jalsoochak.user.dto.internal.AuthResult;
import org.arghyam.jalsoochak.user.dto.request.StaffOtpRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.StaffOtpVerifyDTO;
import org.arghyam.jalsoochak.user.dto.response.TokenResponseDTO;
import org.arghyam.jalsoochak.user.enums.OtpType;
import org.arghyam.jalsoochak.user.enums.TenantUserStatus;
import org.arghyam.jalsoochak.user.event.SendLoginOtpEvent;
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

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;


@Slf4j
@Service
@RequiredArgsConstructor
public class StaffAuthServiceImpl implements StaffAuthService {

    private final UserCommonRepository userCommonRepository;
    private final UserTenantRepository userTenantRepository;
    private final OtpProperties otpProperties;
    private final OtpService otpService;
    private final StaffKeycloakService staffKeycloakService;
    private final KeycloakClient keycloakClient;
    private final UserNotificationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    @Override
    @Transactional
    public void requestOtp(StaffOtpRequestDTO request) {
        String tenantCode = request.getTenantCode().trim().toUpperCase();

        Optional<Integer> tenantIdOpt = userCommonRepository.findTenantIdByStateCode(tenantCode);
        if (tenantIdOpt.isEmpty()) {
            return; // Anti-enumeration: don't reveal whether tenant exists
        }
        int tenantId = tenantIdOpt.get();
        String schema = "tenant_" + tenantCode.toLowerCase();

        // Validate tenant status for staff user access (ACTIVE or DEGRADED only)
        Optional<Integer> tenantStatusOpt = userCommonRepository.findTenantStatusByTenantId(tenantId);
        if (tenantStatusOpt.isEmpty() || !TenantAccessValidator.isAccessibleToStaff(tenantStatusOpt.get())) {
            return; // Anti-enumeration: don't reveal whether tenant is accessible
        }

        Optional<TenantUserRecord> userOpt =
                userTenantRepository.findUserByPhone(schema, request.getPhoneNumber().trim());
        if (userOpt.isEmpty()) {
            return; // Anti-enumeration: don't reveal whether phone is registered
        }
        TenantUserRecord user = userOpt.get();

        if (user.status() == null || user.status() != TenantUserStatus.ACTIVE.code) {
            return; // Anti-enumeration: don't reveal whether account is inactive
        }

        String rawOtp;
        try {
            rawOtp = otpService.requestOtp(user.id(), tenantId, OtpType.LOGIN);
        } catch (BadRequestException e) {
            // Swallow to preserve anti-enumeration: exposing cooldown/errors would confirm the phone is registered.
            log.debug("OTP request suppressed for staffUserId={} tenantCode={}: {}", user.id(), tenantCode, e.getMessage());
            return;
        } catch (Exception e) {
            log.error("OTP infrastructure failure for staffUserId={} tenantCode={}", user.id(), tenantCode, e);
            return;
        }

        String deliveryChannel = otpProperties.deliveryChannel();

        SendLoginOtpEvent event = SendLoginOtpEvent.builder()
                .eventType("SEND_LOGIN_OTP")
                .tenantCode(tenantCode)
                .tenantId(tenantId)
                .triggeredAt(Instant.now().toString())
                .glificId(user.whatsappConnectionId())
                .officerName(user.title())
                .officerPhoneNumber(user.phoneNumber())
                .otp(rawOtp)
                .deliveryChannel(deliveryChannel)
                .build();

        eventPublisher.publishLoginOtpAfterCommit(event);
        log.info("OTP requested for staffUserId={} tenantCode={}", user.id(), tenantCode);
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
        String managedPassword;
        KeycloakTokenResponse token;
        try {
            // Re-validate tenant status post-OTP consumption to prevent concurrent tenant suspend/archive
            int freshTenantStatus = userCommonRepository.findTenantStatusByTenantId(tenantId)
                    .orElseThrow(() -> new BadRequestException("Invalid or expired OTP"));
            if (!TenantAccessValidator.isAccessibleToStaff(freshTenantStatus)) {
                throw new BadRequestException("Invalid or expired OTP");
            }
            
            managedPassword = staffKeycloakService.ensureKeycloakAccount(freshUser, tenantCode, schema);
            token = keycloakClient.obtainToken(freshUser.phoneNumber(), managedPassword);
        } catch (RuntimeException e) {
            otpService.revertOtpConsumption(consumedOtpId);
            throw e;
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
