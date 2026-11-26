package org.arghyam.jalsoochak.user.service.serviceImpl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.user.dto.request.WelcomeMessageRequestDTO;
import org.arghyam.jalsoochak.user.dto.response.WelcomeMessageResponseDTO;
import org.arghyam.jalsoochak.user.event.UserEventPublisher;
import org.arghyam.jalsoochak.user.exceptions.ForbiddenAccessException;
import org.arghyam.jalsoochak.user.exceptions.ResourceNotFoundException;
import org.arghyam.jalsoochak.user.repository.LanguageCatalogRepository;
import org.arghyam.jalsoochak.user.repository.UserCommonRepository;
import org.arghyam.jalsoochak.user.repository.UserLanguagePreferenceRepository;
import org.arghyam.jalsoochak.user.repository.UserPhoneRow;
import org.arghyam.jalsoochak.user.repository.UserTenantRepository;
import org.arghyam.jalsoochak.user.service.WhatsAppPreferredLanguageService;
import org.arghyam.jalsoochak.user.service.WhatsAppPreferredLanguageService.PreferredLanguage;
import org.arghyam.jalsoochak.user.util.SecurityUtils;
import org.arghyam.jalsoochak.user.util.TenantSchemaResolver;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Queues welcome messages for a tenant's active users of the given roles. Each pump operator's language is
 * sent to the WhatsApp provider first, since the provider picks the welcome template's language from the
 * contact's: the operator's saved chatbot language, or for one with none, the tenant default, which is also saved.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WelcomeMessageServiceImpl implements org.arghyam.jalsoochak.user.service.WelcomeMessageService {

    private static final int DEFAULT_BATCH_SIZE = 1000;
    private static final String SUPPORTED_TYPE = "welcome_template";
    private static final String ROLE_PUMP_OPERATOR = "PUMP_OPERATOR";

    private final UserTenantRepository userTenantRepository;
    private final UserCommonRepository userCommonRepository;
    private final UserEventPublisher userEventPublisher;
    private final UserLanguagePreferenceRepository userLanguagePreferenceRepository;
    private final LanguageCatalogRepository languageCatalogRepository;
    private final WhatsAppPreferredLanguageService whatsAppPreferredLanguageService;

    @Override
    public WelcomeMessageResponseDTO sendWelcomeMessages(
            String tenantCode,
            WelcomeMessageRequestDTO request,
            Authentication authentication
    ) {
        if (request == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        String type = request.getType() == null ? "" : request.getType().trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_TYPE.equals(type)) {
            throw new IllegalArgumentException("type must be '" + SUPPORTED_TYPE + "'");
        }
        List<String> roles = normalizeRoles(request.getRoles());
        if (roles.isEmpty()) {
            throw new IllegalArgumentException("At least one role is required");
        }

        String schema = TenantSchemaResolver.requireSchemaNameFromTenantCode(tenantCode);
        String callerTenantCode = SecurityUtils.extractTenantCode(authentication);
        boolean isSuperUser = SecurityUtils.extractRole(authentication)
                .map("SUPER_USER"::equals)
                .orElse(false);

        if (!isSuperUser) {
            if (callerTenantCode == null || !callerTenantCode.equalsIgnoreCase(tenantCode)) {
                throw new ForbiddenAccessException("State admin can only send welcome messages within their own state");
            }
        }

        Integer tenantId = userCommonRepository.findTenantIdByStateCode(tenantCode)
                .orElseThrow(() -> new ResourceNotFoundException("Tenant not found for code: " + tenantCode));

        Instant after = parseInstantOrDate(request.getOnboardedAfter(), true);
        Instant before = parseInstantOrDate(request.getOnboardedBefore(), false);
        if (after != null && before != null && !before.isAfter(after)) {
            throw new IllegalArgumentException("onboardedBefore must be after onboardedAfter");
        }

        WelcomeRun run = new WelcomeRun(tenantCode, tenantId, schema);
        userTenantRepository.streamUsersByRolesAndOnboardingWindow(schema, roles, after, before, run::accept);
        run.flush();

        String msg = run.totalPhones == 0
                ? "No matching users found"
                : "Queued welcome messages for " + run.totalPhones + " users";

        return WelcomeMessageResponseDTO.builder()
                .totalCandidates(run.totalPhones)
                .totalPhones(run.totalPhones)
                .batches(run.batches)
                .batchSize(DEFAULT_BATCH_SIZE)
                .publishedEvents(run.batches)
                .message(msg)
                .build();
    }

    /**
     * One request's users, queued in batches; per batch, the pump operators' language updates go before
     * the welcome. The tenant default and catalog ids are looked up once per request, when first needed.
     */
    private final class WelcomeRun {

        private final String tenantCode;
        private final Integer tenantId;
        private final String schema;
        private final List<UserPhoneRow> batch = new ArrayList<>(DEFAULT_BATCH_SIZE);
        private final Map<String, Optional<Integer>> catalogIdByLanguage = new HashMap<>();
        private PreferredLanguage tenantDefault;
        private int totalPhones;
        private int batches;

        private WelcomeRun(String tenantCode, Integer tenantId, String schema) {
            this.tenantCode = tenantCode;
            this.tenantId = tenantId;
            this.schema = schema;
        }

        void accept(UserPhoneRow user) {
            batch.add(user);
            if (batch.size() >= DEFAULT_BATCH_SIZE) {
                flush();
            }
        }

        void flush() {
            if (batch.isEmpty()) {
                return;
            }
            publishPumpOperatorLanguages();
            List<String> phones = batch.stream().map(UserPhoneRow::phoneNumber).toList();
            userEventPublisher.publishAdminWelcomeMessages(tenantCode, tenantId, phones);
            totalPhones += phones.size();
            batches++;
            batch.clear();
        }

        /**
         * Publishes one language update per language: each pump operator's saved language, or for one with
         * none, the tenant default, which is saved for them too. A saved language the catalog doesn't know
         * is not sent, so the provider keeps whatever it has.
         */
        private void publishPumpOperatorLanguages() {
            Map<String, Long> operatorIdByPhone = new LinkedHashMap<>();
            for (UserPhoneRow user : batch) {
                if (ROLE_PUMP_OPERATOR.equalsIgnoreCase(user.userType())) {
                    operatorIdByPhone.putIfAbsent(user.phoneNumber(), user.id());
                }
            }
            if (operatorIdByPhone.isEmpty()) {
                return;
            }

            Map<String, String> savedLanguages =
                    userLanguagePreferenceRepository.findLanguageValues(schema, operatorIdByPhone.keySet());
            Map<Integer, List<String>> phonesByLanguageId = new LinkedHashMap<>();
            List<String> withoutLanguage = new ArrayList<>();
            int unknownLanguages = 0;
            for (String phone : operatorIdByPhone.keySet()) {
                String saved = savedLanguages.get(phone);
                if (saved == null) {
                    withoutLanguage.add(phone);
                    continue;
                }
                Optional<Integer> catalogId = catalogIdByLanguage.computeIfAbsent(
                        saved, languageCatalogRepository::findLanguageIdByAlias);
                if (catalogId.isPresent()) {
                    phonesByLanguageId.computeIfAbsent(catalogId.get(), id -> new ArrayList<>()).add(phone);
                } else {
                    unknownLanguages++;
                }
            }
            if (unknownLanguages > 0) {
                log.warn("[welcome-admin] language_not_in_catalog count={} tenantCode={} tenantId={};"
                        + " their provider language is left as it is", unknownLanguages, tenantCode, tenantId);
            }

            if (!withoutLanguage.isEmpty()) {
                PreferredLanguage fallback = tenantDefault();
                for (String phone : withoutLanguage) {
                    userTenantRepository.updateUserLanguageId(schema, operatorIdByPhone.get(phone), fallback.languageId());
                }
                // The chatbot reads user_language_preference ahead of language_id, so it gets the same default.
                if (fallback.languageValue() != null) {
                    userLanguagePreferenceRepository.setLanguageWhereUnset(schema, withoutLanguage, fallback.languageValue());
                }
                phonesByLanguageId.computeIfAbsent(fallback.whatsappLanguageId(), id -> new ArrayList<>())
                        .addAll(withoutLanguage);
            }

            phonesByLanguageId.forEach((languageId, phones) ->
                    userEventPublisher.publishLanguageUpdates(tenantCode, tenantId, String.valueOf(languageId), phones));
        }

        private PreferredLanguage tenantDefault() {
            if (tenantDefault == null) {
                tenantDefault = whatsAppPreferredLanguageService.resolvePreferredLanguage(tenantId, schema);
            }
            return tenantDefault;
        }
    }

    private List<String> normalizeRoles(List<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String role : roles) {
            if (role == null || role.isBlank()) {
                continue;
            }
            String[] parts = role.split(",");
            for (String p : parts) {
                String value = p == null ? "" : p.trim();
                if (!value.isEmpty()) {
                    out.add(value.toLowerCase(Locale.ROOT));
                }
            }
        }
        return List.copyOf(out);
    }

    private Instant parseInstantOrDate(String raw, boolean startOfDay) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            // fall back to yyyy-MM-dd
        }
        try {
            LocalDate date = LocalDate.parse(trimmed);
            return startOfDay
                    ? date.atStartOfDay().toInstant(ZoneOffset.UTC)
                    : date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid date/time format: " + trimmed);
        }
    }
}
