package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.event.OperatorLanguageEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserLanguagePreferenceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

@Service
@Slf4j
public class OperatorContextService {

    private final TelemetryTenantRepository telemetryTenantRepository;
    private final UserLanguagePreferenceRepository userLanguagePreferenceRepository;
    private final TenantConfigRepository tenantConfigRepository;
    private final PiiEncryptionService piiEncryptionService;
    private final LanguageMenuService languageMenuService;
    private final OperatorLanguageEventPublisher operatorLanguageEventPublisher;
    private final TransactionTemplate transactionTemplate;

    public OperatorContextService(TelemetryTenantRepository telemetryTenantRepository,
                                  UserLanguagePreferenceRepository userLanguagePreferenceRepository,
                                  TenantConfigRepository tenantConfigRepository,
                                  PiiEncryptionService piiEncryptionService,
                                  LanguageMenuService languageMenuService,
                                  OperatorLanguageEventPublisher operatorLanguageEventPublisher,
                                  TransactionTemplate transactionTemplate) {
        this.telemetryTenantRepository = telemetryTenantRepository;
        this.userLanguagePreferenceRepository = userLanguagePreferenceRepository;
        this.tenantConfigRepository = tenantConfigRepository;
        this.piiEncryptionService = piiEncryptionService;
        this.languageMenuService = languageMenuService;
        this.operatorLanguageEventPublisher = operatorLanguageEventPublisher;
        this.transactionTemplate = transactionTemplate;
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

    /**
     * The language to answer the operator in, the first found of: the language saved in
     * {@code user_language_preference}; {@code user_table.language_id}, read as the id of a language menu
     * option, or for a tenant without a menu, as a {@code language_N} position; the tenant's first
     * language; English. Saves nothing — see {@link #ensureOperatorLanguage}.
     */
    public String resolveOperatorLanguage(TelemetryOperatorWithSchema operatorWithSchema, Integer tenantId) {
        return resolveLanguage(operatorWithSchema, tenantId).language();
    }

    /**
     * {@link #resolveOperatorLanguage} for what may be the operator's first message. When the operator has
     * no saved language, saves the one resolved — with {@code language_id} too when it is the tenant's
     * first language — and hands it to message-service for the operator's WhatsApp contact. A language
     * saved meanwhile is kept. A failure is logged, never raised: the answer is unchanged and the next
     * message retries.
     */
    public String ensureOperatorLanguage(TelemetryOperatorWithSchema operatorWithSchema, Integer tenantId) {
        ResolvedLanguage resolved = resolveLanguage(operatorWithSchema, tenantId);
        if (resolved.toSave() && resolved.language() != null && !resolved.language().isBlank()) {
            saveLanguage(operatorWithSchema, tenantId, resolved);
        }
        return resolved.language();
    }

    private ResolvedLanguage resolveLanguage(TelemetryOperatorWithSchema operatorWithSchema, Integer tenantId) {
        if (operatorWithSchema == null || operatorWithSchema.operator() == null) {
            return ResolvedLanguage.FALLBACK;
        }
        String schemaName = operatorWithSchema.schemaName();
        String phone = operatorWithSchema.operator().phoneNumber();
        // Without a tenant or a phone the saved language cannot be read, so none is saved either.
        boolean canSave = tenantId != null && phone != null && !phone.isBlank();
        if (canSave) {
            Optional<String> savedLanguage = userLanguagePreferenceRepository.findLanguage(schemaName, phone)
                    .filter(language -> !language.isBlank());
            if (savedLanguage.isPresent()) {
                return new ResolvedLanguage(savedLanguage.get(), false, null);
            }
        }

        Integer languageId = operatorWithSchema.operator().languageId();
        List<LanguageMenuService.MenuOption> menu = languageMenuService.resolveOptions(tenantId, schemaName);
        if (!menu.isEmpty()) {
            if (languageId != null) {
                for (LanguageMenuService.MenuOption option : menu) {
                    if (option.languageId() == languageId) {
                        return new ResolvedLanguage(option.option().canonicalLabel(), canSave, null);
                    }
                }
            }
            LanguageMenuService.MenuOption first = menu.get(0);
            return new ResolvedLanguage(first.option().canonicalLabel(), canSave, first.languageId());
        }

        List<String> languageOptions = tenantConfigRepository.findLanguageOptions(tenantId);
        if (languageOptions.isEmpty()) {
            return ResolvedLanguage.FALLBACK;
        }
        if (languageId != null && languageId > 0 && languageId <= languageOptions.size()) {
            return new ResolvedLanguage(languageOptions.get(languageId - 1), canSave, null);
        }
        return new ResolvedLanguage(languageOptions.get(0), canSave, 1);
    }

    private void saveLanguage(TelemetryOperatorWithSchema operatorWithSchema, Integer tenantId,
                              ResolvedLanguage resolved) {
        String schemaName = operatorWithSchema.schemaName();
        TelemetryOperator operator = operatorWithSchema.operator();
        try {
            Boolean saved = transactionTemplate.execute(status -> {
                if (!userLanguagePreferenceRepository.setLanguageWhereUnset(
                        schemaName, operator.phoneNumber(), resolved.language())) {
                    return false;
                }
                if (resolved.languageIdToSet() != null) {
                    telemetryTenantRepository.updateUserLanguageId(schemaName, operator.id(), resolved.languageIdToSet());
                }
                return true;
            });
            if (Boolean.TRUE.equals(saved)) {
                operatorLanguageEventPublisher.publishLanguageUpdated(
                        schemaName, tenantId, operator.phoneNumber(), resolved.language());
            }
        } catch (RuntimeException e) {
            log.warn("[operator-language] Language '{}' of operatorId={} (tenantId={}) not saved or not published: {}",
                    resolved.language(), operator.id(), tenantId, e.getMessage());
        }
    }

    /**
     * @param toSave          the operator has no saved language, and this one is to be saved for them
     * @param languageIdToSet the {@code language_id} to save with it; null to leave {@code language_id} as is
     */
    private record ResolvedLanguage(String language, boolean toSave, Integer languageIdToSet) {
        private static final ResolvedLanguage FALLBACK = new ResolvedLanguage("English", false, null);
    }
}
