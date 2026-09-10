package org.arghyam.jalsoochak.telemetry.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.dto.requests.IntroRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.IssueReportRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.LocationReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.ManualReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.MeterChangeRequest;
import org.arghyam.jalsoochak.telemetry.dto.requests.UpdatedPreviousReadingRequest;
import org.arghyam.jalsoochak.telemetry.dto.response.CreateReadingResponse;
import org.arghyam.jalsoochak.telemetry.dto.response.IntroResponse;
import org.arghyam.jalsoochak.telemetry.event.TelemetryEventPublisher;
import org.arghyam.jalsoochak.telemetry.repository.TenantAnomalyRecord;
import org.arghyam.jalsoochak.telemetry.repository.TenantConfigRepository;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryCompletedFlowReading;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryConfirmedReadingSnapshot;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryFlowReadingDetails;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryPendingMeterChangeRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryReadingRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetrySchemeSelectionRecord;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.repository.UserChannelPreferenceRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.arghyam.jalsoochak.telemetry.util.ReadingTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Slf4j
public class GlificMeterWorkflowService {
    /**
     * Allowlist for operator-supplied free-text issue reasons.
     *
     * <p>{@code \p{L}}, {@code \p{N}} and {@code \p{M}} are Unicode categories, so Devanagari and
     * every other Indic script pass while {@code <}, {@code >}, quotes, control characters and
     * zero-width or bidi-override characters are refused.
     *
     * <p><strong>{@code \p{M}} is not optional.</strong> Indic vowel signs and the virama are
     * combining marks in category {@code Mn}/{@code Mc}, not letters — "पानी" is
     * {@code Lo Mc Lo Mc}. Without {@code \p{M}} this pattern rejects essentially all real Hindi
     * text, which is what it did until a CWE-20 remediation added the category: Hindi-speaking
     * operators could not file a free-text reason at all. Any future edit here must be checked
     * against a non-Latin script, not just ASCII.
     *
     * <p><strong>A mark is only ever allowed after a base character.</strong> Every space-separated
     * token must open with a letter or number, and {@code \p{M}} is admitted only from the second
     * character of a token onwards. A combining mark carries no meaning alone — no real Indic word
     * starts with a vowel sign or a virama — and until this was tightened {@code \p{M}} sat in the
     * leading character class, so a reason consisting only of marks passed the allowlist and was
     * stored to be rendered stacked over whatever text displayed it.
     *
     * <p>Deliberately admits <strong>no punctuation</strong> — not even a comma — which is a
     * knowing trade-off: it keeps the rule identical across every free-text reason path, and the
     * localised operator-facing copy at {@code GlificLocalizationService} ("Issue reason can only
     * contain letters, numbers, and spaces.") states this rule verbatim. <strong>Widening this
     * pattern to punctuation means rewriting that copy in every configured language</strong>; the
     * two must change together. Combining marks need no copy change — a reader sees them as part
     * of a letter.
     *
     * <p>Applied only to input that matched no configured reason — see
     * {@link #requireStorableIssueReason}.
     */
    private static final Pattern ISSUE_REASON_ALLOWED = Pattern.compile(
            "^[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}]*(?: +[\\p{L}\\p{N}][\\p{L}\\p{N}\\p{M}]*)*$");
    private static final String DEFAULT_ISSUE_PROMPT_ENGLISH =
            "Please select your issue by typing any of the number";
    private static final String DEFAULT_ISSUE_PROMPT_HINDI =
            "कृपया नंबर टाइप करके अपनी समस्या चुनें";
    private static final String DEFAULT_OTHERS_PROMPT_ENGLISH =
            "Please report your issue.";
    private static final String DEFAULT_OTHERS_PROMPT_HINDI =
            "कृपया अपनी समस्या बताएं।";
    private static final String LEGACY_ISSUE_PROMPT_ENGLISH =
            "Please type your issue in a few words.";
    private static final String LEGACY_ISSUE_PROMPT_HINDI =
            "कृपया अपनी समस्या संक्षेप में लिखें।";
    private static final String TELEMETRY_ISSUE_PROMPT_ENGLISH =
            "Please select your issue.";
    private static final String TELEMETRY_ISSUE_PROMPT_HINDI =
            "कृपया अपनी समस्या चुनें।";
    private static final String DEFAULT_METER_CHANGE_PROMPT_ENGLISH =
            "Please select the no submission reasons by typing any of the number";
    private static final String DEFAULT_METER_CHANGE_PROMPT_HINDI =
            "कृपया नंबर टाइप करके सबमिशन न होने के कारण चुनें";

    private static final List<String> DEFAULT_ISSUE_REASONS = List.of(
            "Meter Replaced",
            "Meter not working",
            "Meter damage",
            "Incorrect Reading Entered Previously",
            "No Water Supply",
            "Others"
    );

    private static final List<String> DEFAULT_ISSUE_REASONS_HINDI = List.of(
            "मीटर बदला गया",
            "मीटर काम नहीं कर रहा",
            "मीटर खराब है",
            "पहले गलत रीडिंग दर्ज हुई थी",
            "पानी की आपूर्ति नहीं",
            "अन्य"
    );
    private static final List<String> DEFAULT_ISSUE_REASON_SELECTION_KEYS = List.of(
            "meterReplaced",
            "meterNotWorking",
            "meterDamage",
            "incorrectReadingEnteredPreviously",
            "noWaterSupplied",
            "others"
    );
    private static final List<String> TELEMETRY_ISSUE_REASONS = List.of(
            "Meter Replaced",
            "Meter not working",
            "Meter Damaged",
            "No Water Supply",
            "Others"
    );
    private static final List<String> TELEMETRY_ISSUE_REASONS_HINDI = List.of(
            "मीटर बदला गया",
            "मीटर काम नहीं कर रहा",
            "मीटर खराब है",
            "पानी की आपूर्ति नहीं",
            "अन्य"
    );
    private static final List<String> TELEMETRY_ISSUE_REASON_SELECTION_KEYS = List.of(
            "meterReplaced",
            "meterNotWorking",
            "meterDamaged",
            "noWaterSupplied",
            "others"
    );
    private static final Set<String> ISSUE_REPORT_ANOMALY_SELECTION_KEYS = Set.of(
            "meterNotWorking",
            "meterDamage",
            "meterDamaged",
            "noReadingSubmission",
            "noWaterSupply",
            "noWaterSupplied",
            "others"
    );

    private final GlificOperatorContextService operatorContextService;
    private final GlificLocalizationService localizationService;
    private final TenantConfigRepository tenantConfigRepository;
    private final GlificMessageTemplatesService templatesService;
    private final TelemetryTenantRepository telemetryTenantRepository;
    private final UserChannelPreferenceRepository userChannelPreferenceRepository;
    private final TelemetryEventPublisher telemetryEventPublisher;
    private final ObjectMapper objectMapper;

    public GlificMeterWorkflowService(GlificOperatorContextService operatorContextService,
                                      GlificLocalizationService localizationService,
                                      TenantConfigRepository tenantConfigRepository,
                                      GlificMessageTemplatesService templatesService,
                                      TelemetryTenantRepository telemetryTenantRepository,
                                      UserChannelPreferenceRepository userChannelPreferenceRepository,
                                      TelemetryEventPublisher telemetryEventPublisher,
                                      ObjectMapper objectMapper) {
        this.operatorContextService = operatorContextService;
        this.localizationService = localizationService;
        this.tenantConfigRepository = tenantConfigRepository;
        this.templatesService = templatesService;
        this.telemetryTenantRepository = telemetryTenantRepository;
        this.userChannelPreferenceRepository = userChannelPreferenceRepository;
        this.telemetryEventPublisher = telemetryEventPublisher;
        this.objectMapper = objectMapper;
    }

    public IntroResponse meterChangeMessage(IntroRequest request) {
        return meterChangeMessage(MeterChangeRequest.builder().contactId(request.getContactId()).build());
    }

    public IntroResponse meterChangeMessage(MeterChangeRequest request) {
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

            String prompt = tenantConfigRepository.findMeterChangePrompt(tenantId, languageKey)
                    .orElseThrow(() -> new IllegalStateException(
                            "meter change prompt is not configured. Add meter_change_prompt or meter_change_prompt_" + languageKey));

            List<String> reasons = tenantConfigRepository.findMeterChangeReasons(tenantId, languageKey);
            if (reasons.isEmpty()) {
                throw new IllegalStateException(
                        "No meter change reasons configured. Add meter_change_reason_1, meter_change_reason_2... or language-specific keys.");
            }

            StringBuilder message = new StringBuilder(prompt.trim());
            for (int i = 0; i < reasons.size(); i++) {
                message.append("\n")
                        .append(i + 1)
                        .append(". ")
                        .append(reasons.get(i));
            }

            return IntroResponse.builder()
                    .success(true)
                    .message(message.toString())
                    .build();
        } catch (Exception e) {
            log.error("Error building meter change reasons for contactId {}: {}", request.getContactId(), e.getMessage(), e);
            return IntroResponse.builder()
                    .success(false)
                    .message("Meter change reasons could not be prepared.")
                    .build();
        }
    }

    public IntroResponse takeMeterReadingMessage(MeterChangeRequest request) {
        try {
            if (request.getContactId() == null || request.getContactId().isBlank()) {
                throw new IllegalStateException("contactId is required");
            }
            if (request.getReason() == null || request.getReason().isBlank()) {
                throw new IllegalStateException("meter change reason selection is required");
            }

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(request.getContactId());

            Integer tenantId = operatorWithSchema.operator().tenantId();
            if (tenantId == null) {
                throw new IllegalStateException("Operator tenant could not be resolved");
            }

            String languageKey = localizationService.normalizeLanguageKey(operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId));

            List<String> reasons = tenantConfigRepository.findMeterChangeReasons(tenantId, languageKey);
            if (reasons.isEmpty()) {
                throw new IllegalStateException(
                        "No meter change reasons configured. Add meter_change_reason_1, meter_change_reason_2... or language-specific keys.");
            }
            String selectedReason = resolveSelection(request.getReason(), reasons)
                    .orElseThrow(() -> new IllegalStateException("Invalid meter change reason selection"));

            Long schemeId = telemetryTenantRepository
                    .findFirstSchemeForUser(operatorWithSchema.schemaName(), operatorWithSchema.operator().id())
                    .orElseThrow(() -> new IllegalStateException("Operator is not mapped to any scheme"));

            String correlationId = telemetryTenantRepository.upsertPendingMeterChangeRecord(
                    operatorWithSchema.schemaName(),
                    schemeId,
                    operatorWithSchema.operator().id(),
                    ReadingTime.now(),
                    selectedReason
            );

            String prompt = tenantConfigRepository.findTakeMeterReadingPrompt(tenantId, languageKey)
                    .orElse("Please type your meter reading manually (numbers only).");

            return IntroResponse.builder()
                    .success(true)
                    .message(prompt)
                    .correlationId(correlationId)
                    .build();
        } catch (Exception e) {
            log.error("Error preparing take meter reading prompt for contactId {}: {}", request.getContactId(), e.getMessage(), e);
            return IntroResponse.builder()
                    .success(false)
                    .message("Take meter reading prompt could not be prepared.")
                    .build();
        }
    }

    public IntroResponse issueReportPromptMessage(IntroRequest request) {
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

            String defaultPrompt = "hindi".equals(languageKey) ? DEFAULT_ISSUE_PROMPT_HINDI : DEFAULT_ISSUE_PROMPT_ENGLISH;

            Optional<String> promptFromTemplate = templatesService.resolveScreenPrompt(tenantId, "ISSUE_REPORT", languageKey);
            String prompt;
            if (promptFromTemplate.isPresent()) {
                prompt = promptFromTemplate.get().trim();
            } else {
                prompt = tenantConfigRepository.findIssueReportPrompt(tenantId, languageKey)
                        .map(String::trim)
                        .filter(p -> !p.equalsIgnoreCase(LEGACY_ISSUE_PROMPT_ENGLISH)
                                && !p.equals(LEGACY_ISSUE_PROMPT_HINDI))
                        .orElse(defaultPrompt);
            }

            List<GlificMessageTemplatesService.TemplateOption> templateReasons =
                    templatesService.resolveScreenReasons(tenantId, "ISSUE_REPORT");
            List<String> reasons;
            if (!templateReasons.isEmpty()) {
                reasons = templateReasons.stream().map(r -> r.labelForLanguageKey(languageKey)).toList();
            } else {
                reasons = tenantConfigRepository.findIssueReportReasons(tenantId, languageKey);
                if (reasons.isEmpty()) {
                    reasons = "hindi".equals(languageKey) ? DEFAULT_ISSUE_REASONS_HINDI : DEFAULT_ISSUE_REASONS;
                }
            }

            StringBuilder message = new StringBuilder(prompt.trim());
            for (int i = 0; i < reasons.size(); i++) {
                message.append("\n")
                        .append(i + 1)
                        .append(". ")
                        .append(reasons.get(i));
            }

            return IntroResponse.builder()
                    .success(true)
                    .message(message.toString())
                    .build();
        } catch (Exception e) {
            log.error("Error preparing issue report prompt for contactId {}: {}", request.getContactId(), e.getMessage(), e);
            return IntroResponse.builder()
                    .success(false)
                    .message("Issue report prompt could not be prepared.")
                    .build();
        }
    }

    public String issueReportTelemetryReasons(IntroRequest request) {
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

        String configValue = tenantConfigRepository.findConfigValue(tenantId, "SUPPLY_OUTAGE_REASONS")
                .orElseThrow(() -> new IllegalStateException("SUPPLY_OUTAGE_REASONS config is not configured"));

        JsonNode root;
        try {
            root = objectMapper.readTree(configValue);
        } catch (Exception e) {
            throw new IllegalStateException("SUPPLY_OUTAGE_REASONS config is not valid JSON", e);
        }

        JsonNode reasonsNode = root.path("reasons");
        if (!reasonsNode.isArray() || reasonsNode.isEmpty()) {
            throw new IllegalStateException("SUPPLY_OUTAGE_REASONS config has no reasons");
        }

        List<JsonNode> reasons = new java.util.ArrayList<>();
        reasonsNode.forEach(reasons::add);
        reasons.sort((a, b) -> Integer.compare(
                a.path("sequenceOrder").asInt(Integer.MAX_VALUE),
                b.path("sequenceOrder").asInt(Integer.MAX_VALUE)
        ));

        String prompt = "hindi".equals(languageKey) ? TELEMETRY_ISSUE_PROMPT_HINDI : TELEMETRY_ISSUE_PROMPT_ENGLISH;
        StringBuilder message = new StringBuilder(localizationService.localizeMessage(prompt, languageKey));
        for (int i = 0; i < reasons.size(); i++) {
            String name = reasons.get(i).path("name").asText();
            if (name == null || name.isBlank()) {
                continue;
            }
            message.append("\n")
                    .append(i + 1)
                    .append(". ")
                    .append(localizationService.localizeMessage(name.trim(), languageKey));
        }

        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "success", true,
                    "message", message.toString()
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize issue report reasons message", e);
        }
    }

    public String meterChangeReasons(IntroRequest request) {
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

        String configValue = tenantConfigRepository.findConfigValue(tenantId, "METER_CHANGE_REASONS")
                .orElseThrow(() -> new IllegalStateException("METER_CHANGE_REASONS config is not configured"));

        JsonNode root;
        try {
            root = objectMapper.readTree(configValue);
        } catch (Exception e) {
            throw new IllegalStateException("METER_CHANGE_REASONS config is not valid JSON", e);
        }

        JsonNode reasonsNode = root.path("reasons");
        if (!reasonsNode.isArray() || reasonsNode.isEmpty()) {
            throw new IllegalStateException("METER_CHANGE_REASONS config has no reasons");
        }

        List<JsonNode> reasons = new java.util.ArrayList<>();
        reasonsNode.forEach(reasons::add);
        reasons.sort((a, b) -> Integer.compare(
                a.path("sequenceOrder").asInt(Integer.MAX_VALUE),
                b.path("sequenceOrder").asInt(Integer.MAX_VALUE)
        ));

        String prompt = "hindi".equals(languageKey) ? DEFAULT_METER_CHANGE_PROMPT_HINDI : DEFAULT_METER_CHANGE_PROMPT_ENGLISH;
        StringBuilder message = new StringBuilder(localizationService.localizeMessage(prompt, languageKey));
        for (int i = 0; i < reasons.size(); i++) {
            String name = reasons.get(i).path("name").asText();
            if (name == null || name.isBlank()) {
                continue;
            }
            message.append("\n")
                    .append(i + 1)
                    .append(". ")
                    .append(localizationService.localizeMessage(name.trim(), languageKey));
        }

        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "success", true,
                    "message", message.toString()
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize meter change reasons message", e);
        }
    }

    public IntroResponse meterChangeSubmitMessage(MeterChangeRequest request) {
        try {
            if (request.getContactId() == null || request.getContactId().isBlank()) {
                throw new IllegalStateException("contactId is required");
            }
            if (request.getReason() == null || request.getReason().isBlank()) {
                throw new IllegalStateException("meter change reason selection is required");
            }

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(request.getContactId());
            Integer tenantId = operatorWithSchema.operator().tenantId();
            if (tenantId == null) {
                throw new IllegalStateException("Operator tenant could not be resolved");
            }

            String languageKey = localizationService.normalizeLanguageKey(
                    operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId)
            );

            String configValue = tenantConfigRepository.findConfigValue(tenantId, "METER_CHANGE_REASONS")
                    .orElseThrow(() -> new IllegalStateException("METER_CHANGE_REASONS config is not configured"));

            JsonNode root;
            try {
                root = objectMapper.readTree(configValue);
            } catch (Exception e) {
                throw new IllegalStateException("METER_CHANGE_REASONS config is not valid JSON", e);
            }

            JsonNode reasonsNode = root.path("reasons");
            if (!reasonsNode.isArray() || reasonsNode.isEmpty()) {
                throw new IllegalStateException("METER_CHANGE_REASONS config has no reasons");
            }

            List<JsonNode> reasons = new java.util.ArrayList<>();
            reasonsNode.forEach(reasons::add);
            reasons.sort((a, b) -> Integer.compare(
                    a.path("sequenceOrder").asInt(Integer.MAX_VALUE),
                    b.path("sequenceOrder").asInt(Integer.MAX_VALUE)
            ));

            String rawSelection = request.getReason().trim();
            Integer selectedIndex = rawSelection.matches("^\\d+$") ? Integer.parseInt(rawSelection) : null;
            if (selectedIndex == null || selectedIndex < 1 || selectedIndex > reasons.size()) {
                return IntroResponse.builder()
                        .success(false)
                        .message("invalid choice, please restart the flow")
                        .build();
            }

            JsonNode selectedReasonNode = reasons.get(selectedIndex - 1);
            String selectedReason = selectedReasonNode.path("name").asText().trim();
            String selectedKey = selectedReasonNode.path("id").asText().trim();

            Long schemeId = telemetryTenantRepository
                    .findFirstSchemeForUser(operatorWithSchema.schemaName(), operatorWithSchema.operator().id())
                    .orElseThrow(() -> new IllegalStateException("Operator is not mapped to any scheme"));

            String correlationId = telemetryTenantRepository.upsertPendingMeterChangeRecord(
                    operatorWithSchema.schemaName(),
                    schemeId,
                    operatorWithSchema.operator().id(),
                    ReadingTime.now(),
                    selectedReason
            );

            telemetryEventPublisher.publishMeterChangeReason(
                    tenantId,
                    schemeId,
                    operatorWithSchema.operator().id(),
                    ReadingTime.today(),
                    selectedReason
            );

            String fallbackMessage = "Successfully selected.";
            String confirmationMessage = templatesService
                    .resolveScreenConfirmationTemplate(tenantId, "METER_CHANGE", languageKey)
                    .or(() -> tenantConfigRepository.findMeterChangeConfirmationTemplate(tenantId, languageKey))
                    .orElse(localizationService.localizeMessage(fallbackMessage, languageKey));

            return IntroResponse.builder()
                    .success(true)
                    .message(confirmationMessage)
                    .correlationId(correlationId)
                    .selected(selectedKey)
                    .notOthers("OTHERS".equalsIgnoreCase(selectedKey))
                    .build();
        } catch (Exception e) {
            log.error("Error saving meter change reason for contactId {}: {}", request.getContactId(), e.getMessage(), e);
            return IntroResponse.builder()
                    .success(false)
                    .message("Meter change reason could not be saved.")
                    .build();
        }
    }

    public IntroResponse issueReportSubmitMessage(IssueReportRequest request) {
        try {
            if (request.getContactId() == null || request.getContactId().isBlank()) {
                throw new IllegalStateException("contactId is required");
            }
            if (request.getIssueReason() == null || request.getIssueReason().isBlank()) {
                throw new IllegalStateException("issueReason is required");
            }

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(request.getContactId());

            Integer tenantId = operatorWithSchema.operator().tenantId();
            if (tenantId == null) {
                throw new IllegalStateException("Operator tenant could not be resolved");
            }
            String languageKey = localizationService.normalizeLanguageKey(
                    operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId)
            );
            String rawIssueReason = request.getIssueReason().trim();

            List<GlificMessageTemplatesService.TemplateOption> templateReasons =
                    templatesService.resolveScreenReasons(tenantId, "ISSUE_REPORT");
            List<String> reasons;
            List<String> selectionKeys;
            if (!templateReasons.isEmpty()) {
                reasons = templateReasons.stream().map(r -> r.labelForLanguageKey(languageKey)).toList();
                selectionKeys = templateReasons.stream().map(GlificMessageTemplatesService.TemplateOption::key).toList();
            } else {
                reasons = tenantConfigRepository.findIssueReportReasons(tenantId, languageKey);
                if (reasons.isEmpty()) {
                    reasons = "hindi".equals(languageKey) ? DEFAULT_ISSUE_REASONS_HINDI : DEFAULT_ISSUE_REASONS;
                }
                selectionKeys = DEFAULT_ISSUE_REASON_SELECTION_KEYS;
            }
            if (rawIssueReason.matches("^\\d+$") && parseSelectionIndex(rawIssueReason, reasons.size()) == null) {
                return IntroResponse.builder()
                        .success(false)
                        .message("Please choose a number between 1 and " + reasons.size() + ".")
                        .build();
            }
            String resolvedIssueReason = rawIssueReason;
            String selectedKey = null;
            boolean matchedConfiguredReason = false;
            if (!templateReasons.isEmpty()) {
                Integer index = parseSelectionIndex(rawIssueReason, templateReasons.size());
                GlificMessageTemplatesService.TemplateOption matched = null;
                if (index != null) {
                    matched = templateReasons.get(index);
                } else {
                    for (GlificMessageTemplatesService.TemplateOption option : templateReasons) {
                        if (option.matchesAnyLabel(rawIssueReason)) {
                            matched = option;
                            break;
                        }
                    }
                }
                if (matched != null) {
                    String label = matched.labelForLanguageKey(languageKey);
                    resolvedIssueReason = (label == null || label.isBlank()) ? matched.canonicalLabel() : label;
                    selectedKey = matched.key();
                    matchedConfiguredReason = true;
                }
            }
            if (selectedKey == null) {
                Optional<String> matchedReason = resolveSelection(rawIssueReason, reasons);
                matchedConfiguredReason = matchedReason.isPresent();
                resolvedIssueReason = matchedReason.orElse(rawIssueReason);
                selectedKey = resolveIssueSelectionKey(rawIssueReason, resolvedIssueReason, reasons, selectionKeys);
            }
            requireStorableIssueReason(resolvedIssueReason, matchedConfiguredReason);
            String responseSelectedKey = normalizeIssueReportSelectedKey(selectedKey);
            String anomalySelectedKey = isReasonKey(selectedKey) ? responseSelectedKey : selectedKey;

            Long schemeId = telemetryTenantRepository
                    .findFirstSchemeForUser(operatorWithSchema.schemaName(), operatorWithSchema.operator().id())
                    .orElseThrow(() -> new IllegalStateException("Operator is not mapped to any scheme"));
            List<Long> analyticsUserIds = resolveAnalyticsUserIds(
                    operatorWithSchema.schemaName(),
                    schemeId,
                    operatorWithSchema.operator().id()
            );

            String correlationId = "issue-report-" + UUID.randomUUID();
            if (shouldStoreIssueAsAnomaly(anomalySelectedKey, rawIssueReason, ISSUE_REPORT_ANOMALY_SELECTION_KEYS)) {
                int anomalyType = ("noWaterSupply".equals(anomalySelectedKey) || "noWaterSupplied".equals(anomalySelectedKey))
                        ? AnomalyConstants.TYPE_NO_WATER_SUPPLY
                        : AnomalyConstants.TYPE_NO_SUBMISSION;
                telemetryTenantRepository.createTenantAnomalyRecord(
                        operatorWithSchema.schemaName(),
                        TenantAnomalyRecord.builder()
                                .userId(operatorWithSchema.operator().id())
                                .schemeId(schemeId)
                                .type(anomalyType)
                                .reason(resolvedIssueReason)
                                .status(AnomalyConstants.STATUS_OPEN)
                                .retries(0)
                                .build()
                );
                telemetryEventPublisher.publishAnomalyRecorded(
                        tenantId,
                        anomalyType,
                        operatorWithSchema.operator().id(),
                        schemeId,
                        null,
                        null,
                        null,
                        0,
                        null,
                        null,
                        0,
                        resolvedIssueReason,
                        AnomalyConstants.STATUS_OPEN,
                        correlationId
                );
                telemetryEventPublisher.publishOutageOrNonSubmissionReason(
                        tenantId,
                        schemeId,
                        operatorWithSchema.operator().id(),
                        ReadingTime.today(),
                        anomalyType,
                        resolvedIssueReason
                );
            } else {
                telemetryTenantRepository.createIssueReportRecord(
                        operatorWithSchema.schemaName(),
                        schemeId,
                        operatorWithSchema.operator().id(),
                        ReadingTime.now(),
                        correlationId,
                        resolvedIssueReason
                );
            }

            String message;
            if ("meterReplace".equalsIgnoreCase(responseSelectedKey)
                    || "meterReplaced".equalsIgnoreCase(responseSelectedKey)
                    || "noWaterSupply".equalsIgnoreCase(responseSelectedKey)
                    || "noReadingSubmission".equalsIgnoreCase(responseSelectedKey)) {
                message = "please wait a second...";
            } else {
                String fallbackMessage = "Issue reported. Thank you.";
                if ("hindi".equals(languageKey)) {
                    fallbackMessage = "समस्या रिपोर्ट हो गई है। धन्यवाद।";
                }
                message = templatesService
                        .resolveScreenConfirmationTemplate(tenantId, "ISSUE_REPORT", languageKey)
                        .or(() -> tenantConfigRepository.findIssueReportConfirmationTemplate(tenantId, languageKey))
                        .orElse(fallbackMessage);
            }

            return IntroResponse.builder()
                    .success(true)
                    .message(message)
                    .correlationId(correlationId)
                    .selected(responseSelectedKey)
                    .build();
        } catch (Exception e) {
            log.error("Error saving issue report for contactId {}: {}", request.getContactId(), e.getMessage(), e);
            return IntroResponse.builder()
                    .success(false)
                    .message(localizationService.resolveUserFacingErrorMessage(
                            e,
                            "Issue report could not be saved.",
                            localizationService.resolveLanguageKeyForContact(request.getContactId())
                    ))
                    .build();
        }
    }

    public IntroResponse othersPromptMessage(IntroRequest request) {
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
            String message = "hindi".equals(languageKey) ? DEFAULT_OTHERS_PROMPT_HINDI : DEFAULT_OTHERS_PROMPT_ENGLISH;

            return IntroResponse.builder()
                    .success(true)
                    .message(message)
                    .build();
        } catch (Exception e) {
            log.error("Error preparing others prompt for contactId {}: {}", request.getContactId(), e.getMessage(), e);
            return IntroResponse.builder()
                    .success(false)
                    .message("Others prompt could not be prepared.")
                    .build();
        }
    }

    public IntroResponse issueReportTelemetryPromptMessage(IntroRequest request) {
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

            String prompt = "hindi".equals(languageKey) ? DEFAULT_ISSUE_PROMPT_HINDI : DEFAULT_ISSUE_PROMPT_ENGLISH;
            List<String> reasons = "hindi".equals(languageKey) ? TELEMETRY_ISSUE_REASONS_HINDI : TELEMETRY_ISSUE_REASONS;

            StringBuilder message = new StringBuilder(prompt.trim());
            for (int i = 0; i < reasons.size(); i++) {
                message.append("\n")
                        .append(i + 1)
                        .append(". ")
                        .append(reasons.get(i));
            }

            return IntroResponse.builder()
                    .success(true)
                    .message(message.toString())
                    .build();
        } catch (Exception e) {
            log.error("Error preparing telemetry issue report prompt for contactId {}: {}", request.getContactId(), e.getMessage(), e);
            return IntroResponse.builder()
                    .success(false)
                    .message("Issue report prompt could not be prepared.")
                    .build();
        }
    }

    public IntroResponse issueReportTelemetrySubmitMessage(IssueReportRequest request) {
        try {
            if (request.getContactId() == null || request.getContactId().isBlank()) {
                throw new IllegalStateException("contactId is required");
            }
            if (request.getIssueReason() == null || request.getIssueReason().isBlank()) {
                throw new IllegalStateException("issueReason is required");
            }

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(request.getContactId());
            Integer tenantId = operatorWithSchema.operator().tenantId();
            if (tenantId == null) {
                throw new IllegalStateException("Operator tenant could not be resolved");
            }
            String languageKey = localizationService.normalizeLanguageKey(
                    operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId)
            );

            String rawIssueReason = request.getIssueReason().trim();
            Optional<String> configValue = tenantConfigRepository.findConfigValue(tenantId, "SUPPLY_OUTAGE_REASONS");
            String resolvedIssueReason;
            String selectedKey;

            if (configValue.isPresent()) {
                JsonNode root = objectMapper.readTree(configValue.get());
                JsonNode reasonsNode = root.path("reasons");
                if (!reasonsNode.isArray() || reasonsNode.isEmpty()) {
                    throw new IllegalStateException("SUPPLY_OUTAGE_REASONS config has no reasons");
                }
                List<JsonNode> reasons = new java.util.ArrayList<>();
                reasonsNode.forEach(reasons::add);
                reasons.sort((a, b) -> Integer.compare(
                        a.path("sequenceOrder").asInt(Integer.MAX_VALUE),
                        b.path("sequenceOrder").asInt(Integer.MAX_VALUE)
                ));

                Integer selectedIndex = null;
                if (rawIssueReason.matches("^\\d+$")) {
                    selectedIndex = Integer.parseInt(rawIssueReason);
                }

                if (selectedIndex == null || selectedIndex < 1 || selectedIndex > reasons.size()) {
                    return IntroResponse.builder()
                            .success(false)
                            .message("invalid choice, please restart the flow")
                            .build();
                }

                JsonNode selectedReasonNode = reasons.get(selectedIndex - 1);
                resolvedIssueReason = selectedReasonNode.path("name").asText().trim();
                selectedKey = selectedReasonNode.path("id").asText().trim();
                // A configured label, so the character allowlist does not apply — but the length
                // does: this name comes from tenant JSON and reaches analytics' VARCHAR(255).
                requireStorableIssueReason(resolvedIssueReason, true);
            } else {
                List<String> reasons = "hindi".equals(languageKey) ? TELEMETRY_ISSUE_REASONS_HINDI : TELEMETRY_ISSUE_REASONS;
                Optional<String> matchedReason = resolveSelection(rawIssueReason, reasons);
                resolvedIssueReason = matchedReason.orElse(rawIssueReason);
                requireStorableIssueReason(resolvedIssueReason, matchedReason.isPresent());
                selectedKey = resolveIssueSelectionKey(
                        rawIssueReason,
                        resolvedIssueReason,
                        reasons,
                        TELEMETRY_ISSUE_REASON_SELECTION_KEYS
                );
            }

            Long schemeId = telemetryTenantRepository
                    .findFirstSchemeForUser(operatorWithSchema.schemaName(), operatorWithSchema.operator().id())
                    .orElseThrow(() -> new IllegalStateException("Operator is not mapped to any scheme"));
            List<Long> analyticsUserIds = resolveAnalyticsUserIds(
                    operatorWithSchema.schemaName(),
                    schemeId,
                    operatorWithSchema.operator().id()
            );

            String correlationId = telemetryTenantRepository.upsertPendingIssueReportRecord(
                    operatorWithSchema.schemaName(),
                    schemeId,
                    operatorWithSchema.operator().id(),
                    ReadingTime.now(),
                    resolvedIssueReason
            );
            int anomalyType = AnomalyConstants.TYPE_NO_WATER_SUPPLY;
            for (Long recipientUserId : analyticsUserIds) {
                telemetryEventPublisher.publishEscalationCreated(
                        tenantId,
                        schemeId,
                        recipientUserId,
                        anomalyType,
                        resolvedIssueReason,
                        correlationId,
                        AnomalyConstants.STATUS_OPEN,
                        null
                );
            }
            telemetryEventPublisher.publishOutageOrNonSubmissionReason(
                    tenantId,
                    schemeId,
                    operatorWithSchema.operator().id(),
                    ReadingTime.today(),
                    anomalyType,
                    resolvedIssueReason
            );

            String fallbackMessage = "Issue reported. Thank you.";
            if ("hindi".equals(languageKey)) {
                fallbackMessage = "समस्या रिपोर्ट हो गई है। धन्यवाद।";
            }

            String message = tenantConfigRepository.findIssueReportConfirmationTemplate(tenantId, languageKey)
                    .orElse(fallbackMessage);

            return IntroResponse.builder()
                    .success(true)
                    .message(message)
                    .correlationId(correlationId)
                    .selected(selectedKey)
                    .notOthers("OTHERS".equalsIgnoreCase(selectedKey) || "others".equalsIgnoreCase(selectedKey))
                    .build();
        } catch (Exception e) {
            log.error("Error saving telemetry issue report for contactId {}: {}", request.getContactId(), e.getMessage(), e);
            return IntroResponse.builder()
                    .success(false)
                    .message(localizationService.resolveUserFacingErrorMessage(
                            e,
                            "Issue report could not be saved.",
                            localizationService.resolveLanguageKeyForContact(request.getContactId())
                    ))
                    .build();
        }
    }

    public IntroResponse othersSubmittedMessage(IssueReportRequest request) {
        try {
            if (request.getContactId() == null || request.getContactId().isBlank()) {
                throw new IllegalStateException("contactId is required");
            }
            if (request.getIssueReason() == null || request.getIssueReason().isBlank()) {
                throw new IllegalStateException("issueReason is required");
            }

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(request.getContactId());
            Integer tenantId = operatorWithSchema.operator().tenantId();
            if (tenantId == null) {
                throw new IllegalStateException("Operator tenant could not be resolved");
            }

            Long schemeId = telemetryTenantRepository
                    .findFirstSchemeForUser(operatorWithSchema.schemaName(), operatorWithSchema.operator().id())
                    .orElseThrow(() -> new IllegalStateException("Operator is not mapped to any scheme"));
            List<Long> analyticsUserIds = resolveAnalyticsUserIds(
                    operatorWithSchema.schemaName(),
                    schemeId,
                    operatorWithSchema.operator().id()
            );

            String correlationId = "issue-report-" + UUID.randomUUID();
            String issueReason = request.getIssueReason().trim();
            // Always free text on this endpoint — there is no menu behind /others/submitted.
            requireStorableIssueReason(issueReason, false);

            telemetryTenantRepository.createTenantAnomalyRecord(
                    operatorWithSchema.schemaName(),
                    TenantAnomalyRecord.builder()
                            .userId(operatorWithSchema.operator().id())
                            .schemeId(schemeId)
                            .type(AnomalyConstants.TYPE_NO_SUBMISSION)
                            .reason(issueReason)
                            .status(AnomalyConstants.STATUS_OPEN)
                            .retries(0)
                            .build()
            );
            telemetryEventPublisher.publishAnomalyRecorded(
                    tenantId,
                    AnomalyConstants.TYPE_NO_SUBMISSION,
                    operatorWithSchema.operator().id(),
                    schemeId,
                    null,
                    null,
                    null,
                    0,
                    null,
                    null,
                    0,
                    issueReason,
                    AnomalyConstants.STATUS_OPEN,
                    null
            );
            telemetryEventPublisher.publishOutageOrNonSubmissionReason(
                    tenantId,
                    schemeId,
                    operatorWithSchema.operator().id(),
                    ReadingTime.today(),
                    AnomalyConstants.TYPE_NO_SUBMISSION,
                    issueReason
            );

            String languageKey = localizationService.normalizeLanguageKey(
                    operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId)
            );
            String fallbackMessage = "Issue reported. Thank you.";
            if ("hindi".equals(languageKey)) {
                fallbackMessage = "समस्या रिपोर्ट हो गई है। धन्यवाद।";
            }
            String message = templatesService
                    .resolveScreenConfirmationTemplate(tenantId, "ISSUE_REPORT", languageKey)
                    .or(() -> tenantConfigRepository.findIssueReportConfirmationTemplate(tenantId, languageKey))
                    .orElse(fallbackMessage);

            return IntroResponse.builder()
                    .success(true)
                    .message(message)
                    .correlationId(correlationId)
                    .selected("others")
                    .build();
        } catch (Exception e) {
            log.error("Error saving others issue report for contactId {}: {}", request.getContactId(), e.getMessage(), e);
            return IntroResponse.builder()
                    .success(false)
                    .message(localizationService.resolveUserFacingErrorMessage(
                            e,
                            "Issue report could not be saved.",
                            localizationService.resolveLanguageKeyForContact(request.getContactId())
                    ))
                    .build();
        }
    }

    public CreateReadingResponse manualReadingMessage(ManualReadingRequest request) {
        try {
            if (request.getContactId() == null || request.getContactId().isBlank()) {
                throw new IllegalStateException("contactId is required");
            }
            if (request.getManualReading() == null || request.getManualReading().isBlank()) {
                throw new IllegalStateException("manualReading is required");
            }
            boolean isMeterReplaced = Boolean.TRUE.equals(request.getIsMeterReplaced());

            String normalizedReading = request.getManualReading().trim().replace(",", "");
            if (!normalizedReading.matches("^\\d+(\\.\\d+)?$")) {
                throw new IllegalStateException("manualReading must be numeric");
            }
            BigDecimal manualReadingValue = new BigDecimal(normalizedReading);
            if (manualReadingValue.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("manualReading must be greater than zero");
            }

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(request.getContactId());

            Integer tenantId = operatorWithSchema.operator().tenantId();
            if (tenantId == null) {
                throw new IllegalStateException("Operator tenant could not be resolved");
            }
            String languageKey = localizationService.normalizeLanguageKey(operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId));

            Long schemeId = telemetryTenantRepository
                    .findLatestPendingSchemeSelectionForDate(
                            operatorWithSchema.schemaName(),
                            operatorWithSchema.operator().id(),
                            ReadingTime.today()
                    )
                    .map(TelemetrySchemeSelectionRecord::schemeId)
                    .or(() -> telemetryTenantRepository.findFirstSchemeForUser(
                            operatorWithSchema.schemaName(),
                            operatorWithSchema.operator().id()
                    ))
                    .orElseThrow(() -> new IllegalStateException("Operator is not mapped to any scheme"));
            List<Long> analyticsUserIds = resolveAnalyticsUserIds(
                    operatorWithSchema.schemaName(),
                    schemeId,
                    operatorWithSchema.operator().id()
            );

            Optional<TelemetryPendingMeterChangeRecord> pendingOpt = telemetryTenantRepository.findLatestPendingMeterChangeRecord(
                    operatorWithSchema.schemaName(),
                    schemeId,
                    operatorWithSchema.operator().id()
            );
            String correlationId = (request.getCorrelationId() != null && !request.getCorrelationId().isBlank())
                    ? request.getCorrelationId().trim()
                    : "manual-" + UUID.randomUUID();

            // Validation baseline:
            // - If the meter is not replaced, compare against the most recent confirmed reading by default.
            // - If isManualReading=false, compare against the most recent confirmed reading strictly before today.
            // - If the meter is replaced, load latest snapshot for anomaly/audit context only.
            LocalDate today = ReadingTime.today();
            boolean compareWithLatest = request.getIsManualReading() == null || Boolean.TRUE.equals(request.getIsManualReading());
            Optional<TelemetryConfirmedReadingSnapshot> previousSnapshotOpt = isMeterReplaced
                    ? telemetryTenantRepository.findLatestConfirmedReadingSnapshot(operatorWithSchema.schemaName(), schemeId, null)
                    : (compareWithLatest
                    ? telemetryTenantRepository.findLatestConfirmedReadingSnapshot(
                            operatorWithSchema.schemaName(),
                            schemeId,
                            null
                    )
                    : telemetryTenantRepository.findLatestConfirmedReadingSnapshotBeforeDate(
                            operatorWithSchema.schemaName(),
                            schemeId,
                            today,
                            null
                    ));

            BigDecimal effectiveConfirmedReading = manualReadingValue;
//            if (!isMeterReplaced
//                    && previousSnapshotOpt.isPresent()
//                    && manualReadingValue.compareTo(previousSnapshotOpt.get().confirmedReading()) < 0) {
//                TelemetryConfirmedReadingSnapshot previousSnapshot = previousSnapshotOpt.get();
//                String reason = "Submitted reading is less than previous confirmed reading.";
//                telemetryTenantRepository.createTenantAnomalyRecord(
//                        operatorWithSchema.schemaName(),
//                        operatorWithSchema.operator().id(),
//                        schemeId,
//                        AnomalyConstants.TYPE_READING_LESS_THAN_PREVIOUS,
//                        reason,
//                        AnomalyConstants.STATUS_OPEN
//                );
//                telemetryEventPublisher.publishAnomalyRecorded(
//                        tenantId,
//                        AnomalyConstants.TYPE_READING_LESS_THAN_PREVIOUS,
//                        operatorWithSchema.operator().id(),
//                        schemeId,
//                        pendingOpt.map(TelemetryPendingMeterChangeRecord::extractedReading).orElse(null),
//                        null,
//                        manualReadingValue,
//                        0,
//                        previousSnapshot.confirmedReading(),
//                        previousSnapshot.createdAt(),
//                        0,
//                        reason,
//                        AnomalyConstants.STATUS_OPEN,
//                        correlationId
//                );
//                return CreateReadingResponse.builder()
//                        .success(false)
//                        .message(localizationService.localizeMessage(
//                                "Reading rejected because it is below the last confirmed reading. Submitted: " + toPlain(manualReadingValue)
//                                        + ". Last confirmed: " + toPlain(previousSnapshot.confirmedReading()) + ".",
//                                languageKey
//                        ))
//                        .qualityStatus("REJECTED")
//                        .correlationId(correlationId)
//                        .meterReading(manualReadingValue)
//                        .lastConfirmedReading(previousSnapshot.confirmedReading())
//                        .build();
//            }

            // Tenant-configured water supply threshold validation (relative to WATER_NORM).
            // Validate against the effective confirmed value after baseline clamping.
            if (!isMeterReplaced) {
                Optional<WaterSupplyThreshold> thresholdOpt = loadWaterSupplyThreshold(tenantId);
                Optional<BigDecimal> waterNormOpt = loadWaterNorm(tenantId);
                if (thresholdOpt.isPresent() && waterNormOpt.isPresent()) {
                    WaterSupplyThreshold threshold = thresholdOpt.get();
                    BigDecimal waterNorm = waterNormOpt.get();

                    BigDecimal minAllowed = waterNorm
                            .multiply(BigDecimal.valueOf(100.0d - threshold.undersupplyThresholdPercent()))
                            .divide(BigDecimal.valueOf(100.0d), 6, RoundingMode.HALF_UP);
                    BigDecimal maxAllowed = waterNorm
                            .multiply(BigDecimal.valueOf(100.0d + threshold.oversupplyThresholdPercent()))
                            .divide(BigDecimal.valueOf(100.0d), 6, RoundingMode.HALF_UP);

                    BigDecimal previousConfirmed = previousSnapshotOpt.map(TelemetryConfirmedReadingSnapshot::confirmedReading).orElse(null);
                    LocalDateTime previousConfirmedAt = previousSnapshotOpt.map(TelemetryConfirmedReadingSnapshot::createdAt).orElse(null);
                    BigDecimal baselineReading = previousConfirmed != null ? previousConfirmed : BigDecimal.ZERO;
                    BigDecimal minAllowedReading = baselineReading.add(minAllowed);
                    BigDecimal maxAllowedReading = baselineReading.add(maxAllowed);

//                    if (effectiveConfirmedReading.compareTo(minAllowedReading) < 0) {
//                        telemetryTenantRepository.createTenantAnomalyRecord(
//                                operatorWithSchema.schemaName(),
//                                operatorWithSchema.operator().id(),
//                                schemeId,
//                                AnomalyConstants.TYPE_LOW_WATER_SUPPLY,
//                                "Manual reading is below allowed minimum reading (" + toPlain(minAllowedReading) + ").",
//                                AnomalyConstants.STATUS_OPEN
//                        );
//                        telemetryEventPublisher.publishAnomalyRecorded(
//                                tenantId,
//                                AnomalyConstants.TYPE_LOW_WATER_SUPPLY,
//                                operatorWithSchema.operator().id(),
//                                schemeId,
//                                pendingOpt.map(TelemetryPendingMeterChangeRecord::extractedReading).orElse(null),
//                                null,
//                                effectiveConfirmedReading,
//                                0,
//                                previousConfirmed,
//                                previousConfirmedAt,
//                                0,
//                                "Manual reading is below allowed minimum reading (" + toPlain(minAllowedReading) + ").",
//                                AnomalyConstants.STATUS_OPEN,
//                                null
//                        );
//                        telemetryEventPublisher.publishOutageOrNonSubmissionReason(
//                                tenantId,
//                                schemeId,
//                                operatorWithSchema.operator().id(),
//                                today,
//                                AnomalyConstants.TYPE_LOW_WATER_SUPPLY,
//                                "Manual reading is below allowed minimum reading (" + toPlain(minAllowedReading) + ")."
//                        );
//                        return CreateReadingResponse.builder()
//                                .success(false)
//                                .message(localizationService.localizeMessage(
//                                        "Reading rejected because it is below the allowed minimum. Submitted: " + toPlain(effectiveConfirmedReading)
//                                                + ". Minimum allowed reading: " + toPlain(minAllowedReading) + ".",
//                                        languageKey
//                                ))
//                                .qualityStatus("REJECTED")
//                                .correlationId(correlationId)
//                                .meterReading(effectiveConfirmedReading)
//                                .lastConfirmedReading(previousConfirmed)
//                                .build();
//                    }
                    if (effectiveConfirmedReading.compareTo(maxAllowedReading) > 0) {
                        telemetryTenantRepository.createTenantAnomalyRecord(
                                operatorWithSchema.schemaName(),
                                TenantAnomalyRecord.builder()
                                        .userId(operatorWithSchema.operator().id())
                                        .schemeId(schemeId)
                                        .type(AnomalyConstants.TYPE_OVER_WATER_SUPPLY)
                                        .reason("Manual reading is above allowed maximum reading ("
                                                + toPlain(maxAllowedReading) + ").")
                                        .status(AnomalyConstants.STATUS_OPEN)
                                        .aiReading(pendingOpt.map(TelemetryPendingMeterChangeRecord::extractedReading)
                                                .orElse(null))
                                        .overriddenReading(effectiveConfirmedReading)
                                        .retries(0)
                                        .previousReading(previousConfirmed)
                                        .previousReadingDate(previousConfirmedAt)
                                        .build()
                        );
                        telemetryEventPublisher.publishAnomalyRecorded(
                                tenantId,
                                AnomalyConstants.TYPE_OVER_WATER_SUPPLY,
                                operatorWithSchema.operator().id(),
                                schemeId,
                                pendingOpt.map(TelemetryPendingMeterChangeRecord::extractedReading).orElse(null),
                                null,
                                effectiveConfirmedReading,
                                0,
                                previousConfirmed,
                                previousConfirmedAt,
                                0,
                                "Manual reading is above allowed maximum reading (" + toPlain(maxAllowedReading) + ").",
                                AnomalyConstants.STATUS_OPEN,
                                null
                        );
                        // THRESHOLD-DISCLOSURE: the reply says the value was too high but not what the
                        // limit is. Echoing "Maximum allowed reading: N" let any caller read the tenant's
                        // configured WATER_NORM and oversupply threshold straight off the API — two
                        // submissions are enough to solve for both. The numbers stay in the anomaly
                        // record, the Kafka event and the server log, which are all staff-side.
                        log.warn("manual_reading_rejected tenantId={} schemeId={} operatorId={} submitted={} maxAllowed={}",
                                tenantId, schemeId, operatorWithSchema.operator().id(),
                                toPlain(effectiveConfirmedReading), toPlain(maxAllowedReading));
                        return CreateReadingResponse.builder()
                                .success(false)
                                .message(localizationService.localizeMessage(
                                        "Reading rejected because it is above the allowed maximum for this scheme. Submitted: "
                                                + toPlain(effectiveConfirmedReading) + ".",
                                        languageKey
                                ))
                                .qualityStatus("REJECTED")
                                .correlationId(correlationId)
                                .meterReading(effectiveConfirmedReading)
                                .lastConfirmedReading(previousConfirmed)
                                .build();
                    }
                }
            }

            if (pendingOpt.isPresent()) {
                // Manual reading submissions should only update confirmed_reading (never extracted_reading).
                // A pending meter-change row has confirmed_reading = 0 by query invariant, so it can never
                // carry rollover-resolved provenance — tag MANUAL unconditionally, folded into the same UPDATE.
                telemetryTenantRepository.updateConfirmedReading(
                        operatorWithSchema.schemaName(),
                        pendingOpt.get().id(),
                        effectiveConfirmedReading,
                        operatorWithSchema.operator().id(),
                        RolloverResolutionService.SOURCE_MANUAL
                );
                if (isMeterReplaced) {
                    telemetryTenantRepository.updateMeterChangeReason(
                            operatorWithSchema.schemaName(),
                            pendingOpt.get().id(),
                            "METER_REPLACED",
                            operatorWithSchema.operator().id()
                    );
                }
                correlationId = pendingOpt.get().correlationId();
            } else {
                Optional<TelemetryFlowReadingDetails> todaysFlowOpt = telemetryTenantRepository.findLatestFlowReadingForDate(
                        operatorWithSchema.schemaName(),
                        schemeId,
                        operatorWithSchema.operator().id(),
                        today
                );

                if (todaysFlowOpt.isPresent()) {
                    TelemetryFlowReadingDetails todaysFlow = todaysFlowOpt.get();
                    // Manual reading submissions should only update confirmed_reading (never extracted_reading),
                    // regardless of whether extracted_reading exists for today's row. Today's row may have been
                    // rollover-resolved earlier, so retag MANUAL only when the value actually changes — a
                    // same-value re-entry keeps SOURCE_ROLLOVER_RESOLVED. Folded into the same UPDATE.
                    telemetryTenantRepository.updateConfirmedReading(
                            operatorWithSchema.schemaName(),
                            todaysFlow.id(),
                            effectiveConfirmedReading,
                            operatorWithSchema.operator().id(),
                            RolloverResolutionService.manualConfirmSource(
                                    effectiveConfirmedReading, todaysFlow.confirmedReading())
                    );
                    if (isMeterReplaced) {
                        telemetryTenantRepository.updateMeterChangeReason(
                                operatorWithSchema.schemaName(),
                                todaysFlow.id(),
                                "METER_REPLACED",
                                operatorWithSchema.operator().id()
                        );
                    }

                    if (todaysFlow.correlationId() != null && !todaysFlow.correlationId().isBlank()) {
                        correlationId = todaysFlow.correlationId();
                    }
                } else {
                    // Nothing recorded for today yet, so the manual value opens the row: extracted_reading
                    // keeps the 0 sentinel (nothing extracted it) and the row is tagged MANUAL. Without the
                    // tag it would keep confirmed_reading_source at its DEFAULT 0 = AS_EXTRACTED and claim
                    // the AI picked a number it never saw.
                    //
                    // Both writes go through the @Transactional persist helper rather than an insert
                    // followed by a separate applyConfirmedReadingSource: a marker write that failed on its
                    // own would commit exactly the mislabelled row this is here to prevent. NORMAL
                    // ingestion with no submitted ids skips the tracking UPDATE, so this is the same two
                    // statements the API path already runs, under one transaction.
                    telemetryTenantRepository.persistFlowReadingWithTracking(
                            operatorWithSchema.schemaName(),
                            null,
                            schemeId,
                            operatorWithSchema.operator().id(),
                            ReadingTime.now(),
                            BigDecimal.ZERO,
                            effectiveConfirmedReading,
                            correlationId,
                            null,
                            "",
                            isMeterReplaced ? "METER_REPLACED" : request.getMeterChangeReason(),
                            IngestionSource.NORMAL,
                            null,
                            null,
                            null,
                            RolloverResolutionService.SOURCE_MANUAL
                    );
                }
            }

            int unreadableRetryCountToday = telemetryTenantRepository.countAnomaliesByTypeForToday(
                    operatorWithSchema.schemaName(),
                    operatorWithSchema.operator().id(),
                    schemeId,
                    AnomalyConstants.TYPE_UNREADABLE_IMAGE
            );

            telemetryTenantRepository.createTenantAnomalyRecord(
                    operatorWithSchema.schemaName(),
                    TenantAnomalyRecord.builder()
                            .userId(operatorWithSchema.operator().id())
                            .schemeId(schemeId)
                            .type(AnomalyConstants.TYPE_MANUAL_OVERRIDE)
                            .reason("Manual reading submitted as override.")
                            .status(AnomalyConstants.STATUS_OPEN)
                            .aiReading(pendingOpt.map(TelemetryPendingMeterChangeRecord::extractedReading)
                                    .orElse(null))
                            .overriddenReading(manualReadingValue)
                            .retries(unreadableRetryCountToday)
                            .previousReading(previousSnapshotOpt
                                    .map(TelemetryConfirmedReadingSnapshot::confirmedReading).orElse(null))
                            .previousReadingDate(previousSnapshotOpt
                                    .map(TelemetryConfirmedReadingSnapshot::createdAt).orElse(null))
                            .build()
            );
            telemetryEventPublisher.publishAnomalyRecorded(
                    tenantId,
                    AnomalyConstants.TYPE_MANUAL_OVERRIDE,
                    operatorWithSchema.operator().id(),
                    schemeId,
                    pendingOpt.map(TelemetryPendingMeterChangeRecord::extractedReading).orElse(null),
                    null,
                    manualReadingValue,
                    unreadableRetryCountToday,
                    previousSnapshotOpt.map(TelemetryConfirmedReadingSnapshot::confirmedReading).orElse(null),
                    previousSnapshotOpt.map(TelemetryConfirmedReadingSnapshot::createdAt).orElse(null),
                    0,
                    "Manual reading submitted as override.",
                    AnomalyConstants.STATUS_OPEN,
                    null
            );

            int consecutiveOverrideDays = calculateConsecutiveDays(
                    telemetryTenantRepository.findAnomalyDatesByType(
                            operatorWithSchema.schemaName(),
                            operatorWithSchema.operator().id(),
                            schemeId,
                            AnomalyConstants.TYPE_MANUAL_OVERRIDE,
                            10
                    ),
                    ReadingTime.today()
            );

            if (consecutiveOverrideDays >= 5) {
                telemetryTenantRepository.createTenantAnomalyRecord(
                        operatorWithSchema.schemaName(),
                        TenantAnomalyRecord.builder()
                                .userId(operatorWithSchema.operator().id())
                                .schemeId(schemeId)
                                .type(AnomalyConstants.TYPE_CONSECUTIVE_OVERRIDE_5_DAYS)
                                .reason("Manual overrides recorded for five or more consecutive days.")
                                .status(AnomalyConstants.STATUS_OPEN)
                                .retries(0)
                                // The only anomaly that counts an override run, and so the only one
                                // with a real value for the column named after it.
                                .consecutiveDaysOverridden(consecutiveOverrideDays)
                                .build()
                );
                for (Long recipientUserId : analyticsUserIds) {
                    telemetryEventPublisher.publishEscalationCreated(
                            tenantId,
                            schemeId,
                            recipientUserId,
                            AnomalyConstants.TYPE_CONSECUTIVE_OVERRIDE_5_DAYS,
                            "Manual overrides recorded for five or more consecutive days.",
                            correlationId,
                            AnomalyConstants.STATUS_OPEN,
                            null
                    );
                }
            }

            CreateReadingResponse response = CreateReadingResponse.builder()
                    .success(true)
                    .correlationId(correlationId)
                    .meterReading(manualReadingValue)
                    .qualityStatus("CONFIRMED")
                    .build();

            String template = tenantConfigRepository.findManualReadingConfirmationTemplate(tenantId, languageKey)
                    .orElse("Manual reading {reading} saved successfully.");
            response.setMessage(template.replace("{reading}", manualReadingValue.stripTrailingZeros().toPlainString()));

            if (response.getCorrelationId() == null || response.getCorrelationId().isBlank()) {
                response.setCorrelationId(UUID.randomUUID().toString());
            }
            return response;
        } catch (Exception e) {
            log.error("Error processing manual reading for contactId {}: {}", request.getContactId(), e.getMessage(), e);
            String languageKey = localizationService.resolveLanguageKeyForContact(request.getContactId());
            String descriptiveMessage = localizationService.resolveUserFacingErrorMessage(e, "Manual reading could not be saved.", languageKey);
            return CreateReadingResponse.builder()
                    .success(false)
                    .message(descriptiveMessage)
                    .qualityStatus("REJECTED")
                    .correlationId(request.getContactId())
                    .build();
        }
    }

    public CreateReadingResponse locationReadingMessage(LocationReadingRequest request) {
        try {
            String contactId = request != null ? request.resolveContactId() : null;
            if (contactId == null || contactId.isBlank()) {
                throw new IllegalStateException("contactId is required");
            }
            if (request.getLatitude() == null) {
                throw new IllegalStateException("latitude is required");
            }
            if (request.getLongitude() == null) {
                throw new IllegalStateException("longitude is required");
            }

            BigDecimal latitude = request.getLatitude();
            BigDecimal longitude = request.getLongitude();
            if (latitude.compareTo(BigDecimal.valueOf(-90)) < 0 || latitude.compareTo(BigDecimal.valueOf(90)) > 0) {
                throw new IllegalStateException("latitude must be between -90 and 90");
            }
            if (longitude.compareTo(BigDecimal.valueOf(-180)) < 0 || longitude.compareTo(BigDecimal.valueOf(180)) > 0) {
                throw new IllegalStateException("longitude must be between -180 and 180");
            }

            // Glific supplies organization_id; use it as a tenant hint when resolving operator across tenants.
            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(
                    contactId,
                    request.getOrganizationId()
            );
            Long operatorId = operatorWithSchema.operator().id();
            String languageKey = localizationService.normalizeLanguageKey(
                    operatorContextService.resolveOperatorLanguage(operatorWithSchema, operatorWithSchema.operator().tenantId())
            );

            Long schemeId = telemetryTenantRepository
                    .findFirstSchemeForUser(operatorWithSchema.schemaName(), operatorId)
                    .orElseThrow(() -> new IllegalStateException("Operator is not mapped to any scheme"));

            String correlationId = null;
            Long readingId = null;
            Optional<TelemetryFlowReadingDetails> today = telemetryTenantRepository.findLatestFlowReadingForDate(
                    operatorWithSchema.schemaName(),
                    schemeId,
                    operatorId,
                    ReadingTime.today()
            );
            if (today.isPresent()) {
                readingId = today.get().id();
                correlationId = today.get().correlationId();
            }

            if (readingId == null) {
                correlationId = "location-" + UUID.randomUUID();
                readingId = telemetryTenantRepository.createFlowReading(
                        operatorWithSchema.schemaName(),
                        schemeId,
                        operatorId,
                        ReadingTime.now(),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        correlationId,
                        "",
                        null
                );
            }

            telemetryTenantRepository.updateReadingLocation(
                    operatorWithSchema.schemaName(),
                    readingId,
                    latitude,
                    longitude,
                    operatorId
            );

            return CreateReadingResponse.builder()
                    .success(true)
                    .message(localizationService.localizeMessage("Location saved successfully.", languageKey))
                    .qualityStatus("CONFIRMED")
                    .build();
        } catch (Exception e) {
            String safeContactId = request != null ? request.resolveContactId() : null;
            log.error("Error processing location for contactId {}: {}", safeContactId, e.getMessage(), e);
            String languageKey = localizationService.resolveLanguageKeyForContact(safeContactId);
            String descriptiveMessage = localizationService.resolveUserFacingErrorMessage(e, "Location could not be saved.", languageKey);
            return CreateReadingResponse.builder()
                    .success(false)
                    .message(descriptiveMessage)
                    .qualityStatus("REJECTED")
                    .correlationId(safeContactId)
                    .build();
        }
    }

    public CreateReadingResponse updatePreviousReadingMessage(UpdatedPreviousReadingRequest request) {
        try {
            if (request.getContactId() == null || request.getContactId().isBlank()) {
                throw new IllegalStateException("contactId is required");
            }
            if (request.getReading() == null || request.getReading().isBlank()) {
                throw new IllegalStateException("reading is required");
            }

            String normalizedReading = request.getReading().trim().replace(",", "");
            if (!normalizedReading.matches("^\\d+(\\.\\d+)?$")) {
                throw new IllegalStateException("reading must be numeric");
            }
            BigDecimal readingValue = new BigDecimal(normalizedReading);
            if (readingValue.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException("reading must be greater than zero");
            }

            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(request.getContactId());
            Long operatorId = operatorWithSchema.operator().id();
            Integer tenantId = operatorWithSchema.operator().tenantId();
            if (tenantId == null) {
                throw new IllegalStateException("Operator tenant could not be resolved");
            }
            String languageKey = localizationService.normalizeLanguageKey(
                    operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId)
            );

            Long schemeId = telemetryTenantRepository
                    .findFirstSchemeForUser(operatorWithSchema.schemaName(), operatorId)
                    .orElseThrow(() -> new IllegalStateException("Operator is not mapped to any scheme"));

            LocalDate today = ReadingTime.today();

            Optional<TelemetryCompletedFlowReading> targetDayRecordOpt = telemetryTenantRepository
                    .findLatestCompletedFlowReadingBeforeDate(operatorWithSchema.schemaName(), schemeId, operatorId, today);
            if (targetDayRecordOpt.isEmpty()) {
                return CreateReadingResponse.builder()
                        .success(false)
                        .message(localizationService.localizeMessage(
                                "No previous submitted reading found. Please submit a reading first.",
                                languageKey
                        ))
                        .qualityStatus("REJECTED")
                        .correlationId(request.getContactId())
                        .build();
            }
            TelemetryCompletedFlowReading targetDayRecord = targetDayRecordOpt.get();
            LocalDate targetDay = targetDayRecord.readingDate();

            Optional<TelemetryCompletedFlowReading> dayBeforeTargetOpt = telemetryTenantRepository
                    .findLatestCompletedFlowReadingBeforeDate(operatorWithSchema.schemaName(), schemeId, operatorId, targetDay);

            Optional<TelemetryCompletedFlowReading> dayAfterTargetOpt = telemetryTenantRepository
                    .findEarliestCompletedFlowReadingAfterDate(operatorWithSchema.schemaName(), schemeId, operatorId, targetDay);

            if (dayBeforeTargetOpt.isPresent()) {
                TelemetryCompletedFlowReading dayBeforeTarget = dayBeforeTargetOpt.get();
//                if (readingValue.compareTo(dayBeforeTarget.confirmedReading()) <= 0) {
//                    return CreateReadingResponse.builder()
//                            .success(false)
//                            .message(localizationService.localizeMessage(
//                                    "Reading must be greater than the reading on " + dayBeforeTarget.readingDate()
//                                            + " (" + toPlain(dayBeforeTarget.confirmedReading()) + "). Submitted reading: "
//                                            + toPlain(readingValue) + ".",
//                                    languageKey
//                            ))
//                            .qualityStatus("REJECTED")
//                            .correlationId(request.getContactId())
//                            .meterReading(readingValue)
//                            .build();
//                }
            }

            // A hand-typed correction moves confirmed_reading only. This used to call
            // updateReadingValues, which also overwrote extracted_reading and so destroyed the only
            // record of what FlowVision read off that day's photo. Retag MANUAL only when the value
            // actually moves, so restating the stored number keeps an existing ROLLOVER_RESOLVED or
            // EXTERNALLY_ASSERTED marker.
            telemetryTenantRepository.updateConfirmedReading(
                    operatorWithSchema.schemaName(),
                    targetDayRecord.id(),
                    readingValue,
                    operatorId,
                    RolloverResolutionService.manualConfirmSource(
                            readingValue, targetDayRecord.confirmedReading())
            );
            BigDecimal previousDayConfirmedReading = dayBeforeTargetOpt
                    .map(TelemetryCompletedFlowReading::confirmedReading)
                    .orElse(BigDecimal.ZERO);
            BigDecimal targetDayWaterQuantity = readingValue.subtract(previousDayConfirmedReading);
            telemetryEventPublisher.publishWaterQuantityRecorded(
                    tenantId,
                    schemeId,
                    operatorId,
                    targetDay,
                    targetDayWaterQuantity,
                    1
            );
            if (dayAfterTargetOpt.isPresent()) {
                TelemetryCompletedFlowReading dayAfterTarget = dayAfterTargetOpt.get();
                BigDecimal dayAfterWaterQuantity = dayAfterTarget.confirmedReading().subtract(readingValue);
                telemetryEventPublisher.publishWaterQuantityRecorded(
                        tenantId,
                        schemeId,
                        operatorId,
                        dayAfterTarget.readingDate(),
                        dayAfterWaterQuantity,
                        1
                );
            }

            String correlationId = targetDayRecord.correlationId();
            if (correlationId == null || correlationId.isBlank()) {
                correlationId = "previous-day-" + UUID.randomUUID();
            }

            return CreateReadingResponse.builder()
                    .success(true)
                    .message("Previous reading updated successfully.")
                    .qualityStatus("CONFIRMED")
                    .correlationId(correlationId)
                    .meterReading(readingValue)
                    .build();
        } catch (Exception e) {
            log.error("Error updating previous day reading for contactId {}: {}", request.getContactId(), e.getMessage(), e);
            String languageKey = localizationService.resolveLanguageKeyForContact(request.getContactId());
            String descriptiveMessage = localizationService.resolveUserFacingErrorMessage(
                    e,
                    "Previous reading could not be updated.",
                    languageKey
            );
            return CreateReadingResponse.builder()
                    .success(false)
                    .message(descriptiveMessage)
                    .qualityStatus("REJECTED")
                    .correlationId(request.getContactId())
                    .build();
        }
    }

    private Optional<BigDecimal> loadWaterNorm(Integer tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return safeFindConfigValue(tenantId, "WATER_NORM")
                .flatMap(raw -> {
                    try {
                        JsonNode root = objectMapper.readTree(raw);
                        String value = root != null ? root.path("value").asText(null) : null;
                        if (value == null || value.isBlank()) {
                            return Optional.empty();
                        }
                        // WATER_NORM is stored as string; tolerate commas/spaces.
                        String normalized = value.trim().replace(",", "");
                        if (!normalized.matches("^\\d+(\\.\\d+)?$")) {
                            return Optional.empty();
                        }
                        BigDecimal norm = new BigDecimal(normalized);
                        return norm.compareTo(BigDecimal.ZERO) > 0 ? Optional.of(norm) : Optional.empty();
                    } catch (Exception e) {
                        log.warn("Invalid WATER_NORM config for tenantId {}: {}", tenantId, e.getMessage());
                        return Optional.empty();
                    }
                });
    }

    private Optional<String> safeFindConfigValue(Integer tenantId, String key) {
        Optional<String> opt = tenantConfigRepository.findConfigValue(tenantId, key);
        // Some mocks may return null; treat as empty.
        return opt == null ? Optional.empty() : opt;
    }

    private Optional<WaterSupplyThreshold> loadWaterSupplyThreshold(Integer tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        // Prefer tenant-specific override keys, then tenant-level default, then system default (tenant_id = 0).
        Optional<String> rawOpt = safeFindConfigValue(tenantId, "TENANT_WATER_QUANTITY_SUPPLY_THRESHOLD")
                .or(() -> safeFindConfigValue(tenantId, "WATER_QUANTITY_SUPPLY_THRESHOLD"))
                .or(() -> safeFindConfigValue(0, "WATER_QUANTITY_SUPPLY_THRESHOLD"));
        if (rawOpt.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(rawOpt.get());
            if (root == null || root.isNull() || !root.isObject()) {
                return Optional.empty();
            }
            double under = root.path("undersupplyThresholdPercent").asDouble(Double.NaN);
            double over = root.path("oversupplyThresholdPercent").asDouble(Double.NaN);
            if (!Double.isFinite(under) || !Double.isFinite(over)) {
                return Optional.empty();
            }
            if (under < 0.0d || under > 100.0d) {
                return Optional.empty();
            }
            if (over < 0.0d || over > 1000.0d) {
                return Optional.empty();
            }
            return Optional.of(new WaterSupplyThreshold(under, over));
        } catch (Exception e) {
            log.warn("Invalid water supply threshold config for tenantId {}: {}", tenantId, e.getMessage());
            return Optional.empty();
        }
    }

    private static String toPlain(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private record WaterSupplyThreshold(double undersupplyThresholdPercent, double oversupplyThresholdPercent) {
    }

    /**
     * The single guard every resolved issue reason passes before it is stored or published.
     *
     * <p>Must be called <em>after</em> reason resolution and <em>before</em> any database write or
     * Kafka publish, so a rejected reason leaves no trace. Two rules, with deliberately different
     * scopes — keep both here rather than splitting them, so a new resolution path cannot pick up
     * one and miss the other:
     *
     * <p><strong>Length, unconditionally.</strong> {@code IssueReportRequest} caps the field the
     * operator types, but a resolved reason need not be operator input: the template path takes a
     * label from {@code TemplateOption}, the config paths take one from
     * {@code findIssueReportReasons} or the {@code SUPPLY_OUTAGE_REASONS} JSON, and all three
     * arrive from the database unmeasured. Over
     * {@link IssueReportRequest#MAX_ISSUE_REASON_LENGTH} the value still writes fine to the
     * {@code TEXT} columns on this side and then fails the analytics consumer's insert into
     * {@code VARCHAR(255)} {@code fact_water_quantity_table.outage_reason} — the reason is lost
     * where it is actually reported on, and lost asynchronously, so nothing surfaces here.
     * Rejecting the whole submission is the point: truncating would silently corrupt a reason a
     * tenant configured, and the tenant needs to fix the label instead.
     *
     * <p><strong>Characters, only for free text.</strong> {@code matchedConfiguredReason} is why
     * this parameter exists: a reason the operator picked from the menu is constrained to the
     * configured set already, and tenant-configured labels may legitimately contain punctuation
     * that {@link #ISSUE_REASON_ALLOWED} forbids. Applying the allowlist unconditionally would
     * false-reject a valid menu pick. Note this cannot be simplified to
     * {@code reasons.contains(resolvedIssueReason)} — the template path can fall back to
     * {@code TemplateOption.canonicalLabel()}, which is not a member of the label list.
     *
     * @throws IllegalStateException for an over-length reason, with a message the localiser
     *                               deliberately does not recognise: an operator cannot fix a
     *                               misconfigured label, so they get the caller's generic reply
     *                               while the suppressed detail is logged for ops. For a
     *                               disallowed character, with the message the localiser maps to
     *                               "Issue reason can only contain letters, numbers, and spaces."
     */
    private static void requireStorableIssueReason(String resolvedIssueReason,
                                                   boolean matchedConfiguredReason) {
        if (resolvedIssueReason != null
                && resolvedIssueReason.length() > IssueReportRequest.MAX_ISSUE_REASON_LENGTH) {
            throw new IllegalStateException("issueReason exceeds the maximum storable length of "
                    + IssueReportRequest.MAX_ISSUE_REASON_LENGTH + " characters");
        }
        if (matchedConfiguredReason) {
            return;
        }
        if (!ISSUE_REASON_ALLOWED.matcher(resolvedIssueReason).matches()) {
            throw new IllegalStateException("issueReason contains invalid characters");
        }
    }

    private Optional<String> resolveSelection(String rawSelection, List<String> options) {
        String value = rawSelection.trim();
        Integer index = parseSelectionIndex(value, options.size());
        if (index != null) {
            return Optional.of(options.get(index));
        }
        return options.stream().filter(v -> v.equalsIgnoreCase(value)).findFirst();
    }

    private boolean shouldStoreIssueAsAnomaly(String selectedKey, String rawIssueReason, Set<String> anomalySelectionKeys) {
        // Primary: selection key resolved from templates/config by index or label match.
        if (selectedKey != null) {
            String normalizedKey = selectedKey.trim();
            if (anomalySelectionKeys.contains(normalizedKey)) {
                return true;
            }
            if (isReasonKey(normalizedKey)) {
                return false;
            }
        }
        // Fallback: raw numeric selection (legacy clients can send only the number).
        if (rawIssueReason == null) {
            return false;
        }
        String trimmed = rawIssueReason.trim();
        return "2".equals(trimmed) || "3".equals(trimmed) || "5".equals(trimmed);
    }

    private boolean isReasonKey(String key) {
        if (key == null) {
            return false;
        }
        String normalized = key.trim().toUpperCase(Locale.ROOT);
        return normalized.startsWith("REASON_");
    }

    private String normalizeIssueReportSelectedKey(String selectedKey) {
        if (selectedKey == null || selectedKey.isBlank()) {
            return selectedKey;
        }
        String key = selectedKey.trim().toUpperCase(Locale.ROOT);
        return switch (key) {
            case "REASON_1" -> "meterReplace";
            case "REASON_2" -> "incorrectReadingEnteredPreviously";
            case "REASON_3" -> "noReadingSubmission";
            case "REASON_4" -> "noWaterSupply";
            default -> selectedKey;
        };
    }

    private String resolveIssueSelectionKey(String rawIssueReason,
                                            String resolvedIssueReason,
                                            List<String> reasons,
                                            List<String> selectionKeys) {
        String raw = rawIssueReason == null ? "" : rawIssueReason.trim();
        Integer index = parseSelectionIndex(raw, reasons.size());
        if (index != null) {
            if (index >= 0 && index < selectionKeys.size()) {
                return selectionKeys.get(index);
            }
            if (index >= 0 && index < reasons.size()) {
                return toLowerCamelToken(reasons.get(index));
            }
        }

        for (int i = 0; i < reasons.size(); i++) {
            if (reasons.get(i).equalsIgnoreCase(resolvedIssueReason)) {
                if (i < selectionKeys.size()) {
                    return selectionKeys.get(i);
                }
                return toLowerCamelToken(resolvedIssueReason);
            }
        }

        String selected = toLowerCamelToken(resolvedIssueReason);
        return selected == null || selected.isBlank() ? "others" : selected;
    }

    private Integer parseSelectionIndex(String rawSelection, int optionCount) {
        if (rawSelection == null || rawSelection.isBlank()) {
            return null;
        }

        String trimmed = rawSelection.trim();
        int digitEnd = 0;
        while (digitEnd < trimmed.length() && Character.isDigit(trimmed.charAt(digitEnd))) {
            digitEnd++;
        }
        if (digitEnd > 0) {
            int oneBased = Integer.parseInt(trimmed.substring(0, digitEnd));
            if (oneBased >= 1 && oneBased <= optionCount) {
                return oneBased - 1;
            }
            return null;
        }

        String normalized = trimmed.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", "");
        int oneBased = switch (normalized) {
            case "one", "first", "ek", "एक" -> 1;
            case "two", "second", "do", "दो" -> 2;
            case "three", "third", "teen", "तीन" -> 3;
            case "four", "fourth", "char", "चार" -> 4;
            case "five", "fifth", "paanch", "panch", "पांच", "पाँच" -> 5;
            default -> -1;
        };
        if (oneBased >= 1 && oneBased <= optionCount) {
            return oneBased - 1;
        }
        return null;
    }

    private String toLowerCamelToken(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String sanitized = input.replaceAll("[^A-Za-z0-9]+", " ").trim();
        if (sanitized.isBlank()) {
            return null;
        }
        String[] parts = sanitized.split("\\s+");
        if (parts.length == 0) {
            return null;
        }
        StringBuilder out = new StringBuilder(parts[0].toLowerCase(Locale.ROOT));
        for (int i = 1; i < parts.length; i++) {
            if (parts[i].isBlank()) {
                continue;
            }
            String lower = parts[i].toLowerCase(Locale.ROOT);
            out.append(Character.toUpperCase(lower.charAt(0)));
            if (lower.length() > 1) {
                out.append(lower.substring(1));
            }
        }
        return out.toString();
    }

    private List<Long> resolveAnalyticsUserIds(String schemaName, Long schemeId, Long fallbackUserId) {
        List<Long> subDivisionalOfficerIds = telemetryTenantRepository.findSubDivisionalOfficerUserIdsForScheme(schemaName, schemeId);
        if (subDivisionalOfficerIds != null && !subDivisionalOfficerIds.isEmpty()) {
            return subDivisionalOfficerIds;
        }
        List<Long> sectionOfficerIds = telemetryTenantRepository.findSectionOfficerUserIdsForScheme(schemaName, schemeId);
        if (sectionOfficerIds != null && !sectionOfficerIds.isEmpty()) {
            return sectionOfficerIds;
        }
        return List.of(fallbackUserId);
    }

    private List<Long> resolveSdoUserIds(String schemaName, Long schemeId) {
        List<Long> subDivisionalOfficerIds = telemetryTenantRepository.findSubDivisionalOfficerUserIdsForScheme(schemaName, schemeId);
        return subDivisionalOfficerIds == null ? List.of() : subDivisionalOfficerIds;
    }

    private int calculateConsecutiveDays(List<LocalDate> dates, LocalDate startDate) {
        if (dates == null || dates.isEmpty() || startDate == null) {
            return 0;
        }
        int count = 0;
        LocalDate cursor = startDate;
        for (LocalDate date : dates) {
            if (date == null) {
                continue;
            }
            if (date.isEqual(cursor)) {
                count++;
                cursor = cursor.minusDays(1);
            } else if (date.isBefore(cursor)) {
                break;
            }
        }
        return count;
    }
}
