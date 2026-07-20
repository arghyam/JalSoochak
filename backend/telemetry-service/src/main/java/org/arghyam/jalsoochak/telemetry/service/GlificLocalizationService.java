package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
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

    public String resolveUserFacingErrorMessage(Exception e, String fallback, String languageKey) {
        if (e == null) {
            return localizeMessage(fallback, languageKey);
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return localizeMessage(fallback, languageKey);
        }

        String normalized = message.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("duplicate image")) {
            return localizeMessage("Duplicate image submission detected. Please submit a new image.", languageKey);
        }
        if (normalized.contains("less than previous")) {
            return localizeMessage(message.trim(), languageKey);
        }
        if (normalized.contains("manualreading is required")) {
            return localizeMessage("manualReading is required.", languageKey);
        }
        if (normalized.contains("manualreading must be numeric")) {
            return localizeMessage("Manual Reading must be numeric value", languageKey);
        }
        if (normalized.contains("manualreading must be greater than zero")) {
            return localizeMessage("manualReading must be greater than zero.", languageKey);
        }
        if (normalized.contains("language selection is required")) {
            return localizeMessage("Language selection is required. Please choose one of the listed options.", languageKey);
        }
        if (normalized.contains("invalid language selection")) {
            return localizeMessage("Invalid language selection. Please restart the process", languageKey);
        }
        if (normalized.contains("no language options configured")) {
            return localizeMessage("Language options are not configured for this tenant.", languageKey);
        }
        if (normalized.contains("channel selection is required")) {
            return localizeMessage("Channel selection is required. Please choose one of the listed options.", languageKey);
        }
        if (normalized.contains("invalid channel selection")) {
            return localizeMessage("Invalid channel selection. Please restart the process", languageKey);
        }
        if (normalized.contains("no channel options configured")) {
            return localizeMessage("Channel options are not configured for this tenant.", languageKey);
        }
        if (normalized.contains("selected channel is no longer available")) {
            return localizeMessage("Selected channel is no longer available. Please make sure you have a channel selected.", languageKey);
        }
        if (normalized.contains("item selection is required")) {
            return localizeMessage("Item selection is required. Please choose one of the listed options.", languageKey);
        }
        if (normalized.contains("invalid item selection")) {
            return localizeMessage("Invalid item selection. Please choose a valid option from the list.", languageKey);
        }
        if (normalized.contains("no item options configured")) {
            return localizeMessage("Item options are not configured for this tenant.", languageKey);
        }
        if (normalized.contains("operator is not mapped to any scheme")) {
            return localizeMessage("No scheme is mapped to this operator.", languageKey);
        }
        if (normalized.contains("operator could not be resolved")) {
            return localizeMessage("Operator could not be resolved for this contact.", languageKey);
        }
        if (normalized.contains("invalid media")) {
            return localizeMessage("Invalid media. Please submit a clear meter image.", languageKey);
        }
        if (normalized.contains("failed to download image")) {
            return localizeMessage("Image could not be processed. Please try again", languageKey);
        }
        if (normalized.contains("issuereason contains invalid characters")) {
            return localizeMessage("Issue reason can only contain letters, numbers, and spaces.", languageKey);
        }
        return localizeMessage(message.trim(), languageKey);
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
