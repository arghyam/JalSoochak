package org.arghyam.jalsoochak.user.service.serviceImpl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.arghyam.jalsoochak.user.clients.KeycloakClient;
import org.arghyam.jalsoochak.user.clients.KeycloakTokenResponse;
import org.arghyam.jalsoochak.user.config.KeycloakProvider;
import org.arghyam.jalsoochak.user.config.properties.FrontendProperties;
import org.arghyam.jalsoochak.user.config.properties.PasswordResetProperties;
import org.arghyam.jalsoochak.user.dto.internal.AuthResult;
import org.arghyam.jalsoochak.user.dto.request.ActivateAccountRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.ForgotPasswordRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.LoginRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.ResetPasswordRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.InviteInfoResponseDTO;
import org.arghyam.jalsoochak.user.dto.response.TokenResponseDTO;
import org.arghyam.jalsoochak.user.enums.AdminUserStatus;
import org.arghyam.jalsoochak.user.enums.TenantUserStatus;
import org.arghyam.jalsoochak.user.exceptions.AccountDeactivatedException;
import org.arghyam.jalsoochak.user.exceptions.AccountTemporarilyLockedException;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.exceptions.ForbiddenAccessException;
import org.arghyam.jalsoochak.user.exceptions.InvalidCredentialsException;
import org.arghyam.jalsoochak.user.exceptions.KeycloakOperationException;
import org.arghyam.jalsoochak.user.exceptions.ResourceNotFoundException;
import org.arghyam.jalsoochak.user.exceptions.UserAlreadyExistsException;
import org.arghyam.jalsoochak.user.enums.ResourceType;
import org.arghyam.jalsoochak.user.repository.DataVersionRepository;
import org.arghyam.jalsoochak.user.repository.TenantUserRecord;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.repository.records.AdminUserRow;
import org.arghyam.jalsoochak.user.repository.records.AdminUserTokenRow;
import org.arghyam.jalsoochak.user.event.ResetPasswordEmailEvent;
import org.arghyam.jalsoochak.user.event.UserAnalyticsEventPublisher;
import org.arghyam.jalsoochak.user.event.UserNotificationEventPublisher;
import org.arghyam.jalsoochak.user.service.AuthService;
import org.arghyam.jalsoochak.user.service.KeycloakAdminHelper;
import org.arghyam.jalsoochak.user.service.MetadataDecryptionHelper;
import org.arghyam.jalsoochak.user.service.TokenService;
import org.arghyam.jalsoochak.user.enums.TenantAccessRole;
import org.arghyam.jalsoochak.user.util.SecurityUtils;
import org.arghyam.jalsoochak.user.util.TenantAccessValidator;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final KeycloakProvider keycloakProvider;
    private final KeycloakClient keycloakClient;
    private final UserCommonRepository userCommonRepository;
    private final UserTenantRepository userTenantRepository;
    private final UserNotificationEventPublisher userNotificationEventPublisher;
    private final UserAnalyticsEventPublisher userAnalyticsEventPublisher;
    private final KeycloakAdminHelper keycloakAdminHelper;
    private final PasswordResetProperties passwordResetProperties;
    private final FrontendProperties frontendProperties;
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;
    private final MetadataDecryptionHelper metadataDecryptionHelper;
    private final DataVersionRepository dataVersionRepository;

    /** Advisory Retry-After (seconds) sent with a lockout 429. Keep aligned with the realm's brute-force wait. */
    @Value("${security.login.lockout-retry-after-seconds:60}")
    private long lockoutRetryAfterSeconds;

    @Override
    public AuthResult login(LoginRequestDTO request) {
        log.info("login – processing authentication request");
        AdminUserRow user = userCommonRepository.findAdminUserByEmail(request.getEmail())
                .orElseThrow(() -> new InvalidCredentialsException("Invalid credentials"));

        if (user.status() == AdminUserStatus.PENDING) {
            throw new AccountDeactivatedException("Account is not yet activated. Please check your invite email.");
        }
        if (user.status() == AdminUserStatus.INACTIVE) {
            throw new AccountDeactivatedException("Account is deactivated");
        }

        validateTenantStatus(user.tenantId(), TenantAccessRole.fromCName(user.userTypeCName()));

        KeycloakTokenResponse token;
        try {
            token = keycloakClient.obtainToken(request.getEmail(), request.getPassword());
        } catch (ResponseStatusException e) {
            // A locked account returns the same generic 401 as a wrong password, so only the admin
            // attack-detection API can distinguish it. KeycloakClient may also pre-detect lockout as
            // a 429 (for Keycloak setups that send an explicit message). Either way, surface a
            // friendly 429 with Retry-After.
            boolean lockedViaKeycloakBody = e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS;
            boolean lockedViaAttackDetection = e.getStatusCode() == HttpStatus.UNAUTHORIZED
                    && keycloakAdminHelper.isTemporarilyLockedByBruteForce(user.uuid());
            if (lockedViaKeycloakBody || lockedViaAttackDetection) {
                throw new AccountTemporarilyLockedException(lockoutRetryAfterSeconds);
            }
            // Wrong password: normalise to the exact same 401 as an unknown account so the response
            // does not reveal whether the account exists (account-enumeration hardening).
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new InvalidCredentialsException("Invalid credentials");
            }
            throw e;
        }
        return buildEnrichedAuthResult(token, user);
    }

    @Override
    public AuthResult refreshToken(String refreshToken) {
        log.info("refreshToken – processing token refresh");
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BadRequestException("Refresh token must be provided");
        }
        KeycloakTokenResponse token = keycloakClient.refreshToken(refreshToken);
        String sub = SecurityUtils.extractSubFromTrustedKeycloakJwt(token.accessToken());
        String userType = SecurityUtils.extractClaimFromTrustedKeycloakJwt(token.accessToken(), "user_type");

        if (userType != null) {
            return refreshStaffToken(token, sub);
        }

        AdminUserRow user = userCommonRepository.findAdminUserByUuid(sub)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (user.status() == AdminUserStatus.PENDING) {
            throw new AccountDeactivatedException("Account is not yet activated. Please check your invite email.");
        }
        if (user.status() == AdminUserStatus.INACTIVE) {
            throw new AccountDeactivatedException("Account is deactivated");
        }

        validateTenantStatus(user.tenantId(), TenantAccessRole.fromCName(user.userTypeCName()));

        return buildEnrichedAuthResult(token, user);
    }

    private AuthResult refreshStaffToken(KeycloakTokenResponse token, String sub) {
        String tenantCode = SecurityUtils.extractClaimFromTrustedKeycloakJwt(token.accessToken(), "tenant_state_code");
        if (tenantCode == null) {
            throw new ResourceNotFoundException("User not found");
        }
        String schema = "tenant_" + tenantCode.toLowerCase();
        TenantUserRecord user = userTenantRepository.findUserByKeycloakUuid(schema, sub)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (user.status() == null || user.status() != TenantUserStatus.ACTIVE.code) {
            throw new AccountDeactivatedException("Account is deactivated");
        }

        Integer tenantId = userCommonRepository.findTenantIdByStateCode(tenantCode)
                .orElseThrow(() -> new AccountDeactivatedException("Tenant not found or no longer exists."));
        validateTenantStatus(tenantId, TenantAccessRole.STAFF);

        return buildStaffAuthResult(token, user, tenantCode);
    }

    private AuthResult buildStaffAuthResult(KeycloakTokenResponse token, TenantUserRecord user, String tenantCode) {
        TokenResponseDTO resp = buildTokenResponse(token);
        resp.setPersonId(user.id());
        resp.setTenantId(String.valueOf(user.tenantId()));
        resp.setTenantCode(tenantCode);
        resp.setRole(user.cName());
        resp.setPhoneNumber(user.phoneNumber());
        resp.setName(user.title());
        return new AuthResult(resp, token.refreshToken(), token.refreshExpiresIn());
    }

    @Override
    public void logout(String refreshToken) {
        log.info("logout – revoking session");
        keycloakClient.logout(refreshToken);
    }

    @Override
    public InviteInfoResponseDTO getInviteInfo(String inviteToken) {
        String hash = tokenService.hash(inviteToken);
        AdminUserTokenRow tokenRow = userCommonRepository.findActiveTokenByHash(hash)
                .orElseThrow(() -> new BadRequestException("Invite link is invalid or has expired"));

        if (!"INVITE".equals(tokenRow.tokenType())) {
            throw new BadRequestException("Invite link is invalid or has expired");
        }

        String email = tokenRow.email();
        String role = parseMetadata(tokenRow.metadata(), "role");
        String tenantName = parseMetadata(tokenRow.metadata(), "tenantName");
        String firstName = metadataDecryptionHelper.parseAndDecrypt(tokenRow.metadata(), "firstName");
        String lastName = metadataDecryptionHelper.parseAndDecrypt(tokenRow.metadata(), "lastName");

        if (userCommonRepository.existsActiveAdminUserByEmail(email)) {
            throw new UserAlreadyExistsException("Account already exists");
        }

        if (!"SUPER_USER".equals(role)) {
            String tenantCode = parseMetadata(tokenRow.metadata(), "tenantCode");
            Integer tenantId = userCommonRepository.findTenantIdByStateCode(tenantCode)
                    .orElseThrow(() -> new AccountDeactivatedException("Tenant not found or no longer exists."));
            TenantAccessRole accessRole = "STATE_ADMIN".equals(role) || "SUPER_STATE_ADMIN".equals(role)
                    ? TenantAccessRole.STATE_ADMIN : TenantAccessRole.STAFF;
            validateTenantStatus(tenantId, accessRole);
        }

        String phoneNumber = userCommonRepository.findAdminUserByEmail(email)
                .map(AdminUserRow::phoneNumber)
                .orElse(null);

        return new InviteInfoResponseDTO(email, role, tenantName, firstName, lastName, phoneNumber);
    }

    @Override
    @Transactional
    public AuthResult activateAccount(ActivateAccountRequestDTO request) {
        log.info("activateAccount – consuming invite token");
        String hash = tokenService.hash(request.getInviteToken());
        // Atomically validate and consume the token, checking type in the same UPDATE
        AdminUserTokenRow tokenRow = userCommonRepository.consumeActiveTokenOfType(hash, "INVITE")
                .orElseThrow(() -> new BadRequestException("Invite link is invalid, has expired, or has already been used"));

        String email = tokenRow.email();
        String role = parseMetadata(tokenRow.metadata(), "role");
        String tenantCode = parseMetadata(tokenRow.metadata(), "tenantCode");

        // Find the PENDING user created at invite time
        AdminUserRow pendingUser = userCommonRepository.findAdminUserByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Invited user record not found for: " + email));

        if (pendingUser.status() != AdminUserStatus.PENDING) {
            throw new UserAlreadyExistsException("Account already exists");
        }

        Integer tenantId = "SUPER_USER".equals(role) ? 0
                : userCommonRepository.findTenantIdByStateCode(tenantCode)
                        .orElseThrow(() -> new ResourceNotFoundException("Tenant not found for code: " + tenantCode));
        
        // Derive role from token and validate consistency with pendingUser.userTypeCName()
        TenantAccessRole tokenRole = "SUPER_USER".equals(role) ? TenantAccessRole.SUPER_USER
                : "STATE_ADMIN".equals(role) ? TenantAccessRole.STATE_ADMIN
                : "SUPER_STATE_ADMIN".equals(role) ? TenantAccessRole.SUPER_STATE_ADMIN
                : TenantAccessRole.STAFF;
        TenantAccessRole adminLevelRole = TenantAccessRole.fromCName(pendingUser.userTypeCName());
        
        // Validate role consistency: token role must match admin level role
        if (tokenRole != adminLevelRole) {
            throw new BadRequestException("Invite token role does not match user's assigned role");
        }
        
        if (!"SUPER_USER".equals(role)) {
            validateTenantStatus(tenantId, tokenRole);
        }

        String keycloakUuid = null;
        try {
            var usersResource = keycloakProvider.getAdminInstance().realm(keycloakProvider.getRealm()).users();

            UserRepresentation userRep = new UserRepresentation();
            userRep.setUsername(email);
            userRep.setEmail(email);
            userRep.setFirstName(request.getFirstName());
            userRep.setLastName(request.getLastName());
            userRep.setEnabled(true);
            userRep.setEmailVerified(true);

            String extractedUuid;
            try (Response createResponse = usersResource.create(userRep)) {
                if (createResponse.getStatus() != 201) {
                    throw new KeycloakOperationException("Failed to create Keycloak user");
                }
                String location = createResponse.getLocation().toString();
                extractedUuid = location.substring(location.lastIndexOf('/') + 1);
            }
            keycloakUuid = extractedUuid;

            CredentialRepresentation cred = new CredentialRepresentation();
            cred.setType(CredentialRepresentation.PASSWORD);
            cred.setValue(request.getPassword());
            cred.setTemporary(false);
            resetKeycloakPassword(usersResource.get(keycloakUuid), cred);

            keycloakAdminHelper.assignRoleToUser(keycloakUuid, role);

            if ("STATE_ADMIN".equals(role) || "SUPER_STATE_ADMIN".equals(role)) {
                setKeycloakUserAttribute(usersResource, keycloakUuid, "tenant_state_code", tenantCode);
            } else if (!"SUPER_USER".equals(role)) {
                setKeycloakUserAttribute(usersResource, keycloakUuid, "user_type", role);
            }

            // Update the PENDING user record with the real Keycloak UUID and activate it
            userCommonRepository.activatePendingAdminUser(pendingUser.id(), keycloakUuid, request.getPhoneNumber());

            // Publish USER_CREATED analytics event (non-SUPER_USER only — SUPER_USER has no dim_tenant FK row)
            if (!"SUPER_USER".equals(role)) {
                final String finalUuid = keycloakUuid;
                AdminUserRow activatedUser = userCommonRepository.findAdminUserByUuid(finalUuid)
                        .orElseThrow(() -> new IllegalStateException("Activated user not found: " + finalUuid));
                String title = Stream.of(request.getFirstName(), request.getLastName())
                        .filter(s -> s != null && !s.isBlank())
                        .collect(Collectors.joining(" "));
                userAnalyticsEventPublisher.publishUserCreatedAfterCommit(activatedUser, title.isBlank() ? null : title);
            }

            if ("STATE_ADMIN".equals(role) || "SUPER_STATE_ADMIN".equals(role)) {
                String schema = "tenant_" + tenantCode.toLowerCase();
                String title = request.getFirstName() + " " + request.getLastName();
                // Use the same Keycloak UUID so both tables share a single identity key
                userTenantRepository.createUser(schema, keycloakUuid, tenantId, title, email, pendingUser.adminLevel(),
                        request.getPhoneNumber(), "KEYCLOAK_MANAGED", 0L);
                dataVersionRepository.bump(schema, ResourceType.STAFF_USERS);
            }

            log.info("activateAccount – account activated successfully, role={}", role);
            KeycloakTokenResponse token = keycloakClient.obtainToken(email, request.getPassword());
            String tenantStateCode = "SUPER_USER".equals(role) ? null : tenantCode;
            String name = ("STATE_ADMIN".equals(role) || "SUPER_STATE_ADMIN".equals(role))
                    ? request.getFirstName() + " " + request.getLastName()
                    : null;
            Integer resolvedTenantId = "SUPER_USER".equals(role) ? null : tenantId;
            TokenResponseDTO resp = buildTokenResponseEnriched(token, pendingUser.id(), resolvedTenantId,
                    tenantStateCode, role, request.getPhoneNumber(), name);
            return new AuthResult(resp, token.refreshToken(), token.refreshExpiresIn());

        } catch (RuntimeException e) {
            keycloakAdminHelper.deleteUser(keycloakUuid);
            throw e;
        } catch (Exception e) {
            log.error("Account activation failed", e);
            keycloakAdminHelper.deleteUser(keycloakUuid);
            throw new KeycloakOperationException("Account activation failed", e);
        }
    }

    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordRequestDTO request) {
        log.info("forgotPassword – password reset requested");
        var userOpt = userCommonRepository.findAdminUserByEmail(request.getEmail());
        if (userOpt.isEmpty() || userOpt.get().status() == AdminUserStatus.PENDING) {
            return; // OWASP: no email enumeration; also silently skip PENDING users (not yet activated)
        }
        String raw = tokenService.generateRawToken();
        String hash = tokenService.hash(raw);
        Instant expiresAt = Instant.now().plus(passwordResetProperties.expiryMinutes(), ChronoUnit.MINUTES);
        userCommonRepository.insertToken(request.getEmail(), hash, "RESET", null, expiresAt, null);
        String resetUrl = UriComponentsBuilder
                .fromHttpUrl(frontendProperties.baseUrl())
                .path(frontendProperties.resetPath())
                .queryParam("token", raw)
                .build()
                .toUriString();
        userNotificationEventPublisher.publishResetPasswordEmailAfterCommit(ResetPasswordEmailEvent.builder()
                .eventType("SEND_PASSWORD_RESET_EMAIL")
                .to(request.getEmail())
                .resetLink(resetUrl)
                .expiryMinutes(passwordResetProperties.expiryMinutes())
                .build());
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequestDTO request) {
        log.info("resetPassword – consuming reset token");
        String hash = tokenService.hash(request.getToken());
        // Atomically validate, consume and check type in one step
        AdminUserTokenRow tokenRow = userCommonRepository.consumeActiveTokenOfType(hash, "RESET")
                .orElseThrow(() -> new BadRequestException("Reset link is invalid, has expired, or has already been used"));

        AdminUserRow user = userCommonRepository.findAdminUserByEmail(tokenRow.email())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(request.getNewPassword());
        cred.setTemporary(false);
        resetKeycloakPassword(
                keycloakProvider.getAdminInstance().realm(keycloakProvider.getRealm()).users().get(user.uuid()),
                cred);
    }

    private void resetKeycloakPassword(UserResource userResource, CredentialRepresentation cred) {
        try {
            userResource.resetPassword(cred);
        } catch (WebApplicationException wae) {
            Response resp = wae.getResponse();
            if (resp == null) {
                throw new KeycloakOperationException("Failed to reset password: no HTTP response available");
            }
            int status = resp.getStatus();
            if (status == 400) {
                throw new BadRequestException("Password does not meet the required policy");
            }
            throw new KeycloakOperationException("Failed to reset password: HTTP " + status, status);
        }
    }

    private AuthResult buildEnrichedAuthResult(KeycloakTokenResponse token, AdminUserRow user) {
        String roleName = user.userTypeCName();
        String tenantCode = user.tenantId() != 0
                ? userCommonRepository.findTenantStateCodeById(user.tenantId()).orElse(null)
                : null;
        String name = null;
        if (tenantCode != null) {
            String schema = "tenant_" + tenantCode.toLowerCase();
            name = userTenantRepository.findUserByEmail(schema, user.email())
                    .map(TenantUserRecord::title)
                    .orElse(null);
        }
        TokenResponseDTO resp = buildTokenResponseEnriched(token, user.id(), user.tenantId(), tenantCode, roleName,
                user.phoneNumber(), name);
        return new AuthResult(resp, token.refreshToken(), token.refreshExpiresIn());
    }

    private TokenResponseDTO buildTokenResponse(KeycloakTokenResponse token) {
        TokenResponseDTO resp = new TokenResponseDTO();
        resp.setAccessToken(token.accessToken());
        resp.setExpiresIn(token.expiresIn());
        resp.setTokenType(token.tokenType());
        return resp;
    }

    private TokenResponseDTO buildTokenResponseEnriched(KeycloakTokenResponse token,
                                                        Long personId, Integer tenantId, String tenantCode,
                                                        String role, String phoneNumber, String name) {
        TokenResponseDTO resp = buildTokenResponse(token);
        resp.setPersonId(personId);
        resp.setTenantId(tenantId != null && tenantId != 0 ? String.valueOf(tenantId) : null);
        resp.setTenantCode(tenantCode);
        resp.setRole(role);
        resp.setPhoneNumber(phoneNumber);
        resp.setName(name);
        return resp;
    }

    /**
     * Validates tenant status for access control.
     * 
     * <p>tenantId == 0 is the system tenant (SUPER_USER only) — skips validation.
     * For all other tenantIds, validates the tenant status against the user's role.
     * 
     * @param tenantId The tenant ID
     * @param role     The caller's access role
     * @throws AccountDeactivatedException if tenant not found
     * @throws ForbiddenAccessException if the tenant status does not permit access
     */
    private void validateTenantStatus(Integer tenantId, TenantAccessRole role) {
        // System tenant (tenantId == 0) is only accessible to SUPER_USER
        if (tenantId == null || (tenantId == 0 && role == TenantAccessRole.SUPER_USER)) {
            return;
        }
        // For tenantId == 0 with non-SUPER_USER role, treat as invalid
        if (tenantId == 0) {
            throw new AccountDeactivatedException("Tenant not found or no longer exists.");
        }
        Optional<Integer> statusOpt = userCommonRepository.findTenantStatusByTenantId(tenantId);
        if (statusOpt.isEmpty()) {
            throw new AccountDeactivatedException("Tenant not found or no longer exists.");
        }
        int status = statusOpt.get();
        TenantAccessValidator.validateTenantAccess(status, role);
    }

    private String parseMetadata(String json, String key) {
        if (json == null) return null;
        try {
            return objectMapper.readTree(json).path(key).asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private void setKeycloakUserAttribute(
            org.keycloak.admin.client.resource.UsersResource usersResource,
            String keycloakUuid,
            String key,
            String value) {
        var userResource = usersResource.get(keycloakUuid);
        UserRepresentation rep = userResource.toRepresentation();
        Map<String, List<String>> attrs = new HashMap<>(
                rep.getAttributes() != null ? rep.getAttributes() : Map.of());
        if (value == null) {
            attrs.remove(key);
        } else {
            attrs.put(key, List.of(value));
        }
        rep.setAttributes(attrs);
        userResource.update(rep);
    }
}
