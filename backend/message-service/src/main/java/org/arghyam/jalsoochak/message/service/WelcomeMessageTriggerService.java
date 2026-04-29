package org.arghyam.jalsoochak.message.service;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.message.dto.TriggerWelcomeMessageResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class WelcomeMessageTriggerService {

    private final JdbcTemplate jdbcTemplate;
    private final MessageTemplateService messageTemplateService;
    private final PiiEncryptionService piiEncryptionService;

    public TriggerWelcomeMessageResponse triggerByPhone(String phoneInput) {
        String phone = phoneInput == null ? "" : phoneInput.trim();
        if (phone.isBlank()) {
            throw new IllegalArgumentException("phoneNumber is required");
        }
        String tenantCode = resolveTenantCodeByPhone(phone);
        return trigger(tenantCode, phone);
    }

    public TriggerWelcomeMessageResponse trigger(String tenantCodeInput, String phoneInput) {
        String tenantCode = normalizeTenantCode(tenantCodeInput);
        String phone = phoneInput == null ? "" : phoneInput.trim();
        if (tenantCode.isBlank() || !tenantCode.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid tenantCode");
        }
        if (phone.isBlank()) {
            throw new IllegalArgumentException("phoneNumber is required");
        }

        int tenantId = resolveTenantId(tenantCode);
        String tenantSchema = "tenant_" + tenantCode;
        String stateName = messageTemplateService.findStateName(tenantId);

        UserContactInfo info = fetchUserContactInfo(tenantSchema, phone);
        String normalizedPhone = normalizeIndianPhone(phone);
        if ((info.contactId == null || info.contactId <= 0) && !normalizedPhone.equals(phone)) {
            info = fetchUserContactInfo(tenantSchema, normalizedPhone);
        }

        return TriggerWelcomeMessageResponse.builder()
                .success(true)
                .tenantCode(tenantCode)
                .phoneNumber(normalizedPhone)
                .contactId(info.contactId)
                .name(info.name)
                .state(stateName)
                .message("Welcome context resolved")
                .build();
    }

    private int resolveTenantId(String tenantCode) {
        String sql = """
                SELECT id
                FROM common_schema.tenant_master_table
                WHERE lower(state_code) = ?
                ORDER BY id DESC
                LIMIT 1
                """;
        List<Integer> rows = jdbcTemplate.query(sql, (rs, n) -> rs.getInt("id"), tenantCode.toLowerCase(Locale.ROOT));
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("Tenant not found for tenantCode: " + tenantCode);
        }
        return rows.get(0);
    }

    private String resolveTenantCodeByPhone(String phone) {
        List<String> tenantCodes = jdbcTemplate.query(
                """
                SELECT lower(state_code)
                FROM common_schema.tenant_master_table
                WHERE state_code IS NOT NULL AND state_code <> ''
                ORDER BY id DESC
                """,
                (rs, n) -> rs.getString(1));
        for (String tenantCode : tenantCodes) {
            if (tenantCode == null || tenantCode.isBlank()) {
                continue;
            }
            String tenantSchema = "tenant_" + tenantCode;
            if (phoneExistsInTenant(tenantSchema, phone)) {
                return tenantCode;
            }
        }
        throw new IllegalArgumentException("No tenant found for phoneNumber");
    }

    private boolean phoneExistsInTenant(String tenantSchema, String phone) {
        String hashSql = "SELECT 1 FROM " + tenantSchema + ".user_table WHERE phone_number_hash = ? LIMIT 1";
        try {
            for (String candidate : buildPhoneCandidates(phone)) {
                String lookupHash = piiEncryptionService.hmac(candidate);
                List<Integer> rows = jdbcTemplate.query(hashSql, (rs, n) -> rs.getInt(1), lookupHash);
                if (rows != null && !rows.isEmpty()) {
                    return true;
                }
            }
        } catch (Exception ignore) {
            // Fallback below
        }

        String plainSql = "SELECT 1 FROM " + tenantSchema + ".user_table WHERE phone_number = ? LIMIT 1";
        for (String candidate : buildPhoneCandidates(phone)) {
            try {
                List<Integer> rows = jdbcTemplate.query(plainSql, (rs, n) -> rs.getInt(1), candidate);
                if (rows != null && !rows.isEmpty()) {
                    return true;
                }
            } catch (Exception ignore) {
                // keep trying other candidates/schemas
            }
        }
        return false;
    }

    private UserContactInfo fetchUserContactInfo(String tenantSchema, String phone) {
        UserContactInfo byHash = fetchUserContactInfoByHash(tenantSchema, phone);
        if (byHash.contactId != null || byHash.name != null) {
            return byHash;
        }
        String sql = "SELECT whatsapp_connection_id, title FROM " + tenantSchema
                + ".user_table WHERE phone_number = ? LIMIT 1";
        List<UserContactInfo> rows = jdbcTemplate.query(sql,
                (rs, n) -> new UserContactInfo(
                        rs.getObject("whatsapp_connection_id", Long.class),
                        piiEncryptionService.safeDecrypt(rs.getString("title"))),
                phone);
        return rows.isEmpty() ? new UserContactInfo(null, null) : rows.get(0);
    }

    private UserContactInfo fetchUserContactInfoByHash(String tenantSchema, String phone) {
        String sql = "SELECT whatsapp_connection_id, title FROM " + tenantSchema
                + ".user_table WHERE phone_number_hash = ? LIMIT 1";
        try {
            for (String candidate : buildPhoneCandidates(phone)) {
                String lookupHash = piiEncryptionService.hmac(candidate);
                List<UserContactInfo> rows = jdbcTemplate.query(sql,
                        (rs, n) -> new UserContactInfo(
                                rs.getObject("whatsapp_connection_id", Long.class),
                                piiEncryptionService.safeDecrypt(rs.getString("title"))),
                        lookupHash);
                if (rows != null && !rows.isEmpty()) {
                    return rows.get(0);
                }
            }
        } catch (Exception ex) {
            return new UserContactInfo(null, null);
        }
        return new UserContactInfo(null, null);
    }

    private List<String> buildPhoneCandidates(String phone) {
        if (phone == null || phone.isBlank()) {
            return List.of();
        }
        String raw = phone.trim();
        String digits = raw.replaceAll("\\D", "");
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(raw);
        if (!digits.isBlank()) {
            candidates.add(digits);
            if (digits.length() == 10) {
                candidates.add("91" + digits);
            } else if (digits.length() == 12 && digits.startsWith("91")) {
                candidates.add(digits.substring(2));
            }
        }
        return new ArrayList<>(candidates);
    }

    private String normalizeIndianPhone(String phone) {
        if (phone == null) return "";
        String digits = phone.trim().replaceAll("\\D", "");
        if (digits.length() == 10) return "91" + digits;
        return digits;
    }

    private String normalizeTenantCode(String tenantCode) {
        return tenantCode == null ? "" : tenantCode.trim().toLowerCase(Locale.ROOT);
    }

    private record UserContactInfo(Long contactId, String name) {}
}
