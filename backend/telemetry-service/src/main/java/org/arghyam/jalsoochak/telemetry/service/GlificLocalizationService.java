package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class GlificLocalizationService {
    private static final Pattern SUBMITTED_PREVIOUS_PATTERN = Pattern.compile(
            "(?i)submitted\\s+reading:\\s*([^\\.]+)\\.\\s*previous\\s+reading:\\s*([^\\.]+)\\.?"
    );
    private static final Pattern EXTRACTED_READING_PATTERN = Pattern.compile(
            "(?i)extracted\\s+reading:\\s*([^\\.]+)"
    );

    private final GlificOperatorContextService operatorContextService;

    public GlificLocalizationService(GlificOperatorContextService operatorContextService) {
        this.operatorContextService = operatorContextService;
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
    public String resolveUserFacingErrorMessage(Exception e, String fallback, String languageKey) {
        if (e == null) {
            return localizeMessage(fallback, languageKey);
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return localizeMessage(fallback, languageKey);
        }

        String normalized = message.trim().toLowerCase(Locale.ROOT);

        // The one message we pass through: our own code composes it, and it carries the operator's own
        // submitted value and their scheme's previous reading — which the Hindi localizer also parses.
        if (normalized.contains("less than previous")) {
            return localizeMessage(message.trim(), languageKey);
        }

        for (UserFacingRule rule : USER_FACING_RULES) {
            if (normalized.contains(rule.match())) {
                return localizeMessage(rule.safeMessage(), languageKey);
            }
        }

        // Deny by default. The exception itself is already logged with its stack trace by the caller;
        // this line names the message that was withheld so a missing rule is easy to spot and add.
        log.info("error_message_suppressed exceptionType={} message=\"{}\"",
                e.getClass().getSimpleName(), sanitizeForLog(message));
        return localizeMessage(fallback, languageKey);
    }

    /** A recognised, safe-to-disclose error: {@code match} is compared against the lower-cased message. */
    private record UserFacingRule(String match, String safeMessage) {
    }

    private static UserFacingRule rule(String match, String safeMessage) {
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
            rule("duplicate image", "Duplicate image submission detected. Please submit a new image."),

            // ── Manual reading. MUST precede the generic "reading …" rules: the string
            // "manualreading is required" contains "reading is required". ─────────────────────────────
            rule("manualreading is required", "manualReading is required."),
            rule("manualreading must be numeric", "Manual Reading must be numeric value"),
            rule("manualreading must be greater than zero", "manualReading must be greater than zero."),

            // ── Reading values ──────────────────────────────────────────────────────────────────────
            rule("reading is required", "Reading is required."),
            rule("reading must be numeric", "Reading must be a numeric value."),
            rule("reading must be greater than zero", "Reading must be greater than zero."),
            rule("confirmedreading must be a non-negative number", "Reading must be a non-negative number."),
            rule("either readingvalue or readingurl must be provided",
                    "A meter image or a reading value is required."),

            // ── Selections ──────────────────────────────────────────────────────────────────────────
            rule("language selection is required",
                    "Language selection is required. Please choose one of the listed options."),
            rule("invalid language selection", "Invalid language selection. Please restart the process"),
            rule("no language options configured", "Language options are not configured for this tenant."),
            rule("channel selection is required",
                    "Channel selection is required. Please choose one of the listed options."),
            rule("invalid channel selection", "Invalid channel selection. Please restart the process"),
            rule("no channel options configured", "Channel options are not configured for this tenant."),
            rule("selected channel is no longer available",
                    "Selected channel is no longer available. Please make sure you have a channel selected."),
            rule("item selection is required",
                    "Item selection is required. Please choose one of the listed options."),
            rule("invalid item selection", "Invalid item selection. Please choose a valid option from the list."),
            rule("no item options configured", "Item options are not configured for this tenant."),
            rule("scheme selection is required",
                    "Scheme selection is required. Please choose one of the listed options."),
            rule("invalid scheme selection", "Invalid scheme selection. Please choose a valid option from the list."),
            rule("meter change reason selection is required",
                    "Meter change reason is required. Please choose one of the listed options."),
            rule("invalid meter change reason selection",
                    "Invalid meter change reason. Please choose a valid option from the list."),
            // Config keys are named in the thrown text; the reply says only that it is not set up.
            rule("meter_change_reasons config", "Meter change reasons are not configured for this tenant."),
            rule("supply_outage_reasons config", "Submission reasons are not configured for this tenant."),

            // ── Scheme / operator resolution. "scheme not found for the provided state or centre
            // scheme id" MUST precede the generic "scheme not found". ────────────────────────────────
            rule("scheme not found for the provided state or centre scheme id",
                    "Scheme not found for the provided state or centre scheme id"),
            rule("no operator is mapped to the submitted scheme and no phone number was provided",
                    "No operator is mapped to the submitted scheme and no phone number was provided"),
            rule("operator is not mapped to any scheme", "No scheme is mapped to this operator."),
            rule("operator is not mapped to the provided state or centre scheme",
                    "Operator is not mapped to the provided state or centre scheme"),
            rule("operator does not belong to the specified scheme",
                    "Operator is not mapped to the selected scheme."),
            rule("scheme not found", "Scheme not found."),
            rule("not authorized for this scheme", "Not authorized for this scheme."),
            rule("schemeid must be a positive integer", "Scheme id must be a positive integer."),

            // Catches "No operator found for contactId 91XXXXXXXXXX", which echoed the phone number back.
            rule("no operator found", "Operator could not be resolved for this contact."),
            rule("operator not found", "Operator could not be resolved for this contact."),
            rule("operator could not be resolved", "Operator could not be resolved for this contact."),
            rule("user not found", "Operator could not be resolved for this contact."),
            // Also covers "Operator tenant could not be resolved".
            rule("tenant could not be resolved", "Operator could not be resolved for this contact."),

            // ── Contact / request identity ──────────────────────────────────────────────────────────
            rule("contactid is required", "Contact could not be identified. Please restart the process."),
            rule("contactid must be provided", "Contact could not be identified. Please restart the process."),
            rule("phonenumber is required", "Phone number is required."),
            rule("phonenumber must be provided", "Phone number is required."),
            rule("correlationid must be provided", "Request reference is missing. Please restart the process."),

            // ── Media ───────────────────────────────────────────────────────────────────────────────
            rule("invalid media", "Invalid media. Please submit a clear meter image."),
            rule("failed to download image", "Image could not be processed. Please try again"),

            // ── Issue report ────────────────────────────────────────────────────────────────────────
            rule("issuereason contains invalid characters",
                    "Issue reason can only contain letters, numbers, and spaces."),
            rule("issuereason is required", "Issue reason is required."),

            // ── Location. One reply for every shape of bad geolocation. ─────────────────────────────
            rule("latitude is required", "Location could not be saved. Please share a valid location."),
            rule("longitude is required", "Location could not be saved. Please share a valid location."),
            rule("latitude must be between", "Location could not be saved. Please share a valid location."),
            rule("longitude must be between", "Location could not be saved. Please share a valid location."),
            rule("geolocation", "Location could not be saved. Please share a valid location."),

            // ── Readings lookup ─────────────────────────────────────────────────────────────────────
            // Deliberately the same answer for "unknown contact" and "contact in another tenant".
            rule("no reading found for operator", "No reading found for operator"),
            rule("reading not found", "Reading not found."),
            rule("target reading date is missing", "Target reading date is missing."),

            rule("invalid api key", "Invalid API key")
    );

    /** Keeps a withheld message on one log line; it is server-side only and never returned. */
    private static String sanitizeForLog(String message) {
        return message.replace('\n', ' ').replace('\r', ' ').trim();
    }
    public String localizeMessage(String message, String languageKey) {
        if (message == null || message.isBlank()) {
            return message;
        }
        if (!"hindi".equals(languageKey)) {
            return message;
        }

        String normalized = message.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("duplicate image submission detected")) {
            return "डुप्लिकेट इमेज मिली है। कृपया नई इमेज सबमिट करें।";
        }
        if (normalized.contains("reading cannot be less than previous")) {
            Matcher matcher = SUBMITTED_PREVIOUS_PATTERN.matcher(message);
            if (matcher.find()) {
                String submitted = matcher.group(1).trim();
                String previous = matcher.group(2).trim();
                return "रीडिंग पिछली पुष्टि की गई रीडिंग से कम नहीं हो सकती। जमा की गई रीडिंग: "
                        + submitted + "। पिछली रीडिंग: " + previous + "।";
            }
            return "रीडिंग पिछली पुष्टि की गई रीडिंग से कम नहीं हो सकती।";
        }
        if (normalized.contains("manualreading is required")) {
            return "manualReading अनिवार्य है।";
        }
        if (normalized.contains("manual reading must be numeric value")
                || normalized.contains("manualreading must be numeric value")) {
            return "manualReading केवल संख्या होना चाहिए।";
        }
        if (normalized.contains("manualreading must be greater than zero")) {
            return "manualReading शून्य से बड़ा होना चाहिए।";
        }
        if (normalized.contains("language selection is required")) {
            return "भाषा चयन आवश्यक है। कृपया सूची में से एक विकल्प चुनें।";
        }
        if (normalized.contains("invalid language selection")) {
            return "अमान्य भाषा चयन। कृपया सूची से सही संख्या या भाषा चुनें।";
        }
        if (normalized.contains("language options are not configured")) {
            return "इस टेनेंट के लिए भाषा विकल्प कॉन्फ़िगर नहीं हैं।";
        }
        if (normalized.contains("language selected:")) {
            return message.replaceFirst("(?i)language selected:", "भाषा चुनी गई:");
        }
        if (normalized.contains("channel selection is required")) {
            return "चैनल चयन आवश्यक है। कृपया सूची में से एक विकल्प चुनें।";
        }
        if (normalized.contains("invalid channel selection")) {
            return "अमान्य चैनल चयन। कृपया सूची से सही संख्या या चैनल चुनें।";
        }
        if (normalized.contains("channel options are not configured")) {
            return "इस टेनेंट के लिए चैनल विकल्प कॉन्फ़िगर नहीं हैं।";
        }
        if (normalized.contains("selected channel is no longer available")) {
            return "चयनित चैनल अब उपलब्ध नहीं है। कृपया सुनिश्चित करें कि आपने एक चैनल चुना है।";
        }
        if (normalized.contains("item selection is required")) {
            return "विकल्प चयन आवश्यक है। कृपया सूची में से एक विकल्प चुनें।";
        }
        if (normalized.contains("invalid item selection")) {
            return "अमान्य विकल्प चयन। कृपया सूची से सही विकल्प चुनें।";
        }
        if (normalized.contains("item options are not configured")) {
            return "इस टेनेंट के लिए विकल्प कॉन्फ़िगर नहीं हैं।";
        }
        if (normalized.contains("no scheme is mapped to this operator")) {
            return "इस ऑपरेटर के लिए कोई स्कीम मैप नहीं है।";
        }
        if (normalized.contains("operator could not be resolved")) {
            return "इस संपर्क के लिए ऑपरेटर नहीं मिला।";
        }
        if (normalized.contains("invalid media")) {
            return "मीडिया अमान्य है। कृपया स्पष्ट मीटर इमेज भेजें।";
        }
        if (normalized.contains("issue reason can only contain letters, numbers, and spaces")) {
            return "समस्या का विवरण केवल अक्षर, अंक और स्पेस में ही दें।";
        }
        if (normalized.contains("successfully selected")) {
            return "सफलतापूर्वक चयनित।";
        }
        if (normalized.contains("image could not be processed")) {
            return "इमेज प्रोसेस नहीं हो सकी। कृपया दोबारा प्रयास करें।";
        }
        if (normalized.contains("manual reading could not be saved")) {
            return "मैनुअल रीडिंग सेव नहीं हो सकी। कृपया दोबारा प्रयास करें।";
        }
        if (normalized.contains("please type your issue in a few words")) {
            return "कृपया अपनी समस्या संक्षेप में लिखें।";
        }
        if (normalized.contains("please select your issue by typing any of the number")) {
            return "कृपया नंबर टाइप करके अपनी समस्या चुनें";
        }
        if (normalized.contains("please select the no submission reasons by typing any of the number")) {
            return "कृपया नंबर टाइप करके सबमिशन न होने के कारण चुनें";
        }
        if (normalized.contains("electricity supply disconnected")) {
            return "बिजली आपूर्ति बंद है";
        }
        if (normalized.contains("no electricity supply today")) {
            return "आज बिजली की आपूर्ति नहीं है";
        }
        if (normalized.contains("pump failure")) {
            return "पंप खराब है";
        }
        if (normalized.contains("pipeline break")) {
            return "पाइपलाइन टूटी है";
        }
        if (normalized.contains("water quality issues")) {
            return "पानी की गुणवत्ता में समस्या है";
        }
        if (normalized.contains("source drying")) {
            return "स्रोत सूख रहा है";
        }
        if (normalized.contains("natural calamity")) {
            return "प्राकृतिक आपदा";
        }
        if (normalized.equals("others")) {
            return "अन्य";
        }
        if (normalized.contains("meter replaced")) {
            return "मीटर बदला गया";
        }
        if (normalized.contains("meter not working")) {
            return "मीटर काम नहीं कर रहा";
        }
        if (normalized.contains("meter damaged") || normalized.contains("meter damage")) {
            return "मीटर खराब है";
        }
        if (normalized.contains("reading captured successfully")) {
            Matcher matcher = EXTRACTED_READING_PATTERN.matcher(message);
            if (matcher.find()) {
                String reading = matcher.group(1).trim();
                return "रीडिंग सफलतापूर्वक दर्ज हुई। प्राप्त रीडिंग: " + reading;
            }
            return "रीडिंग सफलतापूर्वक दर्ज हुई।";
        }
        if (normalized.contains("low ocr confidence")) {
            Matcher matcher = EXTRACTED_READING_PATTERN.matcher(message);
            if (matcher.find()) {
                String reading = matcher.group(1).trim();
                return "OCR भरोसा कम है। प्राप्त रीडिंग: " + reading + "। कृपया रीडिंग की पुष्टि करें।";
            }
            return "OCR भरोसा कम है। कृपया रीडिंग की पुष्टि करें।";
        }
        if (normalized.contains("invalid reading value")) {
            return "रीडिंग मान्य नहीं है।";
        }
        if (normalized.contains("could not read meter value from image")) {
            return "इमेज से मीटर रीडिंग नहीं पढ़ी जा सकी। कृपया स्पष्ट फोटो भेजें।";
        }
        if (normalized.contains("ocr failed")) {
            return "मीटर रीडिंग पढ़ने में त्रुटि हुई। कृपया स्पष्ट फोटो भेजें।";
        }
        if (normalized.contains("location saved successfully")) {
            return "लोकेशन सफलतापूर्वक सेव हो गई।";
        }
        if (normalized.contains("reading updated successfully")) {
            return "रीडिंग सफलतापूर्वक अपडेट हुई।";
        }
        return message;
    }
}
