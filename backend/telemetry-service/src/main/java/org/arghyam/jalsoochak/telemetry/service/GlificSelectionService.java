package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedChannelRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedItemRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedLanguageRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.SelectedSchemeRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.SelectionResponse;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetrySchemeOption;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserChannelPreferenceRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserLanguagePreferenceRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
@Slf4j
public class GlificSelectionService {

    private static final String[] SMALL_NUMBERS = {
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen"
    };

    private static final String[] TENS = {
            "", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety"
    };

    private final GlificOperatorContextService operatorContextService;
    private final GlificLocalizationService localizationService;
    private final TenantConfigRepository tenantConfigRepository;
    private final GlificMessageTemplatesService templatesService;
    private final TelemetryTenantRepository telemetryTenantRepository;
    private final UserChannelPreferenceRepository userChannelPreferenceRepository;
    private final UserLanguagePreferenceRepository userLanguagePreferenceRepository;
    private final GlificContactSyncService glificContactSyncService;
    private final ObjectMapper objectMapper;

    public GlificSelectionService(GlificOperatorContextService operatorContextService,
                                  GlificLocalizationService localizationService,
                                  TenantConfigRepository tenantConfigRepository,
                                  GlificMessageTemplatesService templatesService,
                                  TelemetryTenantRepository telemetryTenantRepository,
                                  UserChannelPreferenceRepository userChannelPreferenceRepository,
                                  UserLanguagePreferenceRepository userLanguagePreferenceRepository,
                                  GlificContactSyncService glificContactSyncService,
                                  ObjectMapper objectMapper) {
        this.operatorContextService = operatorContextService;
        this.localizationService = localizationService;
        this.tenantConfigRepository = tenantConfigRepository;
        this.templatesService = templatesService;
        this.telemetryTenantRepository = telemetryTenantRepository;
        this.userChannelPreferenceRepository = userChannelPreferenceRepository;
        this.userLanguagePreferenceRepository = userLanguagePreferenceRepository;
        this.glificContactSyncService = glificContactSyncService;
        this.objectMapper = objectMapper;
    }

    public IntroResponse languageSelectionMessage(IntroRequest request) {
        try {
            if (request.getContactId() == null || request.getContactId().isBlank()) {
                throw new IllegalStateException("contactId is required");
            }

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(request.getContactId());
            Integer tenantId = operatorWithSchema.operator().tenantId();
            if (tenantId == null) {
                throw new IllegalStateException("Operator tenant could not be resolved");
            }

            String languageKey = localizationService.normalizeLanguageKey(
                    operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId)
            );
            String prompt = templatesService.resolveScreenPrompt(tenantId, "LANGUAGE_SELECTION", languageKey)
                    .or(() -> tenantConfigRepository.findLanguageSelectionPrompt(tenantId, languageKey))
                    .orElseThrow(() -> new IllegalStateException("language_selection_prompt is not configured"));

            List<GlificMessageTemplatesService.TemplateOption> languageTemplateOptions =
                    templatesService.resolveScreenOptions(tenantId, "LANGUAGE_SELECTION");
            List<String> languageOptions;
            if (!languageTemplateOptions.isEmpty()) {
                languageOptions = languageTemplateOptions.stream()
                        .map(opt -> opt.labelForLanguageKey(languageKey))
                        .toList();
            } else {
                languageOptions = tenantConfigRepository.findLanguageOptions(tenantId);
            }
            if (languageOptions.isEmpty()) {
                throw new IllegalStateException("No language options configured. Add language_1, language_2, ...");
            }

            StringBuilder message = new StringBuilder(prompt.trim());
            for (int i = 0; i < languageOptions.size(); i++) {
                message.append("\n")
                        .append(i + 1)
                        .append(". ")
                        .append(languageOptions.get(i));
            }

            return IntroResponse.builder()
                    .success(true)
                    .message(message.toString())
                    .correlationId(toWords(languageOptions.size()))
                    .build();
        } catch (Exception e) {
            log.error("Error building language selection message: {}", e.getMessage(), e);
            log.debug("Error building language selection message for contactId {}: {}", request.getContactId(), e.getMessage());
            return IntroResponse.builder()
                    .success(false)
                    .message("Language selection could not be prepared.")
                    .build();
        }
    }

    public IntroResponse selectedLanguageMessage(SelectedLanguageRequest request) {
        try {
            if (request.getContactId() == null || request.getContactId().isBlank()) {
                throw new IllegalStateException("contactId is required");
            }
            if (request.getLanguage() == null || request.getLanguage().isBlank()) {
                throw new IllegalStateException("language selection is required");
            }

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(request.getContactId());
            Integer tenantId = operatorWithSchema.operator().tenantId();
            if (tenantId == null) {
                throw new IllegalStateException("Operator tenant could not be resolved");
            }

            String currentLanguageKey = localizationService.normalizeLanguageKey(
                    operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId)
            );

            List<GlificMessageTemplatesService.TemplateOption> languageTemplateOptions =
                    templatesService.resolveScreenOptions(tenantId, "LANGUAGE_SELECTION");
            String selectedLanguage;
            int selectedLanguageId;
            String selectedLanguageKey;
            String selectedLanguageDisplayLabel;
            if (!languageTemplateOptions.isEmpty()) {
                int selectedIndex = resolveTemplateSelectionIndex(request.getLanguage(), languageTemplateOptions, currentLanguageKey)
                        .orElseThrow(() -> new IllegalStateException("Invalid language selection"));
                GlificMessageTemplatesService.TemplateOption selectedOpt = languageTemplateOptions.get(selectedIndex);
                selectedLanguageId = selectedOpt.order() > 0 ? selectedOpt.order() : (selectedIndex + 1);
                // Persist a canonical label (prefer English) so downstream normalization/sync keeps working.
                selectedLanguage = selectedOpt.canonicalLabel();
                selectedLanguageKey = localizationService.normalizeLanguageKey(selectedLanguage);
                selectedLanguageDisplayLabel = selectedOpt.labelForLanguageKey(selectedLanguageKey);
            } else {
                List<String> languageOptions = tenantConfigRepository.findLanguageOptions(tenantId);
                if (languageOptions.isEmpty()) {
                    throw new IllegalStateException("No language options configured for tenant");
                }
                selectedLanguage = resolveSelection(request.getLanguage(), languageOptions)
                        .orElseThrow(() -> new IllegalStateException("Invalid language selection"));
                selectedLanguageId = languageOptions.indexOf(selectedLanguage) + 1;
                selectedLanguageKey = localizationService.normalizeLanguageKey(selectedLanguage);
                selectedLanguageDisplayLabel = selectedLanguage;
            }

            telemetryTenantRepository.updateUserLanguageId(
                    operatorWithSchema.schemaName(),
                    operatorWithSchema.operator().id(),
                    selectedLanguageId
            );
            userLanguagePreferenceRepository.upsert(tenantId, request.getContactId(), selectedLanguage);
            glificContactSyncService.syncContactLanguageAsync(request.getContactId(), selectedLanguage);

            String confirmationTemplate = templatesService
                    .resolveScreenConfirmationTemplate(tenantId, "LANGUAGE_SELECTION", selectedLanguageKey)
                    .or(() -> tenantConfigRepository.findConfigValue(tenantId, "language_selection_confirmation_template_" + selectedLanguageKey))
                    .or(() -> tenantConfigRepository.findConfigValue(tenantId, "language_selection_confirmation_template"))
                    .orElse("Language selected: {language}");

            String localizedConfirmation = localizationService.localizeMessage(
                    confirmationTemplate.replace("{language}", selectedLanguageDisplayLabel),
                    selectedLanguageKey
            );

            return IntroResponse.builder()
                    .success(true)
                    .message(localizedConfirmation)
                    .build();
        } catch (Exception e) {
            log.error("Error saving selected language: {}", e.getMessage(), e);
            log.debug("Error saving selected language for contactId {}: {}", request.getContactId(), e.getMessage());
            String languageKey = localizationService.resolveLanguageKeyForContact(request.getContactId());
            return IntroResponse.builder()
                    .success(false)
                    .message(localizationService.resolveUserFacingErrorMessage(e, "Language selection could not be saved.", languageKey))
                    .build();
        }
    }

    public IntroResponse channelSelectionMessage(IntroRequest request) {
        try {
            if (request.getContactId() == null || request.getContactId().isBlank()) {
                throw new IllegalStateException("contactId is required");
            }

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(request.getContactId());
            Integer tenantId = operatorWithSchema.operator().tenantId();
            if (tenantId == null) {
                throw new IllegalStateException("Operator tenant could not be resolved");
            }

            String languageKey = localizationService.normalizeLanguageKey(operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId));
            String prompt = templatesService.resolveScreenPrompt(tenantId, "CHANNEL_SELECTION", languageKey)
                    .or(() -> tenantConfigRepository.findChannelSelectionPrompt(tenantId, languageKey))
                    .orElse("Please select your preferred channel by typing the corresponding number:");

            List<GlificMessageTemplatesService.TemplateOption> channelTemplateOptions =
                    templatesService.resolveScreenOptions(tenantId, "CHANNEL_SELECTION");
            List<String> channelOptions = resolveSupportedChannelOptions(tenantId, languageKey, channelTemplateOptions);
            if (channelOptions.isEmpty()) {
                throw new IllegalStateException("No channel options configured. Configure TENANT_SUPPORTED_CHANNELS or channel_1/channel_2.");
            }

            StringBuilder message = new StringBuilder(prompt.trim());
            for (int i = 0; i < channelOptions.size(); i++) {
                message.append("\n")
                        .append(i + 1)
                        .append(". ")
                        .append(channelOptions.get(i));
            }

            int correlationCount = channelOptions.size();
            boolean hasBfmOrElectric = channelOptions.stream().anyMatch(option -> isBfmChannel(option) || isElectricChannel(option));
            String correlationWord = toWords(correlationCount);
            String isBfmOrIsElectricValue = buildBfmOrElectricCorrelationFlag(hasBfmOrElectric, correlationWord);

            return IntroResponse.builder()
                    .success(true)
                    .message(message.toString())
                    .correlationId(correlationWord)
                    .isBfmOrIsElectric(isBfmOrIsElectricValue)
                    .build();
        } catch (Exception e) {
            log.error("Error building channel selection message: {}", e.getMessage(), e);
            log.debug("Error building channel selection message for contactId {}: {}", request.getContactId(), e.getMessage());
            return IntroResponse.builder()
                    .success(false)
                    .message("Channel selection could not be prepared.")
                    .build();
        }
    }

    public IntroResponse selectedChannelMessage(SelectedChannelRequest request) {
        try {
            if (request.getContactId() == null || request.getContactId().isBlank()) {
                throw new IllegalStateException("contactId is required");
            }
            if (request.getChannel() == null || request.getChannel().isBlank()) {
                throw new IllegalStateException("channel selection is required");
            }

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(request.getContactId());
            Integer tenantId = operatorWithSchema.operator().tenantId();
            if (tenantId == null) {
                throw new IllegalStateException("Operator tenant could not be resolved");
            }

            String languageKey = localizationService.normalizeLanguageKey(operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId));

            List<GlificMessageTemplatesService.TemplateOption> channelTemplateOptions =
                    templatesService.resolveScreenOptions(tenantId, "CHANNEL_SELECTION");
            List<String> channelOptions = resolveSupportedChannelOptions(tenantId, languageKey, channelTemplateOptions);
            String selectedChannel;
            int selectedChannelId;
            if (!channelOptions.isEmpty()) {
                selectedChannel = resolveSelection(request.getChannel(), channelOptions)
                        .orElseThrow(() -> new IllegalStateException("Invalid channel selection"));
                selectedChannelId = channelOptions.indexOf(selectedChannel) + 1;
            } else if (!channelTemplateOptions.isEmpty()) {
                int selectedIndex = resolveTemplateSelectionIndex(request.getChannel(), channelTemplateOptions, languageKey)
                        .orElseThrow(() -> new IllegalStateException("Invalid channel selection"));
                GlificMessageTemplatesService.TemplateOption selectedOpt = channelTemplateOptions.get(selectedIndex);
                selectedChannel = selectedOpt.labelForLanguageKey(languageKey);
                selectedChannelId = selectedOpt.order() > 0 ? selectedOpt.order() : (selectedIndex + 1);
            } else {
                List<String> legacyChannelOptions = tenantConfigRepository.findChannelOptions(tenantId, languageKey);
                if (legacyChannelOptions.isEmpty()) {
                    throw new IllegalStateException("No channel options configured for tenant");
                }
                selectedChannel = resolveSelection(request.getChannel(), legacyChannelOptions)
                        .orElseThrow(() -> new IllegalStateException("Invalid channel selection"));
                selectedChannelId = legacyChannelOptions.indexOf(selectedChannel) + 1;
            }
            Long schemeId = telemetryTenantRepository
                    .findFirstSchemeForUser(operatorWithSchema.schemaName(), operatorWithSchema.operator().id())
                    .orElseThrow(() -> new IllegalStateException("Operator is not mapped to any scheme"));
            telemetryTenantRepository.updateSchemeChannel(operatorWithSchema.schemaName(), schemeId, selectedChannelId);
            userChannelPreferenceRepository.upsert(tenantId, request.getContactId(), selectedChannel);

            String confirmationTemplate = templatesService
                    .resolveScreenConfirmationTemplate(tenantId, "CHANNEL_SELECTION", languageKey)
                    .or(() -> tenantConfigRepository.findConfigValue(tenantId, "channel_selection_confirmation_template_" + languageKey))
                    .or(() -> tenantConfigRepository.findConfigValue(tenantId, "channel_selection_confirmation_template"))
                    .orElse("Channel selected: {channel}");

            boolean isBfm = isBfmChannel(selectedChannel);
            boolean isElectrical = isElectricChannel(selectedChannel);
            boolean isBfmorIsElectrical = isBfm || isElectrical;

            return IntroResponse.builder()
                    .success(true)
                    .message(confirmationTemplate.replace("{channel}", selectedChannel))
                    .isBfmorIsElectrical(isBfmorIsElectrical)
                    .isBfm(isBfm)
                    .isElectrical(isElectrical)
                    .build();
        } catch (Exception e) {
            log.error("Error saving selected channel: {}", e.getMessage(), e);
            log.debug("Error saving selected channel for contactId {}: {}", request.getContactId(), e.getMessage());
            String languageKey = localizationService.resolveLanguageKeyForContact(request.getContactId());
            return IntroResponse.builder()
                    .success(false)
                    .message(localizationService.resolveUserFacingErrorMessage(e, "Channel selection could not be saved.", languageKey))
                    .build();
        }
    }

    public IntroResponse schemeSelectionMessage(IntroRequest request) {
        try {
            if (request.getContactId() == null || request.getContactId().isBlank()) {
                throw new IllegalStateException("contactId is required");
            }

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(request.getContactId());
            List<TelemetrySchemeOption> schemes = telemetryTenantRepository.findSchemesForUser(
                    operatorWithSchema.schemaName(),
                    operatorWithSchema.operator().id()
            );
            if (schemes.isEmpty()) {
                throw new IllegalStateException("Operator is not mapped to any scheme");
            }

            StringBuilder message = new StringBuilder("Please select a scheme:");
            for (int i = 0; i < schemes.size(); i++) {
                String schemeName = schemes.get(i).name();
                if (schemeName == null || schemeName.isBlank()) {
                    schemeName = "Scheme " + schemes.get(i).id();
                }
                message.append("\n")
                        .append(i + 1)
                        .append(". ")
                        .append(schemeName);
            }

            return IntroResponse.builder()
                    .success(true)
                    .message(message.toString())
                    .isSchemeGreaterThanOne(schemes.size() > 1)
                    .build();
        } catch (Exception e) {
            log.error("Error building scheme selection message: {}", e.getMessage(), e);
            log.debug("Error building scheme selection message for contactId {}: {}", request.getContactId(), e.getMessage());
            String languageKey = localizationService.resolveLanguageKeyForContact(request.getContactId());
            return IntroResponse.builder()
                    .success(false)
                    .message(localizationService.resolveUserFacingErrorMessage(
                            e,
                            "Scheme selection could not be prepared.",
                            languageKey
                    ))
                    .build();
        }
    }

    public IntroResponse selectedSchemeMessage(SelectedSchemeRequest request) {
        try {
            if (request.getContactId() == null || request.getContactId().isBlank()) {
                throw new IllegalStateException("contactId is required");
            }
            if (request.getScheme() == null || request.getScheme().isBlank()) {
                throw new IllegalStateException("scheme selection is required");
            }

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(request.getContactId());
            List<TelemetrySchemeOption> schemes = telemetryTenantRepository.findSchemesForUser(
                    operatorWithSchema.schemaName(),
                    operatorWithSchema.operator().id()
            );
            if (schemes.isEmpty()) {
                throw new IllegalStateException("Operator is not mapped to any scheme");
            }

            TelemetrySchemeOption selectedScheme = resolveSchemeSelection(request.getScheme(), schemes)
                    .orElseThrow(() -> new IllegalStateException("Invalid scheme selection"));

            String correlationId = telemetryTenantRepository.upsertPendingSchemeSelectionRecord(
                    operatorWithSchema.schemaName(),
                    selectedScheme.id(),
                    operatorWithSchema.operator().id(),
                    java.time.LocalDateTime.now()
            );

            String schemeName = selectedScheme.name();
            if (schemeName == null || schemeName.isBlank()) {
                schemeName = "Scheme " + selectedScheme.id();
            }

            return IntroResponse.builder()
                    .success(true)
                    .message("Scheme selected: " + schemeName)
                    .correlationId(correlationId)
                    .selected(String.valueOf(selectedScheme.id()))
                    .build();
        } catch (Exception e) {
            log.error("Error saving selected scheme: {}", e.getMessage(), e);
            log.debug("Error saving selected scheme for contactId {}: {}", request.getContactId(), e.getMessage());
            String languageKey = localizationService.resolveLanguageKeyForContact(request.getContactId());
            return IntroResponse.builder()
                    .success(false)
                    .message(localizationService.resolveUserFacingErrorMessage(
                            e,
                            "Scheme selection could not be saved.",
                            languageKey
                    ))
                    .build();
        }
    }

    public IntroResponse itemSelectionMessage(IntroRequest request) {
        try {
            if (request.getContactId() == null || request.getContactId().isBlank()) {
                throw new IllegalStateException("contactId is required");
            }

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(request.getContactId());
            Integer tenantId = operatorWithSchema.operator().tenantId();
            System.out.println("tenant id " + tenantId);
            if (tenantId == null) {
                throw new IllegalStateException("Operator tenant could not be resolved");
            }

            String languageKey = localizationService.normalizeLanguageKey(operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId));

            String prompt = templatesService.resolveScreenPrompt(tenantId, "ITEM_SELECTION", languageKey)
                    .or(() -> tenantConfigRepository.findItemSelectionPrompt(tenantId, languageKey))
                    .orElse("Please select what you want to do:");

            List<GlificMessageTemplatesService.TemplateOption> itemTemplateOptions =
                    templatesService.resolveScreenOptions(tenantId, "ITEM_SELECTION");
            List<VisibleItemOption> visibleItemOptions;
            if (!itemTemplateOptions.isEmpty()) {
                visibleItemOptions = buildVisibleItemOptionsFromTemplates(tenantId, languageKey, itemTemplateOptions);
            } else {
                List<String> itemOptions = tenantConfigRepository.findItemOptions(tenantId, languageKey);
                if (itemOptions.isEmpty()) {
                    throw new IllegalStateException("No item options configured. Add item_1/item_2... or language-specific keys.");
                }
                visibleItemOptions = buildVisibleItemOptions(tenantId, languageKey, itemOptions);
            }

            StringBuilder message = new StringBuilder(prompt.trim());
            for (int i = 0; i < visibleItemOptions.size(); i++) {
                message.append("\n")
                        .append(i + 1)
                        .append(". ")
                        .append(visibleItemOptions.get(i).label());
            }

            return IntroResponse.builder()
                    .success(true)
                    .message(message.toString())
                    .build();
        } catch (Exception e) {
            log.error("Error building item selection message: {}", e.getMessage(), e);
            log.debug("Error building item selection message for contactId {}: {}", request.getContactId(), e.getMessage());
            String languageKey = localizationService.resolveLanguageKeyForContact(request.getContactId());
            return IntroResponse.builder()
                    .success(false)
                    .message(localizationService.resolveUserFacingErrorMessage(
                            e,
                            "Item selection could not be prepared.",
                            languageKey
                    ))
                    .build();
        }
    }

    public SelectionResponse selectedItemMessage(SelectedItemRequest request) {
        try {
            if (request.getContactId() == null || request.getContactId().isBlank()) {
                throw new IllegalStateException("contactId is required");
            }
            if (request.getChannel() == null || request.getChannel().isBlank()) {
                throw new IllegalStateException("item selection is required");
            }

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(request.getContactId());
            Integer tenantId = operatorWithSchema.operator().tenantId();
            if (tenantId == null) {
                throw new IllegalStateException("Operator tenant could not be resolved");
            }

            String languageKey = localizationService.normalizeLanguageKey(operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId));

            List<GlificMessageTemplatesService.TemplateOption> itemTemplateOptions =
                    templatesService.resolveScreenOptions(tenantId, "ITEM_SELECTION");
            List<VisibleItemOption> visibleItemOptions;
            if (!itemTemplateOptions.isEmpty()) {
                visibleItemOptions = buildVisibleItemOptionsFromTemplates(tenantId, languageKey, itemTemplateOptions);
            } else {
                List<String> itemOptions = tenantConfigRepository.findItemOptions(tenantId, languageKey);
                if (itemOptions.isEmpty()) {
                    throw new IllegalStateException("No item options configured for tenant");
                }
                visibleItemOptions = buildVisibleItemOptions(tenantId, languageKey, itemOptions);
            }

            int selectedIndex = resolveSelectionIndex(
                    request.getChannel(),
                    visibleItemOptions.stream().map(VisibleItemOption::label).toList()
            ).orElseThrow(() -> new IllegalStateException("Invalid item selection"));

            VisibleItemOption selectedItem = visibleItemOptions.get(selectedIndex);
            String selectedItemLabel = selectedItem.label();
            String selectedCode = selectedItem.code();

            String template = templatesService
                    .resolveScreenConfirmationTemplate(tenantId, "ITEM_SELECTION", languageKey)
                    .or(() -> tenantConfigRepository.findConfigValue(tenantId, "item_selection_confirmation_template_" + languageKey))
                    .or(() -> tenantConfigRepository.findConfigValue(tenantId, "item_selection_confirmation_template"))
                    .orElse("{item} selected");

            String message = template
                    .replace("{item}", selectedItemLabel)
                    .replace("{selected}", selectedCode);

            String responseSelectedCode = selectedCode;
            if ("readingSubmission".equals(selectedCode)) {
                boolean isFirstOptionSelected = selectedIndex == 0;
                boolean locationCheckRequiredYes = isLocationCheckRequiredYes(tenantId);
                Long schemeId = telemetryTenantRepository
                        .findFirstSchemeForUser(operatorWithSchema.schemaName(), operatorWithSchema.operator().id())
                        .orElse(null);
                boolean schemeHasLatLng = schemeId != null
                        && telemetryTenantRepository.schemeHasLatitudeAndLongitude(operatorWithSchema.schemaName(), schemeId);

                // Only allow the "readingSubmission" selection to flow through when:
                // 1 is selected, LOCATION_CHECK_REQUIRED is YES, and scheme has latitude + longitude.
                if (!(isFirstOptionSelected && locationCheckRequiredYes && schemeHasLatLng)) {
                    responseSelectedCode = "readingSubmissionLocationNotSelected";
                }
            }

            return SelectionResponse.builder()
                    .success(true)
                    .selected(responseSelectedCode)
                    .message(message)
                    .build();
        } catch (Exception e) {
            log.error("Error processing selected item: {}", e.getMessage(), e);
            log.debug("Error processing selected item for contactId {}: {}", request.getContactId(), e.getMessage());
            String languageKey = localizationService.resolveLanguageKeyForContact(request.getContactId());
            return SelectionResponse.builder()
                    .success(false)
                    .selected(null)
                    .message(localizationService.resolveUserFacingErrorMessage(e, "Item selection could not be saved.", languageKey))
                    .build();
        }
    }

    private boolean isLocationCheckRequiredYes(Integer tenantId) {
        String raw = tenantConfigRepository.findConfigValue(tenantId, "LOCATION_CHECK_REQUIRED").orElse(null);
        String value = extractSimpleConfigValue(raw).orElse(raw == null ? null : raw.trim());
        if (value == null || value.isBlank()) {
            return false;
        }
        return value.trim().equalsIgnoreCase("YES");
    }

    private Optional<String> extractSimpleConfigValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null) {
                return Optional.empty();
            }
            if (node.isTextual()) {
                return Optional.ofNullable(node.asText());
            }
            JsonNode valueNode = node.get("value");
            if (valueNode != null && !valueNode.isNull()) {
                return Optional.ofNullable(valueNode.asText());
            }
        } catch (Exception ignored) {
            // Fall back to raw string value.
        }
        return Optional.empty();
    }

    private Optional<String> resolveSelection(String rawSelection, List<String> options) {
        return resolveSelectionIndex(rawSelection, options).map(index -> options.get(index));
    }

    private Optional<Integer> resolveSelectionIndex(String rawSelection, List<String> options) {
        String value = rawSelection.trim();
        int digitEnd = 0;
        while (digitEnd < value.length() && Character.isDigit(value.charAt(digitEnd))) {
            digitEnd++;
        }
        if (digitEnd > 0) {
            int index = Integer.parseInt(value.substring(0, digitEnd));
            if (index >= 1 && index <= options.size()) {
                return Optional.of(index - 1);
            }
        }
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equalsIgnoreCase(value)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    private Optional<TelemetrySchemeOption> resolveSchemeSelection(String rawSelection, List<TelemetrySchemeOption> options) {
        String value = rawSelection.trim();

        Optional<Integer> selectedIndex = resolveSelectionIndex(
                value,
                options.stream().map(option -> option.name() == null ? "" : option.name()).toList()
        );
        if (selectedIndex.isPresent()) {
            return Optional.of(options.get(selectedIndex.get()));
        }

        if (value.matches("^\\d+$")) {
            long selectedSchemeId = Long.parseLong(value);
            return options.stream().filter(option -> option.id() != null && option.id() == selectedSchemeId).findFirst();
        }

        return options.stream()
                .filter(option -> option.name() != null && option.name().equalsIgnoreCase(value))
                .findFirst();
    }

    private String toWords(int number) {
        if (number == 0) {
            return "zero";
        }
        return toWordsInternal(number).trim();
    }

    private String toWordsInternal(int number) {
        if (number < 0) {
            return "minus " + toWordsInternal(-number);
        }
        if (number < 20) {
            return SMALL_NUMBERS[number];
        }
        if (number < 100) {
            String tensWord = TENS[(number / 10)];
            int remainder = number % 10;
            if (remainder == 0) {
                return tensWord;
            }
            return tensWord + " " + SMALL_NUMBERS[remainder];
        }
        if (number < 1_000) {
            int remainder = number % 100;
            String result = SMALL_NUMBERS[number / 100] + " hundred";
            if (remainder == 0) {
                return result;
            }
            return result + " " + toWordsInternal(remainder);
        }
        if (number < 1_000_000) {
            return withRemainder(number, 1_000, "thousand");
        }
        if (number < 1_000_000_000) {
            return withRemainder(number, 1_000_000, "million");
        }
        return withRemainder(number, 1_000_000_000, "billion");
    }

    private String withRemainder(int number, int divisor, String unit) {
        int remainder = number % divisor;
        String result = toWordsInternal(number / divisor) + " " + unit;
        if (remainder == 0) {
            return result;
        }
        return result + " " + toWordsInternal(remainder);
    }

    private String buildBfmOrElectricCorrelationFlag(boolean hasBfmOrElectric, String correlationWord) {
        if (correlationWord == null || correlationWord.isBlank()) {
            return null;
        }
        String normalized = correlationWord.trim();
        String capitalized = normalized.substring(0, 1).toUpperCase(Locale.ROOT) + normalized.substring(1);
        if (hasBfmOrElectric) {
            return "bfmOrElectricpresentandcorrelationIs" + capitalized;
        }
        return "bfmOrElectricNotpresentandcorrelationIs" + capitalized;
    }

    private String toItemCode(String selectedItemLabel, List<String> itemOptions) {
        String normalized = selectedItemLabel == null
                ? ""
                : selectedItemLabel.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]+", " ").trim();

        if (normalized.contains("submit") || normalized.contains("reading")) {
            return "readingSubmission";
        }
        if (normalized.contains("report") || normalized.contains("issue")) {
            return "reportIssue";
        }
        if (normalized.contains("language")) {
            return "languageChange";
        }
        if (normalized.contains("channel")) {
            return "channelChange";
        }

        // Backward-compatible fallback for legacy indexed tenant configs.
        int index = -1;
        for (int i = 0; i < itemOptions.size(); i++) {
            if (itemOptions.get(i).equalsIgnoreCase(selectedItemLabel)) {
                index = i + 1;
                break;
            }
        }
        return switch (index) {
            case 1 -> "readingSubmission";
            case 2 -> "reportIssue";
            case 3 -> "languageChange";
            case 4 -> "channelChange";
            default -> localizationService.normalizeLanguageKey(selectedItemLabel);
        };
    }

    private Optional<Integer> resolveTemplateSelectionIndex(String rawSelection,
                                                           List<GlificMessageTemplatesService.TemplateOption> options,
                                                           String languageKeyForDisplay) {
        if (rawSelection == null || rawSelection.isBlank()) {
            return Optional.empty();
        }
        String value = rawSelection.trim();
        int digitEnd = 0;
        while (digitEnd < value.length() && Character.isDigit(value.charAt(digitEnd))) {
            digitEnd++;
        }
        if (digitEnd > 0) {
            int index = Integer.parseInt(value.substring(0, digitEnd));
            if (index >= 1 && index <= options.size()) {
                return Optional.of(index - 1);
            }
        }

        // Try display labels first.
        for (int i = 0; i < options.size(); i++) {
            String display = options.get(i).labelForLanguageKey(languageKeyForDisplay);
            if (display != null && display.equalsIgnoreCase(value)) {
                return Optional.of(i);
            }
        }
        // Then match any localized label (including canonical).
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).matchesAnyLabel(value)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    private List<String> resolveSupportedChannelOptions(Integer tenantId,
                                                        String languageKey,
                                                        List<GlificMessageTemplatesService.TemplateOption> channelTemplateOptions) {
        List<String> supportedChannels = resolveTenantSupportedChannels(tenantId);
        if (!supportedChannels.isEmpty()) {
            return supportedChannels;
        }
        if (!channelTemplateOptions.isEmpty()) {
            return channelTemplateOptions.stream()
                    .map(opt -> opt.labelForLanguageKey(languageKey))
                    .toList();
        }
        return tenantConfigRepository.findChannelOptions(tenantId, languageKey);
    }

    private List<String> resolveTenantSupportedChannels(Integer tenantId) {
        Optional<String> rawOpt = tenantConfigRepository.findConfigValue(tenantId, "TENANT_SUPPORTED_CHANNELS");
        if (rawOpt.isEmpty()) {
            return List.of();
        }
        String raw = rawOpt.get();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null || node.isNull()) {
                return List.of();
            }
            JsonNode channelsNode = node;
            if (node.has("channels")) {
                channelsNode = node.get("channels");
            }
            if (channelsNode != null && channelsNode.isArray()) {
                List<String> channels = new ArrayList<>();
                for (JsonNode child : channelsNode) {
                    if (child != null && child.isTextual()) {
                        String value = child.asText();
                        if (value != null && !value.isBlank()) {
                            channels.add(value.trim());
                        }
                    }
                }
                return channels;
            }
            if (channelsNode != null && channelsNode.isTextual()) {
                String value = channelsNode.asText();
                if (value != null && !value.isBlank()) {
                    return List.of(value.trim());
                }
            }
        } catch (Exception e) {
            log.warn("Invalid TENANT_SUPPORTED_CHANNELS JSON for tenantId {}: {}", tenantId, e.getMessage());
        }
        return List.of();
    }

    private List<VisibleItemOption> buildVisibleItemOptionsFromTemplates(Integer tenantId,
                                                                         String languageKey,
                                                                         List<GlificMessageTemplatesService.TemplateOption> itemOptions) {
        List<GlificMessageTemplatesService.TemplateOption> channelTemplateOptions =
                templatesService.resolveScreenOptions(tenantId, "CHANNEL_SELECTION");
        int channelCount = resolveSupportedChannelOptions(tenantId, languageKey, channelTemplateOptions).size();
        if (channelCount == 0) {
            channelCount = !channelTemplateOptions.isEmpty()
                    ? channelTemplateOptions.size()
                    : tenantConfigRepository.findChannelOptions(tenantId, languageKey).size();
        }
        boolean showChannelChange = channelCount > 1;

        List<GlificMessageTemplatesService.TemplateOption> languageOptions =
                templatesService.resolveScreenOptions(tenantId, "LANGUAGE_SELECTION");
        int languageCount = !languageOptions.isEmpty()
                ? languageOptions.size()
                : tenantConfigRepository.findLanguageOptions(tenantId).size();
        boolean showLanguageChange = languageCount > 1;

        List<VisibleItemOption> filtered = new ArrayList<>();
        for (int i = 0; i < itemOptions.size(); i++) {
            GlificMessageTemplatesService.TemplateOption opt = itemOptions.get(i);
            String itemCode = toItemCode(opt, itemOptions, i);
            if ("channelChange".equals(itemCode) && !showChannelChange) {
                continue;
            }
            if ("languageChange".equals(itemCode) && !showLanguageChange) {
                continue;
            }
            String displayLabel = opt.labelForLanguageKey(languageKey);
            if ("reportIssue".equals(itemCode)) {
                displayLabel = normalizeIssueReportLabel(displayLabel, languageKey);
            }
            filtered.add(new VisibleItemOption(displayLabel, itemCode));
        }
        if (filtered.isEmpty()) {
            List<VisibleItemOption> fallback = new ArrayList<>();
            for (int i = 0; i < itemOptions.size(); i++) {
                GlificMessageTemplatesService.TemplateOption opt = itemOptions.get(i);
                fallback.add(new VisibleItemOption(opt.labelForLanguageKey(languageKey), toItemCode(opt, itemOptions, i)));
            }
            return fallback;
        }
        return filtered;
    }

    private String toItemCode(GlificMessageTemplatesService.TemplateOption opt,
                              List<GlificMessageTemplatesService.TemplateOption> all,
                              int index) {
        String canonical = opt == null ? null : opt.canonicalLabel();
        String normalized = canonical == null
                ? ""
                : canonical.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]+", " ").trim();

        if (normalized.contains("submit") || normalized.contains("reading")) {
            return "readingSubmission";
        }
        if (normalized.contains("report") || normalized.contains("issue")) {
            return "reportIssue";
        }
        if (normalized.contains("language")) {
            return "languageChange";
        }
        if (normalized.contains("channel")) {
            return "channelChange";
        }

        // Backward-compatible fallback for fixed workflows by index.
        int resolvedIndex = index + 1;
        return switch (resolvedIndex) {
            case 1 -> "readingSubmission";
            case 2 -> "reportIssue";
            case 3 -> "languageChange";
            case 4 -> "channelChange";
            default -> localizationService.normalizeLanguageKey(canonical);
        };
    }

    private List<VisibleItemOption> buildVisibleItemOptions(Integer tenantId,
                                                            String languageKey,
                                                            List<String> itemOptions) {
        List<String> channelOptions = resolveSupportedChannelOptions(tenantId, languageKey, List.of());
        if (channelOptions.isEmpty()) {
            channelOptions = tenantConfigRepository.findChannelOptions(tenantId, languageKey);
        }
        boolean showChannelChange = channelOptions.size() > 1;

        List<String> languageOptions = tenantConfigRepository.findLanguageOptions(tenantId);
        boolean showLanguageChange = languageOptions.size() > 1;

        List<VisibleItemOption> filtered = new ArrayList<>();
        for (String option : itemOptions) {
            String itemCode = toItemCode(option, itemOptions);
            if ("channelChange".equals(itemCode) && !showChannelChange) {
                continue;
            }
            if ("languageChange".equals(itemCode) && !showLanguageChange) {
                continue;
            }
            String displayLabel = option;
            if ("reportIssue".equals(itemCode)) {
                displayLabel = normalizeIssueReportLabel(option, languageKey);
            }
            filtered.add(new VisibleItemOption(displayLabel, itemCode));
        }
        if (filtered.isEmpty()) {
            List<VisibleItemOption> fallback = new ArrayList<>();
            for (String option : itemOptions) {
                fallback.add(new VisibleItemOption(option, toItemCode(option, itemOptions)));
            }
            return fallback;
        }
        return filtered;
    }

    private String normalizeIssueReportLabel(String option, String languageKey) {
        if (option == null) {
            return option;
        }
        String trimmed = option.trim();
        if ("hindi".equals(languageKey)) {
            if ("मीटर परिवर्तन".equalsIgnoreCase(trimmed)) {
                return "समस्या रिपोर्ट करें";
            }
            return option;
        }
        if ("meter change".equalsIgnoreCase(trimmed)) {
            return "Report Issue";
        }
        return option;
    }

    private boolean isBfmChannel(String channelOption) {
        if (channelOption == null) {
            return false;
        }
        String trimmed = channelOption.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        if (normalized.contains("bfm")) {
            return true;
        }
        // Common Hindi spelling used in tenant templates.
        String compact = trimmed.replaceAll("\\s+", "");
        return compact.contains("बीएफएम");
    }

    private boolean isElectricChannel(String channelOption) {
        if (channelOption == null) {
            return false;
        }
        String trimmed = channelOption.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        if (normalized.contains("electric")) {
            return true;
        }
        // Common Hindi spelling used in tenant templates.
        String compact = trimmed.replaceAll("\\s+", "");
        return compact.contains("इलेक्ट्रिक");
    }

    private record VisibleItemOption(String label, String code) {
    }
}
