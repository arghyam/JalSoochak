package org.arghyam.jalsoochak.user.service.serviceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.clients.KeycloakClient;
import org.arghyam.jalsoochak.user.config.KeycloakProvider;
import org.arghyam.jalsoochak.user.config.properties.FrontendProperties;
import org.arghyam.jalsoochak.user.config.properties.InviteProperties;
import org.arghyam.jalsoochak.user.dto.common.PageResponseDTO;
import org.arghyam.jalsoochak.user.dto.request.ChangePasswordRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.InviteRequestDTO;
import org.arghyam.jalsoochak.user.dto.request.UpdateProfileRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.AdminUserResponseDTO;
import org.arghyam.jalsoochak.user.enums.AdminUserStatus;
import org.arghyam.jalsoochak.user.exceptions.BadRequestException;
import org.arghyam.jalsoochak.user.exceptions.ForbiddenAccessException;
import org.arghyam.jalsoochak.user.exceptions.InsufficientActiveUsersException;
import org.arghyam.jalsoochak.user.exceptions.InvalidCredentialsException;
import org.arghyam.jalsoochak.user.exceptions.ResourceNotFoundException;
import org.arghyam.jalsoochak.user.exceptions.UnauthorizedAccessException;
import org.arghyam.jalsoochak.user.exceptions.UserAlreadyExistsException;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.repository.records.AdminUserRow;
import org.arghyam.jalsoochak.user.repository.records.AdminUserTokenRow;
import org.arghyam.jalsoochak.user.event.InviteEmailEvent;
import org.arghyam.jalsoochak.user.event.UserAnalyticsEventPublisher;
import org.arghyam.jalsoochak.user.event.UserNotificationEventPublisher;
import org.arghyam.jalsoochak.user.service.KeycloakAdminHelper;
import org.arghyam.jalsoochak.user.service.MetadataDecryptionHelper;
import org.arghyam.jalsoochak.user.service.PiiEncryptionService;
import org.arghyam.jalsoochak.user.service.TokenService;
import org.arghyam.jalsoochak.user.service.UserManagementService;
import org.arghyam.jalsoochak.user.util.SecurityUtils;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserManagementServiceImpl implements UserManagementService {

    private final KeycloakProvider keycloakProvider;
    private final KeycloakClient keycloakClient;
    private final UserCommonRepository userCommonRepository;
    private final UserTenantRepository userTenantRepository;
    private final UserNotificationEventPublisher userNotificationEventPublisher;
    private final UserAnalyticsEventPublisher userAnalyticsEventPublisher;
    private final KeycloakAdminHelper keycloakAdminHelper;
    private final InviteProperties inviteProperties;
    private final FrontendProperties frontendProperties;
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;
    private final PiiEncryptionService pii;
    private final MetadataDecryptionHelper metadataDecryptionHelper;

    @Override
    @Transactional
    public void inviteUser(InviteRequestDTO request, Authentication caller) {
        log.info("inviteUser called");
        log.debug("inviteUser – role={}, tenantCode={}", request.getRole(), request.getTenantCode());
        String callerUuid = SecurityUtils.getKeycloakId(caller);
        AdminUserRow callerRow = userCommonRepository.findAdminUserByUuid(callerUuid)
                .orElseThrow(() -> new UnauthorizedAccessException("Caller is not registered in the system"));

        String callerRole = callerRow.userTypeCName();
        if (callerRole == null || callerRole.isBlank()) {
            throw new IllegalStateException("Caller user role is missing for user id: " + callerRow.id());
        }

        if ("STATE_ADMIN".equals(callerRole)) {
            if (!"STATE_ADMIN".equals(request.getRole())) {
                throw new ForbiddenAccessException("State admin can only invite state admins");
            }
            String callerTenantCode = SecurityUtils.extractTenantCode(caller);
            if (callerTenantCode == null || !callerTenantCode.equalsIgnoreCase(request.getTenantCode())) {
                throw new ForbiddenAccessException("State admin can only invite within their own state");
            }
        }

        if ("STATE_ADMIN".equals(request.getRole())) {
            if (request.getTenantCode() == null || request.getTenantCode().isBlank()) {
                throw new BadRequestException("tenantCode is required for STATE_ADMIN role");
            }
            if (!userCommonRepository.existsTenantByStateCode(request.getTenantCode())) {
                throw new ResourceNotFoundException("Tenant not found for state code: " + request.getTenantCode());
            }
        }

        var existingUser = userCommonRepository.findAdminUserByEmail(request.getEmail());
        if (existingUser.isPresent()) {
            if (existingUser.get().status() == AdminUserStatus.PENDING) {
                throw new BadRequestException("User already has a pending invitation. Use the reinvite endpoint.");
            }
            throw new UserAlreadyExistsException("User with this email is already registered");
        }

        Integer adminLevelId = userCommonRepository.findUserTypeIdByName(request.getRole())
                .orElseThrow(() -> new BadRequestException("Unknown role: " + request.getRole()));

        Integer tenantId = "SUPER_USER".equals(request.getRole()) ? 0
                : userCommonRepository.findTenantIdByStateCode(request.getTenantCode())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Tenant not found for state code: " + request.getTenantCode()));

        try {
            userCommonRepository.createAdminUserPending(request.getEmail(), request.getPhoneNumber(), tenantId,
                    adminLevelId, callerRow.id() != null ? callerRow.id().intValue() : null);
        } catch (DuplicateKeyException ex) {
            AdminUserRow existing = userCommonRepository.findAdminUserByEmail(request.getEmail())
                    .orElseThrow(() -> ex);
            if (existing.status() == AdminUserStatus.PENDING) {
                throw new BadRequestException("User already has a pending invitation. Use the reinvite endpoint.");
            }
            throw new UserAlreadyExistsException("User with this email is already registered");
        }

        String tenantName = "STATE_ADMIN".equals(request.getRole())
                ? userCommonRepository.findTenantTitleByStateCode(request.getTenantCode()).orElse(null)
                : null;

        Map<String, Object> metaMap = new HashMap<>();
        metaMap.put("role", request.getRole());
        if (request.getTenantCode() != null)
            metaMap.put("tenantCode", request.getTenantCode());
        if (tenantName != null)
            metaMap.put("tenantName", tenantName);
        // Store names encrypted to avoid persisting PII in plaintext in the token table
        metaMap.put("firstName", pii.encrypt(request.getFirstName()));
        metaMap.put("lastName", pii.encrypt(request.getLastName()));
        // Store HMAC of the full name for exact-match search without decrypting PII
        String fullName = Stream.of(request.getFirstName(), request.getLastName())
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" "));
        if (!fullName.isBlank()) {
            metaMap.put("nameHash", pii.hmac(fullName.trim().toLowerCase(Locale.ROOT)));
        }

        String metadataJson;
        try {
            metadataJson = objectMapper.writeValueAsString(metaMap);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize invite metadata", e);
        }

        String raw = tokenService.generateRawToken();
        String hash = tokenService.hash(raw);
        Instant expiresAt = Instant.now().plus(inviteProperties.expiryHours(), ChronoUnit.HOURS);
        userCommonRepository.insertToken(request.getEmail(), hash, "INVITE", metadataJson, expiresAt,
                callerRow.id() != null ? callerRow.id().intValue() : null);

        String inviteUrl = UriComponentsBuilder
                .fromHttpUrl(frontendProperties.baseUrl())
                .path(frontendProperties.invitePath())
                .queryParam("token", raw)
                .build()
                .toUriString();
        String name = Stream.of(request.getFirstName(), request.getLastName())
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(" "));
        if (name.isEmpty())
            name = "User";
        userNotificationEventPublisher.publishInviteEmailAfterCommit(InviteEmailEvent.builder()
                .eventType("SEND_INVITE_EMAIL")
                .to(request.getEmail())
                .name(name)
                .role(request.getRole())
                .inviteLink(inviteUrl)
                .expiryHours(inviteProperties.expiryHours())
                .stateName(tenantName)
                .build());
    }

    @Override
    @Transactional
    public void reinviteUser(Long id, Authentication caller) {
        log.info("reinviteUser called");
        log.debug("reinviteUser – id={}", id);
        AdminUserRow target = userCommonRepository.findAdminUserById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (target.status() != AdminUserStatus.PENDING) {
            throw new BadRequestException("User has already activated their account");
        }

        String callerUuid = SecurityUtils.getKeycloakId(caller);
        AdminUserRow callerRow = userCommonRepository.findAdminUserByUuid(callerUuid)
                .orElseThrow(() -> new UnauthorizedAccessException("Caller is not registered in the system"));

        String callerRole = callerRow.userTypeCName();
        if (callerRole == null || callerRole.isBlank()) {
            throw new IllegalStateException("Caller user role is missing for user id: " + callerRow.id());
        }
        String targetRole = target.userTypeCName();
        if (targetRole == null || targetRole.isBlank()) {
            throw new IllegalStateException("Target user role is missing for user id: " + target.id());
        }

        if ("STATE_ADMIN".equals(callerRole)) {
            if (!"STATE_ADMIN".equals(targetRole)) {
                throw new ForbiddenAccessException("State admin can only reinvite state admins");
            }
            String callerTenantCode = SecurityUtils.extractTenantCode(caller);
            String targetTenantCode = target.tenantId() != 0
                    ? userCommonRepository.findTenantStateCodeById(target.tenantId()).orElse(null)
                    : null;
            if (callerTenantCode == null || !callerTenantCode.equalsIgnoreCase(targetTenantCode)) {
                throw new ForbiddenAccessException("State admin can only reinvite within their own state");
            }
        }

        String tenantCode = target.tenantId() != 0
                ? userCommonRepository.findTenantStateCodeById(target.tenantId()).orElse(null)
                : null;
        String tenantName = tenantCode != null
                ? userCommonRepository.findTenantTitleByStateCode(tenantCode).orElse(null)
                : null;

        // Carry over firstName/lastName from the most recent unconsumed invite token
        // (even if expired)
        Optional<AdminUserTokenRow> existingToken = userCommonRepository.findInviteTokenByEmail(target.email());

        Map<String, Object> metaMap = new HashMap<>();
        metaMap.put("role", targetRole);
        if (tenantCode != null)
            metaMap.put("tenantCode", tenantCode);
        if (tenantName != null)
            metaMap.put("tenantName", tenantName);
        existingToken.ifPresent(t -> {
            String firstName = metadataDecryptionHelper.parseAndDecrypt(t.metadata(), "firstName");
            String lastName = metadataDecryptionHelper.parseAndDecrypt(t.metadata(), "lastName");
            // Re-encrypt before storing in the new token
            if (firstName != null)
                metaMap.put("firstName", pii.encrypt(firstName));
            if (lastName != null)
                metaMap.put("lastName", pii.encrypt(lastName));
            // Recompute nameHash for exact-match search
            String first = firstName != null ? firstName : "";
            String last = lastName != null ? lastName : "";
            String fullName = (first + " " + last).trim();
            if (!fullName.isBlank()) {
                metaMap.put("nameHash", pii.hmac(fullName.toLowerCase(Locale.ROOT)));
            }
        });

        String metadataJson;
        try {
            metadataJson = objectMapper.writeValueAsString(metaMap);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize invite metadata", e);
        }

        String raw = tokenService.generateRawToken();
        String hash = tokenService.hash(raw);
        Instant expiresAt = Instant.now().plus(inviteProperties.expiryHours(), ChronoUnit.HOURS);
        userCommonRepository.insertToken(target.email(), hash, "INVITE", metadataJson, expiresAt,
                callerRow.id() != null ? callerRow.id().intValue() : null);

        String inviteUrl = UriComponentsBuilder
                .fromHttpUrl(frontendProperties.baseUrl())
                .path(frontendProperties.invitePath())
                .queryParam("token", raw)
                .build()
                .toUriString();
        String name = existingToken.map(t -> {
            String fn = metadataDecryptionHelper.parseAndDecrypt(t.metadata(), "firstName");
            String ln = metadataDecryptionHelper.parseAndDecrypt(t.metadata(), "lastName");
            return ((fn != null ? fn : "") + " " + (ln != null ? ln : "")).trim();
        }).orElse(null);
        userNotificationEventPublisher.publishInviteEmailAfterCommit(InviteEmailEvent.builder()
                .eventType("SEND_REINVITE_EMAIL")
                .to(target.email())
                .name(name)
                .role(targetRole)
                .inviteLink(inviteUrl)
                .expiryHours(inviteProperties.expiryHours())
                .stateName(tenantName)
                .build());
    }

    @Override
    public AdminUserResponseDTO getMe(String keycloakId) {
        AdminUserRow user = userCommonRepository.findAdminUserByUuid(keycloakId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return keycloakAdminHelper.buildAdminUserResponse(user);
    }

    @Override
    @Transactional
    public AdminUserResponseDTO updateMe(String keycloakId, UpdateProfileRequestDTO request) {
        log.info("updateMe called");
        log.debug("updateMe – keycloakId={}", keycloakId);
        AdminUserRow user = userCommonRepository.findAdminUserByUuid(keycloakId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (user.status() == AdminUserStatus.INACTIVE) {
            throw new BadRequestException("Cannot update a deactivated user");
        }

        var usersResource = keycloakProvider.getAdminInstance().realm(keycloakProvider.getRealm()).users();
        UserRepresentation rep = usersResource.get(keycloakId).toRepresentation();
        applyNameUpdatesAndSyncProfile(user, request, rep);

        if (request.getPhoneNumber() != null) {
            userCommonRepository.updateAdminUserProfile(user.id(), request.getPhoneNumber(), user.id());
        }

        usersResource.get(keycloakId).update(rep);

        AdminUserRow refreshedUser = userCommonRepository.findAdminUserByUuid(keycloakId)
                .orElseThrow(() -> new IllegalStateException("User record missing after update [uuid=" + keycloakId + "]"));
        userAnalyticsEventPublisher.publishUserUpdatedAfterCommit(refreshedUser);
        return keycloakAdminHelper.buildAdminUserResponse(refreshedUser);
    }

    @Override
    public void changePassword(String keycloakId, ChangePasswordRequestDTO request) {
        log.info("changePassword called");
        log.debug("changePassword – keycloakId={}", keycloakId);
        AdminUserRow user = userCommonRepository.findAdminUserByUuid(keycloakId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        try {
            keycloakClient.obtainToken(user.email(), request.getCurrentPassword());
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throw new InvalidCredentialsException("Current password is incorrect");
            }
            throw e;
        }

        CredentialRepresentation cred = new CredentialRepresentation();
        cred.setType(CredentialRepresentation.PASSWORD);
        cred.setValue(request.getNewPassword());
        cred.setTemporary(false);

        var usersResource = keycloakProvider.getAdminInstance().realm(keycloakProvider.getRealm()).users();
        usersResource.get(keycloakId).resetPassword(cred);
        usersResource.get(keycloakId).logout();
    }

    @Override
    public PageResponseDTO<AdminUserResponseDTO> listSuperUsers(AdminUserStatus status, int page, int limit) {
        long offset = (long) page * limit;
        List<AdminUserRow> rows = userCommonRepository.listSuperUsers(status, offset, limit);
        long total = userCommonRepository.countSuperUsers(status);
        List<AdminUserResponseDTO> users = rows.stream()
                .map(keycloakAdminHelper::buildAdminUserResponse)
                .collect(Collectors.toList());
        return PageResponseDTO.of(users, total, page, limit);
    }

    @Override
    public PageResponseDTO<AdminUserResponseDTO> listStateAdmins(String tenantCode, AdminUserStatus status,
            String name, Authentication caller, int page, int limit) {
        Integer tenantId = null;
        String resolvedStateCode = null;
        Optional<String> callerRole = SecurityUtils.extractRole(caller);

        if (callerRole.map("STATE_ADMIN"::equals).orElse(false)) {
            String callerTenantCode = SecurityUtils.extractTenantCode(caller);
            if (callerTenantCode == null) {
                throw new ForbiddenAccessException("Unable to determine caller's tenant");
            }
            // STATE_ADMIN can only list admins from their own tenant
            if (tenantCode != null && !tenantCode.equalsIgnoreCase(callerTenantCode)) {
                throw new ForbiddenAccessException("State admin can only list admins within their own state");
            }
            tenantId = userCommonRepository.findTenantIdByStateCode(callerTenantCode)
                    .orElseThrow(() -> new ForbiddenAccessException("Caller's tenant not found"));
            resolvedStateCode = callerTenantCode.toUpperCase(Locale.ROOT);
        } else if (tenantCode != null && !tenantCode.isBlank()) {
            // SUPER_USER can optionally filter by tenantCode
            tenantId = userCommonRepository.findTenantIdByStateCode(tenantCode)
                    .orElseThrow(() -> new ResourceNotFoundException("Tenant not found for state code: " + tenantCode));
            resolvedStateCode = tenantCode.toUpperCase(Locale.ROOT);
        }

        Set<String> nameFilterUuids = null;
        if (name != null && !name.isBlank()) {
            String nameHash = pii.hmac(name.trim().toLowerCase(Locale.ROOT));
            nameFilterUuids = new HashSet<>();

            // ACTIVE/INACTIVE: query each tenant's user_table by title_hash
            List<String> schemas = resolvedStateCode != null
                    ? List.of("tenant_" + resolvedStateCode.toLowerCase(Locale.ROOT))
                    : userCommonRepository.findAllTenantStateCodes().stream()
                            .map(c -> "tenant_" + c.toLowerCase(Locale.ROOT))
                            .toList();
            if (resolvedStateCode == null) {
                log.warn("Cross-tenant name search executing sequentially across {} tenant schemas [nameHash={}]; this may be slow at scale",
                        schemas.size(), nameHash);
            }
            for (String schema : schemas) {
                nameFilterUuids.addAll(userTenantRepository.findUuidsByTitleHash(schema, nameHash));
            }

            // PENDING: no tenant user_table row yet — query invite token metadata nameHash
            nameFilterUuids.addAll(userCommonRepository.findPendingAdminUuidsByNameHash(nameHash, tenantId));

            if (nameFilterUuids.isEmpty()) {
                return PageResponseDTO.of(List.of(), 0L, page, limit);
            }
        }

        long offset = (long) page * limit;
        List<AdminUserRow> rows = userCommonRepository.listStateAdminsByTenant(tenantId, status, nameFilterUuids, offset, limit);
        long total = userCommonRepository.countStateAdminsByTenant(tenantId, status, nameFilterUuids);
        List<AdminUserResponseDTO> users = rows.stream()
                .map(keycloakAdminHelper::buildAdminUserResponse)
                .collect(Collectors.toList());
        return PageResponseDTO.of(users, total, page, limit);
    }

    @Override
    public AdminUserResponseDTO getUserById(Long id, Authentication caller) {
        AdminUserRow user = userCommonRepository.findAdminUserById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Optional<String> callerRole = SecurityUtils.extractRole(caller);
        if (callerRole.map("STATE_ADMIN"::equals).orElse(false)) {
            String callerTenantCode = SecurityUtils.extractTenantCode(caller);
            String targetTenantCode = user.tenantId() != 0
                    ? userCommonRepository.findTenantStateCodeById(user.tenantId()).orElse(null)
                    : null;
            if (callerTenantCode == null || !callerTenantCode.equalsIgnoreCase(targetTenantCode)) {
                throw new ForbiddenAccessException("Cannot view user from another state");
            }
        }

        return keycloakAdminHelper.buildAdminUserResponse(user);
    }

    @Override
    @Transactional
    public AdminUserResponseDTO updateUserById(Long id, Authentication caller, UpdateProfileRequestDTO request) {
        log.info("updateUserById called");
        log.debug("updateUserById – id={}", id);
        AdminUserRow user = userCommonRepository.findAdminUserById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Optional<String> callerRole = SecurityUtils.extractRole(caller);
        if (callerRole.map("STATE_ADMIN"::equals).orElse(false)) {
            String callerTenantCode = SecurityUtils.extractTenantCode(caller);
            String targetTenantCode = user.tenantId() != 0
                    ? userCommonRepository.findTenantStateCodeById(user.tenantId()).orElse(null)
                    : null;
            if (callerTenantCode == null || !callerTenantCode.equalsIgnoreCase(targetTenantCode)) {
                throw new ForbiddenAccessException("Cannot update user from another state");
            }
        }

        if (user.status() == AdminUserStatus.PENDING) {
            throw new BadRequestException("Cannot update a user who has not completed registration");
        }
        if (user.status() == AdminUserStatus.INACTIVE) {
            throw new BadRequestException("Cannot update a deactivated user");
        }

        String callerUuid = SecurityUtils.getKeycloakId(caller);
        AdminUserRow callerRow = userCommonRepository.findAdminUserByUuid(callerUuid)
                .orElseThrow(() -> new UnauthorizedAccessException("Caller is not registered in the system"));

        var usersResource = keycloakProvider.getAdminInstance().realm(keycloakProvider.getRealm()).users();
        UserRepresentation rep = usersResource.get(user.uuid()).toRepresentation();
        applyNameUpdatesAndSyncProfile(user, request, rep);

        if (request.getPhoneNumber() != null) {
            userCommonRepository.updateAdminUserProfile(user.id(), request.getPhoneNumber(), callerRow.id());
        }

        usersResource.get(user.uuid()).update(rep);

        AdminUserRow refreshedUser = userCommonRepository.findAdminUserById(id)
                .orElseThrow(() -> new IllegalStateException(
                        "Failed to refresh user after profile update [id=" + id + "]"));
        userAnalyticsEventPublisher.publishUserUpdatedAfterCommit(refreshedUser);
        return keycloakAdminHelper.buildAdminUserResponse(refreshedUser);
    }

    private void applyNameUpdatesAndSyncProfile(AdminUserRow user, UpdateProfileRequestDTO request,
            UserRepresentation rep) {
        if (request.getFirstName() != null && !request.getFirstName().isBlank())
            rep.setFirstName(request.getFirstName());
        if (request.getLastName() != null && !request.getLastName().isBlank())
            rep.setLastName(request.getLastName());

        String roleName = user.userTypeCName();
        if (roleName == null || roleName.isBlank()) {
            throw new IllegalStateException("User role is missing for user id: " + user.id());
        }
        if ("STATE_ADMIN".equals(roleName)
                && (request.getPhoneNumber() != null || request.getFirstName() != null
                        || request.getLastName() != null)) {
            String tenantCode = userCommonRepository.findTenantStateCodeById(user.tenantId()).orElse(null);
            if (tenantCode != null) {
                String schema = "tenant_" + tenantCode.toLowerCase();
                String phoneToSet = request.getPhoneNumber() != null ? request.getPhoneNumber()
                        : userTenantRepository.findUserById(schema, user.id())
                                .map(r -> r.phoneNumber()).orElse(null);
                String fn = rep.getFirstName() != null ? rep.getFirstName() : "";
                String ln = rep.getLastName() != null ? rep.getLastName() : "";
                userTenantRepository.updateUserProfile(schema, user.id(), (fn + " " + ln).trim(), phoneToSet);
            }
        }
    }

    @Override
    @Transactional
    public void deactivateUser(Long id, Authentication caller) {
        log.info("deactivateUser called");
        log.debug("deactivateUser – id={}", id);
        AdminUserRow target = userCommonRepository.findAdminUserById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        String callerUuid = SecurityUtils.getKeycloakId(caller);
        if (callerUuid != null && callerUuid.equals(target.uuid())) {
            throw new ForbiddenAccessException("Cannot deactivate your own account");
        }

        String targetRole = target.userTypeCName();
        if (targetRole == null || targetRole.isBlank()) {
            throw new IllegalStateException("Target user role is missing for user id: " + target.id());
        }
        Optional<String> callerRole = SecurityUtils.extractRole(caller);

        if (callerRole.map("STATE_ADMIN"::equals).orElse(false)) {
            String callerTenantCode = SecurityUtils.extractTenantCode(caller);
            String targetTenantCode = target.tenantId() != 0
                    ? userCommonRepository.findTenantStateCodeById(target.tenantId()).orElse(null)
                    : null;
            if (callerTenantCode == null || !callerTenantCode.equalsIgnoreCase(targetTenantCode)) {
                throw new ForbiddenAccessException("Cannot deactivate user from another state");
            }
        }

        if (target.status() == AdminUserStatus.PENDING) {
            throw new BadRequestException("Cannot deactivate a user who has not completed registration");
        }

        if ("SUPER_USER".equals(targetRole)) {
            if (userCommonRepository.countActiveSuperUsers() <= 1) {
                throw new InsufficientActiveUsersException("At least one active super user must remain");
            }
        } else if ("STATE_ADMIN".equals(targetRole)) {
            if (userCommonRepository.countActiveStateAdminsForTenant(target.tenantId()) <= 1) {
                throw new InsufficientActiveUsersException(
                        "At least one active state admin must remain for this state");
            }
        }

        AdminUserRow callerRow = userCommonRepository.findAdminUserByUuid(callerUuid)
                .orElseThrow(() -> new UnauthorizedAccessException("Caller is not registered in the system"));

        userCommonRepository.deactivateAdminUser(id, callerRow.id());

        AdminUserRow deactivatedUser = userCommonRepository.findAdminUserById(id)
                .orElseThrow(() -> new IllegalStateException("User record missing after deactivation [id=" + id + "]"));
        userAnalyticsEventPublisher.publishUserUpdatedAfterCommit(deactivatedUser);

        var usersResource = keycloakProvider.getAdminInstance().realm(keycloakProvider.getRealm()).users();
        UserRepresentation rep = usersResource.get(target.uuid()).toRepresentation();
        rep.setEnabled(false);
        usersResource.get(target.uuid()).update(rep);
    }

    @Override
    @Transactional
    public void activateUser(Long id, Authentication caller) {
        log.info("activateUser called");
        log.debug("activateUser – id={}", id);
        AdminUserRow target = userCommonRepository.findAdminUserById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (target.status() == AdminUserStatus.PENDING) {
            throw new BadRequestException("Cannot activate a user who has not completed registration");
        }

        String callerUuid = SecurityUtils.getKeycloakId(caller);
        AdminUserRow callerRow = userCommonRepository.findAdminUserByUuid(callerUuid)
                .orElseThrow(() -> new UnauthorizedAccessException("Caller is not registered in the system"));

        Optional<String> callerRole = SecurityUtils.extractRole(caller);
        if (callerRole.map("STATE_ADMIN"::equals).orElse(false)) {
            String callerTenantCode = SecurityUtils.extractTenantCode(caller);
            String targetTenantCode = target.tenantId() != 0
                    ? userCommonRepository.findTenantStateCodeById(target.tenantId()).orElse(null)
                    : null;
            if (callerTenantCode == null || !callerTenantCode.equalsIgnoreCase(targetTenantCode)) {
                throw new ForbiddenAccessException("Cannot activate user from another state");
            }
        }

        userCommonRepository.activateAdminUser(id, callerRow.id());

        AdminUserRow activatedUser = userCommonRepository.findAdminUserById(id)
                .orElseThrow(() -> new IllegalStateException("User record missing after activation [id=" + id + "]"));
        userAnalyticsEventPublisher.publishUserUpdatedAfterCommit(activatedUser);

        var usersResource = keycloakProvider.getAdminInstance().realm(keycloakProvider.getRealm()).users();
        UserRepresentation rep = usersResource.get(target.uuid()).toRepresentation();
        rep.setEnabled(true);
        usersResource.get(target.uuid()).update(rep);
    }
}
