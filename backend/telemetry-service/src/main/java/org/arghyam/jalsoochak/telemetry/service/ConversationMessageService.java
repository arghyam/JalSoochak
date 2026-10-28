package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.requests.ClosingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.ClosingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
public class ConversationMessageService {

    private final OperatorContextService operatorContextService;
    private final ConversationLocalizationService localizationService;
    private final TenantConfigRepository tenantConfigRepository;
    private final ConversationTemplateService templatesService;

    public ConversationMessageService(OperatorContextService operatorContextService,
                                      ConversationLocalizationService localizationService,
                                      TenantConfigRepository tenantConfigRepository,
                                      ConversationTemplateService templatesService) {
        this.operatorContextService = operatorContextService;
        this.localizationService = localizationService;
        this.tenantConfigRepository = tenantConfigRepository;
        this.templatesService = templatesService;
    }

    public IntroResponse introMessage(IntroRequest introRequest) {
        try {
            if (introRequest.getContactId() == null || introRequest.getContactId().isBlank()) {
                throw new IllegalStateException("contactId is required");
            }

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(introRequest.getContactId());

            Integer tenantId = operatorWithSchema.operator().tenantId();
            if (tenantId == null) {
                throw new IllegalStateException("Operator tenant could not be resolved");
            }
            String name = operatorWithSchema.operator().title() != null && !operatorWithSchema.operator().title().isBlank()
                    ? operatorWithSchema.operator().title()
                    : "there";

            String languageKey = localizationService.normalizeLanguageKey(
                    operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId));

            String template = templatesService
                    .resolveScreenMessage(tenantId, "INTRO_MESSAGE", languageKey)
                    .orElseGet(() -> resolveLegacyMessage(tenantId, "intro_message", languageKey));

            log.info("Resolved intro message config with language key '{}'", languageKey);
            log.debug("Resolved intro message config for contactId {} with language key '{}'", introRequest.getContactId(), languageKey);

            return IntroResponse.builder()
                    .success(true)
                    .message(template.replace("{name}", name))
                    .build();
        } catch (Exception e) {
            log.error("Error sending intro message: {}", e.getMessage(), e);
            log.debug("Error sending intro message for contactId {}: {}", introRequest.getContactId(), e.getMessage());
            return IntroResponse.builder()
                    .success(false)
                    .message("Something went wrong. Please try again.")
                    .build();
        }
    }

    public ClosingResponse closingMessage(ClosingRequest closingRequest) {
        try {
            if (closingRequest.getContactId() == null || closingRequest.getContactId().isBlank()) {
                throw new IllegalStateException("contactId is required");
            }

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(closingRequest.getContactId());

            Integer tenantId = operatorWithSchema.operator().tenantId();
            if (tenantId == null) {
                throw new IllegalStateException("Operator tenant could not be resolved");
            }
            String languageKey = localizationService.normalizeLanguageKey(
                    operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId));

            String template = templatesService
                    .resolveScreenMessage(tenantId, "CLOSING_MESSAGE", languageKey)
                    .orElseGet(() -> resolveLegacyMessage(tenantId, "closing_message", languageKey));

            log.info("Resolved closing message config with language key '{}'", languageKey);
            log.debug("Resolved closing message config for contactId {} with language key '{}'", closingRequest.getContactId(), languageKey);

            return ClosingResponse.builder()
                    .success(true)
                    .message(template)
                    .build();
        } catch (Exception e) {
            log.error("Error sending closing message: {}", e.getMessage(), e);
            log.debug("Error sending closing message for contactId {}: {}", closingRequest.getContactId(), e.getMessage());
            return ClosingResponse.builder()
                    .success(false)
                    .message("Something went wrong. Please try again.")
                    .build();
        }
    }

    /**
     * The legacy per-language config key {@code <baseKey>_<languageKey>}; for a language that has
     * none, the English copy ({@code <baseKey>_english}, then {@code <baseKey>}) with a WARN.
     */
    private String resolveLegacyMessage(Integer tenantId, String baseKey, String languageKey) {
        if (!"english".equals(languageKey)) {
            Optional<String> localized = tenantConfigRepository.findConfigValue(tenantId, baseKey + "_" + languageKey);
            if (localized.isPresent()) {
                return localized.get();
            }
            log.warn("chatbot_translation_missing tenantId={} configKey={}_{} fallback=en",
                    tenantId, baseKey, languageKey);
        }
        return tenantConfigRepository.findConfigValue(tenantId, baseKey + "_english")
                .or(() -> tenantConfigRepository.findConfigValue(tenantId, baseKey))
                .orElseThrow(() -> new IllegalStateException(
                        baseKey + " is not configured. Add " + baseKey + "_" + languageKey + " or " + baseKey));
    }
}
