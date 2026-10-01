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

            Optional<String> selectedLanguageOpt = Optional.of(operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId));
            String languageKey = selectedLanguageOpt
                    .map(localizationService::normalizeLanguageKey)
                    .orElse("english");

            String template = templatesService
                    .resolveScreenMessage(tenantId, "INTRO_MESSAGE", languageKey)
                    .orElseGet(() -> resolveLegacyIntroMessage(tenantId, selectedLanguageOpt, languageKey));

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
            Optional<String> selectedLanguageOpt = Optional.of(operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId));
            String languageKey = selectedLanguageOpt
                    .map(localizationService::normalizeLanguageKey)
                    .orElse("english");

            String template = templatesService
                    .resolveScreenMessage(tenantId, "CLOSING_MESSAGE", languageKey)
                    .orElseGet(() -> resolveLegacyClosingMessage(tenantId, selectedLanguageOpt, languageKey));

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

    private String resolveLegacyIntroMessage(Integer tenantId, Optional<String> selectedLanguageOpt, String languageKey) {
        if (selectedLanguageOpt.isPresent() && !"english".equals(languageKey)) {
            return tenantConfigRepository
                    .findConfigValue(tenantId, "intro_message_" + languageKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Intro message is not configured for selected language. Add intro_message_" + languageKey));
        }
        return tenantConfigRepository
                .findConfigValue(tenantId, "intro_message_" + languageKey)
                .or(() -> tenantConfigRepository.findConfigValue(tenantId, "intro_message"))
                .orElseThrow(() -> new IllegalStateException(
                        "Intro message is not configured. Add intro_message_" + languageKey + " or intro_message"));
    }

    private String resolveLegacyClosingMessage(Integer tenantId, Optional<String> selectedLanguageOpt, String languageKey) {
        if (selectedLanguageOpt.isPresent() && !"english".equals(languageKey)) {
            return tenantConfigRepository
                    .findConfigValue(tenantId, "closing_message_" + languageKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "Closing message is not configured for selected language. Add closing_message_" + languageKey));
        }
        return tenantConfigRepository
                .findConfigValue(tenantId, "closing_message_" + languageKey)
                .or(() -> tenantConfigRepository.findConfigValue(tenantId, "closing_message"))
                .orElseThrow(() -> new IllegalStateException(
                        "Closing message is not configured. Add closing_message_" + languageKey + " or closing_message"));
    }
}
