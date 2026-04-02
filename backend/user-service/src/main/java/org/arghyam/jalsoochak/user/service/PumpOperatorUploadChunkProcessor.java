package org.arghyam.jalsoochak.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.event.UserEventPublisher;
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

    @Transactional
    public ChunkResult processChunk(
            String schemaName,
            String tenantCode,
            TenantUserRecord actor,
            Map<String, Integer> userTypeIds,
            int preferredLanguageId,
            int actorUserId,
            List<UploadRow> rows,
            Map<String, Integer> schemeIdCache
    ) {
        if (rows == null || rows.isEmpty()) {
            return new ChunkResult(0, 0);
        }

        List<UserSchemeMappingCreateRow> insertRows = new ArrayList<>(rows.size());
        List<String> phonesForInsertRows = new ArrayList<>(rows.size());
        List<String> typesForInsertRows = new ArrayList<>(rows.size());
        Set<Long> userIdsToReplace = new LinkedHashSet<>();
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
                String title = resolveTitle(row, typeKey, normalizedPhone);
                if (user == null) {
                    userId = userTenantRepository.createUser(
                            schemaName,
                            java.util.UUID.randomUUID().toString(),
                            actor.tenantId(),
                            title,
                            uniqueEmail(schemaName, normalizedPhone, typeKey),
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
                } else {
                    // If an existing user isn't the requested type, skip to avoid mutating unrelated user types.
                    if (user.cName() == null || !user.cName().equalsIgnoreCase(typeKey.toUpperCase(java.util.Locale.ROOT))) {
                        skipped++;
                        continue;
                    }
                    userId = user.id();
                    if (isUnchangedMapping(schemaName, user, userId, normalizedPhone, title, schemeId)) {
                        unchanged++;
                        continue;
                    }
                    userTenantRepository.updateUserProfile(schemaName, userId, title, normalizedPhone);
                    userTenantRepository.updateUserLanguageId(schemaName, userId, preferredLanguageId);
                }

                // Replace existing mappings so the upload overwrites prior assignments.
                userIdsToReplace.add(userId);
                insertRows.add(new UserSchemeMappingCreateRow(userId, schemeId));
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
        int inserted = 0;
        int n = Math.min(insertCounts.length, phonesForInsertRows.size());
        for (int i = 0; i < n; i++) {
            if (insertCounts[i] > 0) {
                inserted++;
                if ("pump_operator".equals(typesForInsertRows.get(i))) {
                    phonesToNotify.add(phonesForInsertRows.get(i));
                }
            }
        }

        if (!phonesToNotify.isEmpty()) {
            userEventPublisher.publishPumpOperatorOnboardedAfterCommit(
                    tenantCode,
                    actor.tenantId(),
                    String.valueOf(preferredLanguageId),
                    new ArrayList<>(phonesToNotify)
            );
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

    private boolean isUnchangedMapping(
            String schemaName,
            TenantUserRecord user,
            Long userId,
            String normalizedPhone,
            String title,
            Integer schemeId
    ) {
        if (user == null || userId == null || schemeId == null) {
            return false;
        }

        String existingPhone = PhoneNumberUtil.normalizeIndianMobileForDb(user.phoneNumber());
        if (existingPhone == null || !existingPhone.equals(normalizedPhone)) {
            return false;
        }

        String existingTitle = user.title() == null ? "" : user.title().trim();
        String incomingTitle = title == null ? "" : title.trim();
        if (!existingTitle.equals(incomingTitle)) {
            return false;
        }

        List<Integer> schemeIds = userUploadRepository.findActiveSchemeIdsForUser(schemaName, userId);
        return schemeIds.size() == 1 && schemeIds.get(0).equals(schemeId);
    }

    private String emailPrefix(String typeKey) {
        return switch (typeKey) {
            case "section_officer" -> "so_";
            case "sub_divisional_officer" -> "sdo_";
            default -> "po_";
        };
    }
}
