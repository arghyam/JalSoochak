package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class ConversationLocalizationService {
    private static final Pattern SUBMITTED_PREVIOUS_PATTERN = Pattern.compile(
            "(?i)submitted\\s+reading:\\s*([^\\.]+)\\.\\s*previous\\s+reading:\\s*([^\\.]+)\\.?"
    );
    private static final Pattern EXTRACTED_READING_PATTERN = Pattern.compile(
            "(?i)extracted\\s+reading:\\s*([^\\.]+)"
    );
    /** {@code SubmittedValueCapture.aboveMaximumMessage}: the maximum, then the unit if the channel has one. */
    private static final Pattern ABOVE_MAXIMUM_PATTERN = Pattern.compile(
            "(?i)reading can't be more than\\s+(\\d+(?:\\.\\d+)?)\\s*([^.]*?)\\.?\\s*$"
    );

    private final OperatorContextService operatorContextService;
    private final ConversationMessageResolver messageResolver;

    public ConversationLocalizationService(OperatorContextService operatorContextService,
                                           ConversationMessageResolver messageResolver) {
        this.operatorContextService = operatorContextService;
        this.messageResolver = messageResolver;
    }

    public String normalizeLanguageKey(String language) {
        if (language == null) {
            return "";
        }

        String raw = language.trim();
        String lower = raw.toLowerCase();

        if ("हिंदी".equals(raw) || "हिन्दी".equals(raw) || "hindi".equals(lower)) {
            return "hindi";
        }
        if ("english".equals(lower) || "inglish".equals(lower)) {
            return "english";
        }

        return lower.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
    }

    public String resolveLanguageKeyForContact(String contactId) {
        try {
            if (contactId == null || contactId.isBlank()) {
                return "english";
            }
            TelemetryOperatorWithSchema operatorWithSchema = operatorContextService.resolveOperatorWithSchema(contactId);
            Integer tenantId = operatorWithSchema.operator().tenantId();
            if (tenantId == null) {
                return "english";
            }
            String language = operatorContextService.resolveOperatorLanguage(operatorWithSchema, tenantId);
            return normalizeLanguageKey(language);
        } catch (Exception ignored) {
            return "english";
        }
    }

    /**
     * Turns an exception into text that is safe to show an operator or return to an API caller.
     *
     * <p>ERROR-DISCLOSURE: this used to end with {@code return localizeMessage(message.trim(), ...)} —
     * anything the rule list did not recognise was handed to the user verbatim. Every caller catches
     * {@code Exception}, so that path was reachable by far more than the business validations it was
     * written for: a {@code DataAccessException} would put SQL text and schema names such as
     * {@code tenant_as} into a WhatsApp reply, and messages like {@code "Missing required column …"},
     * {@code "Tenant not found for schema …"}, {@code "PII_ENCRYPTION_KEY must decode to exactly 32
     * bytes"} or {@code "No operator found for contactId 91XXXXXXXXXX"} (a phone number echoed back)
     * were all one unmapped throw away from the user.
     *
     * <p>It is now an allowlist: only a message matched by {@link #USER_FACING_RULES} is shown, and
     * everything else becomes the caller's own context-specific {@code fallback}. Adding a new
     * user-facing message therefore means adding a rule — the safe default is not to disclose.
     * Suppressions are logged so an over-eager fallback is visible in ops rather than silent.
     */
    public String resolveUserFacingErrorMessage(Exception e, ConversationMessageCode fallback, String languageKey) {
        if (e == null) {
            return messageResolver.get(fallback, languageKey);
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return messageResolver.get(fallback, languageKey);
        }

        String normalized = message.trim().toLowerCase(Locale.ROOT);

        // The one message we pass through: our own code composes it, and it carries the operator's own
        // submitted value and their scheme's previous reading — which localizeMessage also parses.
        if (normalized.contains("less than previous")) {
            return localizeMessage(message.trim(), languageKey);
        }

        for (UserFacingRule rule : USER_FACING_RULES) {
            if (normalized.contains(rule.match())) {
                return messageResolver.get(rule.safeMessage(), languageKey);
            }
        }

        // Deny by default. The exception itself is already logged with its stack trace by the caller;
        // this line names the message that was withheld so a missing rule is easy to spot and add.
        log.info("error_message_suppressed exceptionType={} message=\"{}\"",
                e.getClass().getSimpleName(), sanitizeForLog(message));
        return messageResolver.get(fallback, languageKey);
    }

    /** A recognised, safe-to-disclose error: {@code match} is compared against the lower-cased message. */
    private record UserFacingRule(String match, ConversationMessageCode safeMessage) {
    }

    private static UserFacingRule rule(String match, ConversationMessageCode safeMessage) {
        return new UserFacingRule(match, safeMessage);
    }

    /**
     * ERROR-DISCLOSURE: the allowlist, in order — the first match wins, so a more specific phrase must
     * come before a phrase it contains. Two such pairs exist and are marked below.
     *
     * <p>Every entry is a message our own code throws deliberately for the user's benefit. Nothing here
     * names a config key, column, schema, tenant, phone number, threshold or internal component.
     */
    private static final List<UserFacingRule> USER_FACING_RULES = List.of(
            rule("duplicate image", ConversationMessageCode.DUPLICATE_IMAGE),

            // ── Manual reading. MUST precede the generic "reading …" rules: the string
            // "manualreading is required" contains "reading is required". ─────────────────────────────
            rule("manualreading is required", ConversationMessageCode.MANUAL_READING_REQUIRED),
            rule("manualreading must be numeric", ConversationMessageCode.MANUAL_READING_NOT_NUMERIC),
            rule("manualreading must be greater than zero", ConversationMessageCode.MANUAL_READING_NOT_POSITIVE),

            // ── Reading values ──────────────────────────────────────────────────────────────────────
            rule("reading is required", ConversationMessageCode.READING_REQUIRED),
            rule("reading must be numeric", ConversationMessageCode.READING_NOT_NUMERIC),
            rule("reading must be greater than zero", ConversationMessageCode.READING_NOT_POSITIVE),
            rule("confirmedreading must be a non-negative number", ConversationMessageCode.READING_NEGATIVE),
            rule("either readingvalue or readingurl must be provided",
                    ConversationMessageCode.READING_OR_IMAGE_REQUIRED),

            // ── Selections ──────────────────────────────────────────────────────────────────────────
            rule("language selection is required", ConversationMessageCode.LANGUAGE_SELECTION_REQUIRED),
            rule("invalid language selection", ConversationMessageCode.LANGUAGE_SELECTION_INVALID),
            rule("no language options configured", ConversationMessageCode.LANGUAGE_OPTIONS_NOT_CONFIGURED),
            rule("channel selection is required", ConversationMessageCode.CHANNEL_SELECTION_REQUIRED),
            rule("invalid channel selection", ConversationMessageCode.CHANNEL_SELECTION_INVALID),
            rule("no channel options configured", ConversationMessageCode.CHANNEL_OPTIONS_NOT_CONFIGURED),
            rule("selected channel is no longer available", ConversationMessageCode.CHANNEL_NO_LONGER_AVAILABLE),
            rule("item selection is required", ConversationMessageCode.ITEM_SELECTION_REQUIRED),
            rule("invalid item selection", ConversationMessageCode.ITEM_SELECTION_INVALID),
            rule("no item options configured", ConversationMessageCode.ITEM_OPTIONS_NOT_CONFIGURED),
            rule("scheme selection is required", ConversationMessageCode.SCHEME_SELECTION_REQUIRED),
            rule("invalid scheme selection", ConversationMessageCode.SCHEME_SELECTION_INVALID),
            rule("meter change reason selection is required", ConversationMessageCode.METER_CHANGE_REASON_REQUIRED),
            rule("invalid meter change reason selection", ConversationMessageCode.METER_CHANGE_REASON_INVALID),
            // Config keys are named in the thrown text; the reply says only that it is not set up.
            rule("meter_change_reasons config", ConversationMessageCode.METER_CHANGE_REASONS_NOT_CONFIGURED),
            rule("supply_outage_reasons config", ConversationMessageCode.SUBMISSION_REASONS_NOT_CONFIGURED),

            // ── Scheme / operator resolution. "scheme not found for the provided state or centre
            // scheme id" MUST precede the generic "scheme not found". ────────────────────────────────
            rule("scheme not found for the provided state or centre scheme id",
                    ConversationMessageCode.SCHEME_NOT_FOUND_FOR_ID),
            rule("no operator is mapped to the submitted scheme and no phone number was provided",
                    ConversationMessageCode.NO_OPERATOR_FOR_SCHEME),
            rule("operator is not mapped to any scheme", ConversationMessageCode.NO_SCHEME_FOR_OPERATOR),
            rule("operator is not mapped to the provided state or centre scheme",
                    ConversationMessageCode.OPERATOR_NOT_MAPPED_TO_PROVIDED_SCHEME),
            rule("operator does not belong to the specified scheme",
                    ConversationMessageCode.OPERATOR_NOT_MAPPED_TO_SELECTED_SCHEME),
            rule("scheme not found", ConversationMessageCode.SCHEME_NOT_FOUND),
            rule("not authorized for this scheme", ConversationMessageCode.SCHEME_NOT_AUTHORIZED),
            rule("schemeid must be a positive integer", ConversationMessageCode.SCHEME_ID_NOT_POSITIVE),

            // Catches "No operator found for contactId 91XXXXXXXXXX", which echoed the phone number back.
            rule("no operator found", ConversationMessageCode.OPERATOR_NOT_RESOLVED),
            rule("operator not found", ConversationMessageCode.OPERATOR_NOT_RESOLVED),
            rule("operator could not be resolved", ConversationMessageCode.OPERATOR_NOT_RESOLVED),
            rule("user not found", ConversationMessageCode.OPERATOR_NOT_RESOLVED),
            // Also covers "Operator tenant could not be resolved".
            rule("tenant could not be resolved", ConversationMessageCode.OPERATOR_NOT_RESOLVED),

            // ── Contact / request identity ──────────────────────────────────────────────────────────
            rule("contactid is required", ConversationMessageCode.CONTACT_NOT_IDENTIFIED),
            rule("contactid must be provided", ConversationMessageCode.CONTACT_NOT_IDENTIFIED),
            rule("phonenumber is required", ConversationMessageCode.PHONE_NUMBER_REQUIRED),
            rule("phonenumber must be provided", ConversationMessageCode.PHONE_NUMBER_REQUIRED),
            rule("correlationid must be provided", ConversationMessageCode.REQUEST_REFERENCE_MISSING),

            // ── Media ───────────────────────────────────────────────────────────────────────────────
            rule("invalid media", ConversationMessageCode.INVALID_MEDIA),
            rule("failed to download image", ConversationMessageCode.IMAGE_DOWNLOAD_FAILED),

            // ── Issue report ────────────────────────────────────────────────────────────────────────
            rule("issuereason contains invalid characters", ConversationMessageCode.ISSUE_REASON_INVALID_CHARACTERS),
            rule("issuereason is required", ConversationMessageCode.ISSUE_REASON_REQUIRED),

            // ── Location. One reply for every shape of bad geolocation. ─────────────────────────────
            rule("latitude is required", ConversationMessageCode.LOCATION_INVALID),
            rule("longitude is required", ConversationMessageCode.LOCATION_INVALID),
            rule("latitude must be between", ConversationMessageCode.LOCATION_INVALID),
            rule("longitude must be between", ConversationMessageCode.LOCATION_INVALID),
            rule("geolocation", ConversationMessageCode.LOCATION_INVALID),

            // ── Readings lookup ─────────────────────────────────────────────────────────────────────
            // Deliberately the same answer for "unknown contact" and "contact in another tenant".
            rule("no reading found for operator", ConversationMessageCode.NO_READING_FOR_OPERATOR),
            rule("reading not found", ConversationMessageCode.READING_NOT_FOUND),
            rule("target reading date is missing", ConversationMessageCode.TARGET_READING_DATE_MISSING),

            rule("invalid api key", ConversationMessageCode.INVALID_API_KEY)
    );

    /** Keeps a withheld message on one log line; it is server-side only and never returned. */
    private static String sanitizeForLog(String message) {
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }

    /**
     * Translates English written outside the chatbot — reading results shared with the partner API,
     * tenant-configured reason names, a tenant's language confirmation — by recognising the text and
     * rendering the {@link ConversationMessageCode} it carries. Copy the chatbot writes itself is looked
     * up by code instead, through {@link ConversationMessageResolver}.
     *
     * <p>English text, and text in a language that does not translate what it was recognised as, is
     * returned unchanged; so is anything unrecognised.
     */
    public String localizeMessage(String message, String languageKey) {
        if (message == null || message.isBlank() || messageResolver.isEnglish(languageKey)) {
            return message;
        }
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        for (Recognizer recognizer : RECOGNIZERS) {
            if (recognizer.matches().test(normalized)) {
                Recognized recognized = recognizer.recognize().apply(message);
                return messageResolver.translation(recognized.code(), languageKey, recognized.args())
                        .map(recognized.assemble())
                        .orElse(message);
            }
        }
        return message;
    }

    /** Matches the lower-cased text, then reads the message and its arguments out of the original. */
    private record Recognizer(Predicate<String> matches, Function<String, Recognized> recognize) {
    }

    /** What a text was recognised as; {@code assemble} turns the translation into the reply. */
    private record Recognized(ConversationMessageCode code, Object[] args, UnaryOperator<String> assemble) {
        Recognized(ConversationMessageCode code, Object... args) {
            this(code, args, UnaryOperator.identity());
        }
    }

    private static Recognizer recognize(String phrase, ConversationMessageCode code) {
        return new Recognizer(normalized -> normalized.contains(phrase), text -> new Recognized(code));
    }

    /** In order: the first match wins. */
    private static final List<Recognizer> RECOGNIZERS = List.of(
            recognize("duplicate image submission detected", ConversationMessageCode.DUPLICATE_IMAGE),
            new Recognizer(normalized -> normalized.contains("reading cannot be less than previous"), text -> {
                Matcher matcher = SUBMITTED_PREVIOUS_PATTERN.matcher(text);
                return matcher.find()
                        ? new Recognized(ConversationMessageCode.READING_BELOW_PREVIOUS_WITH_VALUES,
                                matcher.group(1).trim(), matcher.group(2).trim())
                        : new Recognized(ConversationMessageCode.READING_BELOW_PREVIOUS);
            }),
            // A tenant's confirmation template: only its "Language selected:" label is translated.
            new Recognizer(normalized -> normalized.contains("language selected:"),
                    text -> new Recognized(ConversationMessageCode.LANGUAGE_SELECTED_LABEL, new Object[0],
                            label -> text.replaceFirst("(?i)language selected:", Matcher.quoteReplacement(label)))),

            // ── Default reason names, as stored in a tenant's reason config ─────────────────────────
            recognize("electricity supply disconnected",
                    ConversationMessageCode.SUPPLY_REASON_ELECTRICITY_DISCONNECTED),
            recognize("no electricity supply today", ConversationMessageCode.SUPPLY_REASON_NO_ELECTRICITY_TODAY),
            recognize("pump failure", ConversationMessageCode.SUPPLY_REASON_PUMP_FAILURE),
            recognize("pipeline break", ConversationMessageCode.SUPPLY_REASON_PIPELINE_BREAK),
            recognize("water quality issues", ConversationMessageCode.SUPPLY_REASON_WATER_QUALITY),
            recognize("source drying", ConversationMessageCode.SUPPLY_REASON_SOURCE_DRYING),
            recognize("natural calamity", ConversationMessageCode.SUPPLY_REASON_NATURAL_CALAMITY),
            new Recognizer("others"::equals, text -> new Recognized(ConversationMessageCode.ISSUE_REASON_OTHERS)),
            recognize("meter replaced", ConversationMessageCode.ISSUE_REASON_METER_REPLACED),
            recognize("meter not working", ConversationMessageCode.ISSUE_REASON_METER_NOT_WORKING),
            // Also covers "meter damaged".
            recognize("meter damage", ConversationMessageCode.ISSUE_REASON_METER_DAMAGE),

            // ── Reading results ─────────────────────────────────────────────────────────────────────
            new Recognizer(normalized -> normalized.contains("reading captured successfully"), text -> {
                Matcher matcher = EXTRACTED_READING_PATTERN.matcher(text);
                return matcher.find()
                        ? new Recognized(ConversationMessageCode.READING_CAPTURED_WITH_VALUE, matcher.group(1).trim())
                        : new Recognized(ConversationMessageCode.READING_CAPTURED);
            }),
            new Recognizer(normalized -> normalized.contains("low ocr confidence"), text -> {
                Matcher matcher = EXTRACTED_READING_PATTERN.matcher(text);
                return matcher.find()
                        ? new Recognized(ConversationMessageCode.LOW_OCR_CONFIDENCE_WITH_VALUE, matcher.group(1).trim())
                        : new Recognized(ConversationMessageCode.LOW_OCR_CONFIDENCE);
            }),
            recognize("invalid reading value", ConversationMessageCode.INVALID_READING_VALUE),
            recognize("no meter found in the photo", ConversationMessageCode.NO_METER_IN_PHOTO),
            recognize("could not read meter value from image", ConversationMessageCode.UNREADABLE_IMAGE),
            recognize("meter photos are not supported for your reading channel",
                    ConversationMessageCode.PHOTO_NOT_SUPPORTED),
            new Recognizer(normalized -> normalized.startsWith("reading can't be more than"), text -> {
                Matcher matcher = ABOVE_MAXIMUM_PATTERN.matcher(text.trim());
                if (!matcher.find()) {
                    return new Recognized(ConversationMessageCode.READING_ABOVE_MAXIMUM);
                }
                String unit = matcher.group(2).trim();
                if (unit.isEmpty()) {
                    return new Recognized(ConversationMessageCode.READING_ABOVE_MAXIMUM_VALUE, matcher.group(1));
                }
                return new Recognized(ConversationMessageCode.READING_ABOVE_MAXIMUM_VALUE_UNIT, matcher.group(1),
                        "minutes".equalsIgnoreCase(unit) ? ConversationMessageCode.UNIT_MINUTES : unit);
            }),
            recognize("total pump running time for the day can't be more than 24 hours",
                    ConversationMessageCode.PDU_DAY_TOO_LONG),
            recognize("pump running time can't be more than 24 hours", ConversationMessageCode.PDU_RUN_TOO_LONG),
            recognize("ocr failed", ConversationMessageCode.OCR_FAILED),
            recognize("reading updated successfully", ConversationMessageCode.READING_UPDATED)
    );
}
