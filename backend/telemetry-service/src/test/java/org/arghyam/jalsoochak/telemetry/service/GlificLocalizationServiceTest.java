package org.arghyam.jalsoochak.telemetry.service;

import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperator;
import org.arghyam.jalsoochak.telemetry.repository.TelemetryOperatorWithSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Language-key normalisation and the English→Hindi message table used to localise every operator
 * reply. The tables are matched on substrings of the English source text, so these tests pin both
 * the mapping and the "leave anything unrecognised untouched" contract.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("GlificLocalizationService")
class GlificLocalizationServiceTest {

    private static final String CONTACT = "919999900001";
    private static final String HINDI = "hindi";

    @Mock
    private GlificOperatorContextService operatorContextService;

    @InjectMocks
    private GlificLocalizationService service;

    @Nested
    @DisplayName("normalizeLanguageKey")
    class NormalizeLanguageKey {

        @Test
        void mapsMissingLanguageToTheEmptyKey() {
            assertThat(service.normalizeLanguageKey(null)).isEmpty();
        }

        @ParameterizedTest(name = "\"{0}\" -> hindi")
        @ValueSource(strings = {"हिंदी", "हिन्दी", "hindi", "Hindi", "HINDI", "  hindi  "})
        void recognisesHindiInDevanagariAndLatinScript(String language) {
            assertThat(service.normalizeLanguageKey(language)).isEqualTo("hindi");
        }

        @ParameterizedTest(name = "\"{0}\" -> english")
        @ValueSource(strings = {"english", "English", "ENGLISH", "inglish", "  English "})
        void recognisesEnglishIncludingTheCommonMisspelling(String language) {
            assertThat(service.normalizeLanguageKey(language)).isEqualTo("english");
        }

        @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
        @CsvSource({
                "Assamese,assamese",
                "'Bodo (बड़ो)',bodo",
                "'Some Language',some_language",
                "'  Mixed--Case  ',mixed_case",
                "'!!!',''"
        })
        void slugifiesAnyOtherLanguageName(String language, String expected) {
            assertThat(service.normalizeLanguageKey(language)).isEqualTo(expected);
        }
    }

    @Nested
    @DisplayName("resolveLanguageKeyForContact")
    class ResolveForContact {

        private TelemetryOperatorWithSchema operator(Integer tenantId) {
            return new TelemetryOperatorWithSchema("tenant_as",
                    new TelemetryOperator(11L, tenantId, "Asha", "a@b.c", CONTACT, 1));
        }

        @Test
        void defaultsToEnglishForAMissingContactId() {
            assertThat(service.resolveLanguageKeyForContact(null)).isEqualTo("english");
            assertThat(service.resolveLanguageKeyForContact("  ")).isEqualTo("english");
        }

        @Test
        void normalisesTheOperatorsConfiguredLanguage() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(3));
            when(operatorContextService.resolveOperatorLanguage(any(), anyInt())).thenReturn("हिंदी");

            assertThat(service.resolveLanguageKeyForContact(CONTACT)).isEqualTo("hindi");
        }

        @Test
        void defaultsToEnglishWhenTheOperatorHasNoTenant() {
            when(operatorContextService.resolveOperatorWithSchema(CONTACT)).thenReturn(operator(null));

            assertThat(service.resolveLanguageKeyForContact(CONTACT)).isEqualTo("english");
        }

        @Test
        void defaultsToEnglishWhenOperatorResolutionFails() {
            when(operatorContextService.resolveOperatorWithSchema(anyString()))
                    .thenThrow(new IllegalStateException("No operator found"));

            assertThat(service.resolveLanguageKeyForContact(CONTACT)).isEqualTo("english");
        }
    }

    @Nested
    @DisplayName("resolveUserFacingErrorMessage")
    class UserFacingErrors {

        @Test
        void usesTheFallbackForAMissingException() {
            assertThat(service.resolveUserFacingErrorMessage(null, "Fallback.", "english"))
                    .isEqualTo("Fallback.");
        }

        @Test
        void usesTheFallbackWhenTheExceptionHasNoMessage() {
            assertThat(service.resolveUserFacingErrorMessage(new IllegalStateException(), "Fallback.", "english"))
                    .isEqualTo("Fallback.");
            assertThat(service.resolveUserFacingErrorMessage(new IllegalStateException("  "), "Fallback.", "english"))
                    .isEqualTo("Fallback.");
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @MethodSource("technicalToUserFacing")
        void rewritesTechnicalMessagesIntoOperatorFacingCopy(String technical, String expected) {
            assertThat(service.resolveUserFacingErrorMessage(
                    new IllegalStateException(technical), "Fallback.", "english"))
                    .isEqualTo(expected);
        }

        static Stream<Arguments> technicalToUserFacing() {
            return Stream.of(
                    Arguments.of("Duplicate image detected for hash abc123",
                            "Duplicate image submission detected. Please submit a new image."),
                    Arguments.of("manualReading is required",
                            "manualReading is required."),
                    Arguments.of("manualReading must be numeric",
                            "Manual Reading must be numeric value"),
                    Arguments.of("manualReading must be greater than zero",
                            "manualReading must be greater than zero."),
                    Arguments.of("Language selection is required",
                            "Language selection is required. Please choose one of the listed options."),
                    Arguments.of("Invalid language selection: 9",
                            "Invalid language selection. Please restart the process"),
                    Arguments.of("No language options configured for tenant 3",
                            "Language options are not configured for this tenant."),
                    Arguments.of("Channel selection is required",
                            "Channel selection is required. Please choose one of the listed options."),
                    Arguments.of("Invalid channel selection: 9",
                            "Invalid channel selection. Please restart the process"),
                    Arguments.of("No channel options configured",
                            "Channel options are not configured for this tenant."),
                    Arguments.of("Selected channel is no longer available",
                            "Selected channel is no longer available. Please make sure you have a channel selected."),
                    Arguments.of("Item selection is required",
                            "Item selection is required. Please choose one of the listed options."),
                    Arguments.of("Invalid item selection: 9",
                            "Invalid item selection. Please choose a valid option from the list."),
                    Arguments.of("No item options configured",
                            "Item options are not configured for this tenant."),
                    Arguments.of("Operator is not mapped to any scheme",
                            "No scheme is mapped to this operator."),
                    Arguments.of("Operator could not be resolved for contact",
                            "Operator could not be resolved for this contact."),
                    Arguments.of("Invalid media id supplied",
                            "Invalid media. Please submit a clear meter image."),
                    Arguments.of("Failed to download image from Glific",
                            "Image could not be processed. Please try again"),
                    Arguments.of("issueReason contains invalid characters",
                            "Issue reason can only contain letters, numbers, and spaces."),
                    Arguments.of("issueReason is required",
                            "Issue reason is required."),
                    Arguments.of("contactId is required",
                            "Contact could not be identified. Please restart the process."),
                    Arguments.of("Invalid scheme selection: 9",
                            "Invalid scheme selection. Please choose a valid option from the list."),
                    Arguments.of("scheme selection is required",
                            "Scheme selection is required. Please choose one of the listed options."),
                    Arguments.of("Invalid meter change reason selection",
                            "Invalid meter change reason. Please choose a valid option from the list."),
                    Arguments.of("METER_CHANGE_REASONS config is not valid JSON",
                            "Meter change reasons are not configured for this tenant."),
                    Arguments.of("SUPPLY_OUTAGE_REASONS config has no reasons",
                            "Submission reasons are not configured for this tenant."),
                    Arguments.of("latitude must be between -90 and 90",
                            "Location could not be saved. Please share a valid location."),
                    Arguments.of("geolocation.type must be Point",
                            "Location could not be saved. Please share a valid location."),
                    Arguments.of("Operator tenant could not be resolved",
                            "Operator could not be resolved for this contact."),
                    Arguments.of("Scheme not found for the provided state or centre scheme id",
                            "Scheme not found for the provided state or centre scheme id"),
                    Arguments.of("State scheme not found",
                            "Scheme not found.")
            );
        }

        @Test
        void keepsTheReadingComparisonDetailIntactSoTheOperatorSeesBothValues() {
            String message = "Reading cannot be less than previous. Submitted reading: 100. Previous reading: 200.";

            assertThat(service.resolveUserFacingErrorMessage(
                    new IllegalStateException(message), "Fallback.", "english"))
                    .isEqualTo(message);
        }

        @Test
        void replacesAnUnrecognisedMessageWithTheCallersFallback() {
            // ERROR-DISCLOSURE: deny by default. Anything the allowlist does not recognise is replaced,
            // never echoed — the caller's fallback is already context-specific ("Manual reading could
            // not be saved.", "Image could not be processed.", …).
            assertThat(service.resolveUserFacingErrorMessage(
                    new IllegalStateException("  Something unusual happened  "), "Fallback.", "english"))
                    .isEqualTo("Fallback.");
        }

        @ParameterizedTest(name = "withholds: {0}")
        @MethodSource("internalDetailThatMustNotReachTheUser")
        void neverDisclosesInternalDetail(String internal) {
            String result = service.resolveUserFacingErrorMessage(
                    new IllegalStateException(internal), "Fallback.", "english");

            assertThat(result).isEqualTo("Fallback.");
        }

        static Stream<Arguments> internalDetailThatMustNotReachTheUser() {
            return Stream.of(
                    // Database / driver failures — every caller catches Exception, so these reached the
                    // user verbatim, SQL and schema names included.
                    Arguments.of("PreparedStatementCallback; bad SQL grammar [SELECT * FROM "
                            + "tenant_as.flow_reading_table WHERE id = ?]; nested exception is "
                            + "org.postgresql.util.PSQLException: ERROR: relation does not exist"),
                    Arguments.of("Could not open JDBC Connection for transaction"),
                    // Schema / column internals
                    Arguments.of("Missing required column reading_date"),
                    Arguments.of("Tenant not found for schema tenant_as"),
                    Arguments.of("Failed to map operator record"),
                    // Crypto and key configuration
                    Arguments.of("PII_ENCRYPTION_KEY must decode to exactly 32 bytes (256 bits)"),
                    Arguments.of("PII_HMAC_KEY must decode to exactly 32 bytes (256 bits)"),
                    Arguments.of("AES-GCM decryption failed"),
                    Arguments.of("HMAC-SHA256 failed"),
                    Arguments.of("SHA-256 not available"),
                    // Serialization internals
                    Arguments.of("Failed to serialize Glific resume result payload"),
                    // Deployment configuration
                    Arguments.of("API key service not configured")
            );
        }

        @Test
        void doesNotEchoThePhoneNumberBackWhenTheContactIsUnknown() {
            // "No operator found for contactId " + contactId returned the submitted phone number to the
            // caller — a PII echo that also confirmed which numbers are registered.
            String result = service.resolveUserFacingErrorMessage(
                    new IllegalStateException("No operator found for contactId 919999900001"),
                    "Fallback.", "english");

            assertThat(result)
                    .isEqualTo("Operator could not be resolved for this contact.")
                    .doesNotContain("919999900001");
        }

        @Test
        void stripsTheHttpStatusPrefixOfAResponseStatusException() {
            // ResponseStatusException.getMessage() is '400 BAD_REQUEST "reason"'; the status prefix used
            // to be shown to the operator alongside the reason.
            String result = service.resolveUserFacingErrorMessage(
                    new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.BAD_REQUEST,
                            "Operator does not belong to the specified scheme"),
                    "Fallback.", "english");

            assertThat(result)
                    .isEqualTo("Operator is not mapped to the selected scheme.")
                    .doesNotContain("400");
        }

        @Test
        void keepsManualReadingRulesAheadOfTheGenericReadingRules() {
            // "manualreading is required" contains "reading is required": order in the allowlist decides
            // which reply the operator gets.
            assertThat(service.resolveUserFacingErrorMessage(
                    new IllegalStateException("manualReading is required"), "Fallback.", "english"))
                    .isEqualTo("manualReading is required.");
            assertThat(service.resolveUserFacingErrorMessage(
                    new IllegalStateException("reading is required"), "Fallback.", "english"))
                    .isEqualTo("Reading is required.");
        }

        @Test
        void stillLocalisesTheFallbackForAHindiOperator() {
            assertThat(service.resolveUserFacingErrorMessage(
                    new IllegalStateException("bad SQL grammar [SELECT 1]"),
                    "Manual reading could not be saved.", HINDI))
                    .isEqualTo("मैनुअल रीडिंग सेव नहीं हो सकी। कृपया दोबारा प्रयास करें।");
        }

        @Test
        void localisesTheRewrittenMessageWhenTheOperatorReadsHindi() {
            assertThat(service.resolveUserFacingErrorMessage(
                    new IllegalStateException("Duplicate image detected"), "Fallback.", HINDI))
                    .isEqualTo("डुप्लिकेट इमेज मिली है। कृपया नई इमेज सबमिट करें।");
        }
    }

    @Nested
    @DisplayName("localizeMessage")
    class LocalizeMessage {

        @Test
        void returnsAMissingMessageUnchanged() {
            assertThat(service.localizeMessage(null, HINDI)).isNull();
            assertThat(service.localizeMessage("   ", HINDI)).isEqualTo("   ");
        }

        @ParameterizedTest(name = "languageKey={0} leaves English copy untouched")
        @ValueSource(strings = {"english", "assamese", "bodo", ""})
        void leavesCopyUntouchedForEveryNonHindiLanguage(String languageKey) {
            assertThat(service.localizeMessage("Meter replaced", languageKey)).isEqualTo("Meter replaced");
        }

        @Test
        void leavesCopyUntouchedForANullLanguageKey() {
            assertThat(service.localizeMessage("Meter replaced", null)).isEqualTo("Meter replaced");
        }

        @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
        @MethodSource("englishToHindi")
        void translatesKnownCopyIntoHindi(String english, String hindi) {
            assertThat(service.localizeMessage(english, HINDI)).isEqualTo(hindi);
        }

        static Stream<Arguments> englishToHindi() {
            return Stream.of(
                    Arguments.of("Duplicate image submission detected. Please submit a new image.",
                            "डुप्लिकेट इमेज मिली है। कृपया नई इमेज सबमिट करें।"),
                    Arguments.of("manualReading is required.", "manualReading अनिवार्य है।"),
                    Arguments.of("Manual Reading must be numeric value", "manualReading केवल संख्या होना चाहिए।"),
                    Arguments.of("manualReading must be greater than zero.", "manualReading शून्य से बड़ा होना चाहिए।"),
                    Arguments.of("Language selection is required. Please choose one of the listed options.",
                            "भाषा चयन आवश्यक है। कृपया सूची में से एक विकल्प चुनें।"),
                    Arguments.of("Invalid language selection. Please restart the process",
                            "अमान्य भाषा चयन। कृपया सूची से सही संख्या या भाषा चुनें।"),
                    Arguments.of("Language options are not configured for this tenant.",
                            "इस टेनेंट के लिए भाषा विकल्प कॉन्फ़िगर नहीं हैं।"),
                    Arguments.of("Channel selection is required. Please choose one of the listed options.",
                            "चैनल चयन आवश्यक है। कृपया सूची में से एक विकल्प चुनें।"),
                    Arguments.of("Invalid channel selection. Please restart the process",
                            "अमान्य चैनल चयन। कृपया सूची से सही संख्या या चैनल चुनें।"),
                    Arguments.of("Channel options are not configured for this tenant.",
                            "इस टेनेंट के लिए चैनल विकल्प कॉन्फ़िगर नहीं हैं।"),
                    Arguments.of("Selected channel is no longer available.",
                            "चयनित चैनल अब उपलब्ध नहीं है। कृपया सुनिश्चित करें कि आपने एक चैनल चुना है।"),
                    Arguments.of("Item selection is required. Please choose one of the listed options.",
                            "विकल्प चयन आवश्यक है। कृपया सूची में से एक विकल्प चुनें।"),
                    Arguments.of("Invalid item selection. Please choose a valid option from the list.",
                            "अमान्य विकल्प चयन। कृपया सूची से सही विकल्प चुनें।"),
                    Arguments.of("Item options are not configured for this tenant.",
                            "इस टेनेंट के लिए विकल्प कॉन्फ़िगर नहीं हैं।"),
                    Arguments.of("No scheme is mapped to this operator.",
                            "इस ऑपरेटर के लिए कोई स्कीम मैप नहीं है।"),
                    Arguments.of("Operator could not be resolved for this contact.",
                            "इस संपर्क के लिए ऑपरेटर नहीं मिला।"),
                    Arguments.of("Invalid media. Please submit a clear meter image.",
                            "मीडिया अमान्य है। कृपया स्पष्ट मीटर इमेज भेजें।"),
                    Arguments.of("Issue reason can only contain letters, numbers, and spaces.",
                            "समस्या का विवरण केवल अक्षर, अंक और स्पेस में ही दें।"),
                    Arguments.of("Successfully selected.", "सफलतापूर्वक चयनित।"),
                    Arguments.of("Image could not be processed. Please try again",
                            "इमेज प्रोसेस नहीं हो सकी। कृपया दोबारा प्रयास करें।"),
                    Arguments.of("Manual reading could not be saved.",
                            "मैनुअल रीडिंग सेव नहीं हो सकी। कृपया दोबारा प्रयास करें।"),
                    Arguments.of("Please type your issue in a few words",
                            "कृपया अपनी समस्या संक्षेप में लिखें।"),
                    Arguments.of("Please select your issue by typing any of the number",
                            "कृपया नंबर टाइप करके अपनी समस्या चुनें"),
                    Arguments.of("Please select the no submission reasons by typing any of the number",
                            "कृपया नंबर टाइप करके सबमिशन न होने के कारण चुनें"),
                    Arguments.of("Electricity supply disconnected", "बिजली आपूर्ति बंद है"),
                    Arguments.of("No electricity supply today", "आज बिजली की आपूर्ति नहीं है"),
                    Arguments.of("Pump failure", "पंप खराब है"),
                    Arguments.of("Pipeline break", "पाइपलाइन टूटी है"),
                    Arguments.of("Water quality issues", "पानी की गुणवत्ता में समस्या है"),
                    Arguments.of("Source drying", "स्रोत सूख रहा है"),
                    Arguments.of("Natural calamity", "प्राकृतिक आपदा"),
                    Arguments.of("Others", "अन्य"),
                    Arguments.of("Meter replaced", "मीटर बदला गया"),
                    Arguments.of("Meter not working", "मीटर काम नहीं कर रहा"),
                    Arguments.of("Meter damaged", "मीटर खराब है"),
                    Arguments.of("Meter damage", "मीटर खराब है"),
                    Arguments.of("Invalid reading value", "रीडिंग मान्य नहीं है।"),
                    Arguments.of("Could not read meter value from image",
                            "इमेज से मीटर रीडिंग नहीं पढ़ी जा सकी। कृपया स्पष्ट फोटो भेजें।"),
                    Arguments.of("OCR failed", "मीटर रीडिंग पढ़ने में त्रुटि हुई। कृपया स्पष्ट फोटो भेजें।"),
                    Arguments.of("Location saved successfully", "लोकेशन सफलतापूर्वक सेव हो गई।"),
                    Arguments.of("Reading updated successfully", "रीडिंग सफलतापूर्वक अपडेट हुई।")
            );
        }

        @Test
        void carriesTheSubmittedAndPreviousReadingsIntoTheHindiComparisonMessage() {
            String english = "Reading cannot be less than previous confirmed reading. "
                    + "Submitted reading: 100. Previous reading: 200.";

            assertThat(service.localizeMessage(english, HINDI))
                    .isEqualTo("रीडिंग पिछली पुष्टि की गई रीडिंग से कम नहीं हो सकती। "
                            + "जमा की गई रीडिंग: 100। पिछली रीडिंग: 200।");
        }

        @Test
        void fallsBackToTheGenericHindiComparisonMessageWhenTheReadingsAreAbsent() {
            assertThat(service.localizeMessage("Reading cannot be less than previous confirmed reading.", HINDI))
                    .isEqualTo("रीडिंग पिछली पुष्टि की गई रीडिंग से कम नहीं हो सकती।");
        }

        @Test
        void carriesTheExtractedReadingIntoTheHindiSuccessMessage() {
            assertThat(service.localizeMessage("Reading captured successfully. Extracted reading: 1234", HINDI))
                    .isEqualTo("रीडिंग सफलतापूर्वक दर्ज हुई। प्राप्त रीडिंग: 1234");
        }

        @Test
        void fallsBackToTheGenericHindiSuccessMessageWithoutAnExtractedReading() {
            assertThat(service.localizeMessage("Reading captured successfully.", HINDI))
                    .isEqualTo("रीडिंग सफलतापूर्वक दर्ज हुई।");
        }

        @Test
        void carriesTheExtractedReadingIntoTheHindiLowConfidencePrompt() {
            assertThat(service.localizeMessage("Low OCR confidence. Extracted reading: 987", HINDI))
                    .isEqualTo("OCR भरोसा कम है। प्राप्त रीडिंग: 987। कृपया रीडिंग की पुष्टि करें।");
        }

        @Test
        void fallsBackToTheGenericHindiLowConfidencePrompt() {
            assertThat(service.localizeMessage("Low OCR confidence.", HINDI))
                    .isEqualTo("OCR भरोसा कम है। कृपया रीडिंग की पुष्टि करें।");
        }

        @Test
        void translatesOnlyTheLabelOfALanguageConfirmation() {
            assertThat(service.localizeMessage("Language selected: Hindi", HINDI))
                    .isEqualTo("भाषा चुनी गई: Hindi");
        }

        @Test
        void leavesUnrecognisedHindiBoundCopyUntouched() {
            assertThat(service.localizeMessage("Some entirely new message", HINDI))
                    .isEqualTo("Some entirely new message");
        }
    }
}
