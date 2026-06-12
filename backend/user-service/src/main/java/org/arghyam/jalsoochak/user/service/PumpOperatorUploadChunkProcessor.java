package org.arghyam.jalsoochak.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.enums.ResourceType;
import org.arghyam.jalsoochak.user.enums.TenantUserStatus;
import org.arghyam.jalsoochak.user.event.UserAnalyticsEventPublisher;
import org.arghyam.jalsoochak.user.event.UserEventPublisher;
import org.arghyam.jalsoochak.user.repository.DataVersionRepository;
import org.arghyam.jalsoochak.user.repository.TenantUserRecord;
import org.arghyam.jalsoochak.user.repository.UserSchemeMappingCreateRow;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.repository.UserUploadRepository;
import org.arghyam.jalsoochak.user.util.PhoneNumberUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Processes uploads in small transactions so very large CSVs don't create a single massive transaction
 * or require materializing everything in memory.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PumpOperatorUploadChunkProcessor {

    private final UserTenantRepository userTenantRepository;
    private final UserUploadRepository userUploadRepository;
    private final UserEventPublisher userEventPublisher;
    private final UserAnalyticsEventPublisher userAnalyticsEventPublisher;
    private final StaffKeycloakService staffKeycloakService;
    private final DataVersionRepository dataVersionRepository;

    public record UploadRow(
            int rowNumber,
            String firstName,
            String lastName,
            String fullName,
            String phone,
            String personType,
            String stateSchemeId
    ) {}

    public record ChunkResult(int uploadedRows, int skippedRows, int unchangedRows) {}

    private record UploadUserSnapshot(
            Long userId,
            Integer tenantId,
            Integer userType,
            UUID uuid,
            String email,
            String title,
            Integer status
    ) {}

    @Transactional
    public ChunkResult processChunk(
            String schemaName,
            String tenantCode,
            TenantUserRecord actor,
            Map<String, Integer> userTypeIds,
            int preferredLanguageId,
            int actorUserId,
            List<UploadRow> rows,
            Map<String, Integer> schemeIdCache,
            Set<Long> mappingsClearedInUpload
    ) {
        if (rows == null || rows.isEmpty()) {
            return new ChunkResult(0, 0, 0);
        }

        List<UserSchemeMappingCreateRow> insertRows = new ArrayList<>(rows.size());
        List<Long> userIdsForInsertRows = new ArrayList<>(rows.size());
        List<String> phonesForInsertRows = new ArrayList<>(rows.size());
        List<String> typesForInsertRows = new ArrayList<>(rows.size());
        Set<Long> userIdsToReplace = new LinkedHashSet<>();
        Map<Long, UploadUserSnapshot> userSnapshots = new java.util.LinkedHashMap<>();
        int uploaded = 0;
        int skipped = 0;
        int unchanged = 0;

        for (UploadRow row : rows) {
            try {
                String typeKey = normalizeType(row.personType());
                Integer userTypeId = userTypeIds.get(typeKey);
                if (userTypeId == null) {
                    skipped++;
                    continue;
                }

                Integer schemeId = resolveSchemeId(schemaName, row.stateSchemeId(), schemeIdCache);
                if (schemeId == null || schemeId < 0) {
                    skipped++;
                    continue;
                }

                String normalizedPhone = PhoneNumberUtil.normalizeIndianMobileForDb(row.phone());
                TenantUserRecord user = userTenantRepository.findUserByPhone(schemaName, normalizedPhone).orElse(null);
                Long userId;
                UUID userUuid;
                String userEmail;
                String title = resolveTitle(row, typeKey, normalizedPhone);
                if (user == null) {
                    String newUuid = java.util.UUID.randomUUID().toString();
                    String newEmail = uniqueEmail(schemaName, normalizedPhone, typeKey);
                    userId = userTenantRepository.createUser(
                            schemaName,
                            newUuid,
                            actor.tenantId(),
                            title,
                            newEmail,
                            userTypeId,
                            normalizedPhone,
                            "CSV_ONBOARDED",
                            actor.id()
                    );
                    if (userId == null) {
                        skipped++;
                        continue;
                    }
                    userTenantRepository.updateUserLanguageId(schemaName, userId, preferredLanguageId);
                    userUuid = UUID.fromString(newUuid);
                    userEmail = newEmail;
                } else {
                    // If an existing user isn't the requested type, skip to avoid mutating unrelated user types.
                    if (user.cName() == null || !user.cName().equalsIgnoreCase(typeKey.toUpperCase(java.util.Locale.ROOT))) {
                        skipped++;
                        continue;
                    }
                    userId = user.id();
                    // Revoke the Keycloak account if the phone number has changed so the next
                    // OTP login re-provisions with the new phone as the Keycloak username.
                    // In the current CSV flow matching is by phone so this is always a no-op,
                    // but the guard is critical for any future in-place phone-change path.
                    if (!normalizedPhone.equals(user.phoneNumber())) {
                        staffKeycloakService.revokeKeycloakAccount(user, schemaName, actor.id());
                    }
                    userTenantRepository.updateUserProfile(schemaName, userId, title, normalizedPhone);
                    userTenantRepository.updateUserLanguageId(schemaName, userId, preferredLanguageId);
                    userUuid = null;
                    if (user.keycloakUuid() != null && !user.keycloakUuid().isBlank()) {
                        try {
                            userUuid = UUID.fromString(user.keycloakUuid());
                        } catch (IllegalArgumentException ignored) {
                            // keep null when UUID is malformed
                        }
                    }
                    userEmail = user.email();
                }
                userSnapshots.put(userId, new UploadUserSnapshot(
                        userId,
                        actor.tenantId(),
                        userTypeId,
                        userUuid,
                        userEmail,
                        title,
                        TenantUserStatus.ACTIVE.code
                ));

                // Replace existing mappings once per upload, then keep adding all mapped schemes for the same user.
                if (mappingsClearedInUpload.add(userId)) {
                    userIdsToReplace.add(userId);
                }
                insertRows.add(new UserSchemeMappingCreateRow(userId, schemeId));
                userIdsForInsertRows.add(userId);
                phonesForInsertRows.add(normalizedPhone);
                typesForInsertRows.add(typeKey);

                uploaded++;
            } catch (Exception ex) {
                // Best-effort: one bad row must not abort the entire batch upload.
                skipped++;
                log.warn("[pump-operator-upload] row_failed row={} err={}", row.rowNumber(), ex.getMessage());
                log.debug("[pump-operator-upload] row_failed row={} phone={} err={}",
                        row.rowNumber(), row.phone(), ex.getMessage());
            }
        }

        if (!userIdsToReplace.isEmpty()) {
            userUploadRepository.markUserSchemeMappingsDeleted(schemaName, new ArrayList<>(userIdsToReplace), actorUserId);
        }
        int[] insertCounts = userUploadRepository.insertUserSchemeMappings(schemaName, insertRows, actorUserId);
        Set<String> phonesToNotify = new LinkedHashSet<>();
        Map<Long, Set<Integer>> schemeIdsByUser = new java.util.LinkedHashMap<>();
        int inserted = 0;
        int n = Math.min(insertCounts.length, phonesForInsertRows.size());
        for (int i = 0; i < n; i++) {
            if (insertCounts[i] > 0) {
                inserted++;
                Long uid = userIdsForInsertRows.get(i);
                Integer sid = insertRows.get(i).schemeId();
                schemeIdsByUser.computeIfAbsent(uid, k -> new LinkedHashSet<>()).add(sid);
                if ("pump_operator".equals(typesForInsertRows.get(i))) {
                    phonesToNotify.add(phonesForInsertRows.get(i));
                }
            }
        }

        for (Map.Entry<Long, Set<Integer>> entry : schemeIdsByUser.entrySet()) {
            UploadUserSnapshot snapshot = userSnapshots.get(entry.getKey());
            if (snapshot == null) {
                continue;
            }
            userAnalyticsEventPublisher.publishUserUpdatedAfterCommit(
                    snapshot.userId(),
                    snapshot.tenantId(),
                    snapshot.userType(),
                    snapshot.uuid(),
                    snapshot.email(),
                    snapshot.title(),
                    snapshot.status()
            );
            userAnalyticsEventPublisher.publishUserSchemeMappingsReplacedAfterCommit(
                    snapshot.userId(),
                    snapshot.tenantId(),
                    snapshot.uuid(),
                    new ArrayList<>(entry.getValue())
            );
        }

        if (!phonesToNotify.isEmpty()) {
            userEventPublisher.publishPumpOperatorOnboardedAfterCommit(
                    tenantCode,
                    actor.tenantId(),
                    String.valueOf(preferredLanguageId),
                    new ArrayList<>(phonesToNotify)
            );
        }

        if (uploaded > 0) {
            dataVersionRepository.bump(schemaName, ResourceType.STAFF_USERS);
        }

        log.info("[pump-operator-upload] chunk_processed rows={} uploaded={} skipped={} mappings_inserted={}",
                rows.size(), uploaded, skipped, inserted);

        return new ChunkResult(uploaded, skipped, unchanged);
    }

    private Integer resolveSchemeId(
            String schemaName,
            String stateSchemeId,
            Map<String, Integer> schemeIdCache
    ) {
        String schemeKey = stateSchemeId;
        Integer schemeId = schemeIdCache.get(schemeKey);
        if (schemeId != null) {
            return schemeId;
        }
        Integer found = userUploadRepository.findSchemeId(schemaName, blankToNull(stateSchemeId), null);
        schemeId = found != null ? found : -1;
        schemeIdCache.put(schemeKey, schemeId);
        return schemeId;
    }

    private String generatedEmailForPhone(String phone, String typeKey) {
        return emailPrefix(typeKey) + phone + "@pump-operator.local";
    }

    private String uniqueEmail(String schemaName, String phone, String typeKey) {
        String email = generatedEmailForPhone(phone, typeKey);
        // Extremely unlikely for new users (email derives from phone), but keep the same safety as the old flow.
        if (userTenantRepository.findUserByEmail(schemaName, email).isPresent()) {
            return emailPrefix(typeKey) + phone + "_" + java.util.UUID.randomUUID() + "@pump-operator.local";
        }
        return email;
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isBlank() ? null : t;
    }

    private String normalizeType(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
    }

    private String defaultTitle(String typeKey) {
        return switch (typeKey) {
            case "section_officer" -> "Section Officer";
            case "sub_divisional_officer" -> "Sub Divisional Officer";
            default -> "Pump Operator";
        };
    }

    private String resolveTitle(UploadRow row, String typeKey, String normalizedPhone) {
        String title = row.fullName() == null ? "" : row.fullName().trim();
        if (title.isBlank()) {
            title = (row.firstName() + " " + row.lastName()).trim();
        }
        if (title.isBlank()) {
            title = defaultTitle(typeKey) + " " + normalizedPhone;
        }
        return title;
    }

    private String emailPrefix(String typeKey) {
        return switch (typeKey) {
            case "section_officer" -> "so_";
            case "sub_divisional_officer" -> "sdo_";
            default -> "po_";
        };
    }
}
