package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserLanguagePreferenceRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class GlificOperatorContextService {

    private final TelemetryTenantRepository telemetryTenantRepository;
    private final UserLanguagePreferenceRepository userLanguagePreferenceRepository;
    private final TenantConfigRepository tenantConfigRepository;
    private final PiiEncryptionService piiEncryptionService;

    public GlificOperatorContextService(TelemetryTenantRepository telemetryTenantRepository,
                                        UserLanguagePreferenceRepository userLanguagePreferenceRepository,
                                        TenantConfigRepository tenantConfigRepository,
                                        PiiEncryptionService piiEncryptionService) {
        this.telemetryTenantRepository = telemetryTenantRepository;
        this.userLanguagePreferenceRepository = userLanguagePreferenceRepository;
        this.tenantConfigRepository = tenantConfigRepository;
        this.piiEncryptionService = piiEncryptionService;
    }

    public TelemetryOperatorWithSchema resolveOperatorWithSchema(String contactId) {
        Integer preferredTenantId = userLanguagePreferenceRepository
                .findPreferredTenantIdByContactId(contactId)
                .orElse(null);

        return resolveOperatorWithSchema(contactId, preferredTenantId);
    }

    /**
     * LENIENT-INGEST: non-throwing variant of {@link #resolveOperatorWithSchema(String, Integer)}.
     * Returns empty (instead of throwing) when no operator is registered for the contact, so the
     * caller can fall back to recording the submission against a sentinel operator.
     */
    public Optional<TelemetryOperatorWithSchema> tryResolveOperatorWithSchema(String contactId, Integer preferredTenantId) {
        if (contactId == null || contactId.isBlank()) {
            // A missing contactId is a validation error, not a lookup miss — let it propagate so the
            // submission is rejected rather than silently recorded against the sentinel operator.
            throw new IllegalStateException("contactId is required");
        }
        try {
            return Optional.of(resolveOperatorWithSchema(contactId, preferredTenantId));
        } catch (IllegalStateException notFound) {
            return Optional.empty();
        }
    }

    public TelemetryOperatorWithSchema resolveOperatorWithSchema(String contactId, Integer preferredTenantId) {
        if (contactId == null || contactId.isBlank()) {
            throw new IllegalStateException("contactId is required");
        }

        String trimmed = contactId.trim();

        if (looksLikePhoneHash(trimmed)) {
            return telemetryTenantRepository
                    .findOperatorByPhoneHashAcrossTenants(trimmed, preferredTenantId)
                    .orElseThrow(() -> new IllegalStateException("No operator found for the provided contactId"));
        }

        if (looksLikeEncryptedValue(trimmed)) {
            String decrypted = tryDecrypt(trimmed);
            if (decrypted != null && !decrypted.isBlank() && !decrypted.equals(trimmed)) {
                TelemetryOperatorWithSchema decryptedMatch = telemetryTenantRepository
                        .findOperatorByPhoneAcrossTenants(decrypted, preferredTenantId)
                        .orElse(null);
                if (decryptedMatch != null) {
                    return decryptedMatch;
                }
            }
        }

        return telemetryTenantRepository
                .findOperatorByPhoneAcrossTenants(trimmed, preferredTenantId)
                .orElseThrow(() -> new IllegalStateException("No operator found for contactId " + contactId));
    }

    private boolean looksLikePhoneHash(String value) {
        if (value == null) {
            return false;
        }
        return value.matches("^[A-Fa-f0-9]{64}$");
    }

    private boolean looksLikeEncryptedValue(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.length() < 24 || (trimmed.length() % 4) != 0) {
            return false;
        }
        if (!trimmed.matches("^[A-Za-z0-9+/=]+$")) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '+' || c == '/') {
                return true;
            }
        }
        return false;
    }

    private String tryDecrypt(String value) {
        try {
            return piiEncryptionService.decrypt(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    public String resolveOperatorLanguage(TelemetryOperatorWithSchema operatorWithSchema, Integer tenantId) {
        if (operatorWithSchema == null || operatorWithSchema.operator() == null) {
            return "English";
        }
        if (tenantId != null) {
            String contactId = operatorWithSchema.operator().phoneNumber();
            if (contactId != null && !contactId.isBlank()) {
                String preferredLanguage = userLanguagePreferenceRepository
                        .findLanguage(tenantId, contactId)
                        .orElse(null);
                if (preferredLanguage != null && !preferredLanguage.isBlank()) {
                    return preferredLanguage;
                }
            }
        }
        Integer languageId = operatorWithSchema.operator().languageId();
        if (languageId == null || languageId <= 0) {
            return "English";
        }
        List<String> languageOptions = tenantConfigRepository.findLanguageOptions(tenantId);
        if (languageOptions.isEmpty() || languageId > languageOptions.size()) {
            return "English";
        }
        return languageOptions.get(languageId - 1);
    }
}
