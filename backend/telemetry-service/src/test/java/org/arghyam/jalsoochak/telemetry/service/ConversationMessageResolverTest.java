package org.arghyam.jalsoochak.telemetry.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConversationMessageResolver")
class ConversationMessageResolverTest {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");
    /** An apostrophe MessageFormat would treat as the start of a quoted, and so dropped, section. */
    private static final Pattern LONE_APOSTROPHE = Pattern.compile("(?<!')'(?!')");

    private final ConversationMessageResolver resolver = ConversationMessageResolverFixtures.real();

    @Nested
    @DisplayName("bundles")
    class Bundles {

        @ParameterizedTest(name = "{0}")
        @EnumSource(ConversationMessageCode.class)
        void giveEveryMessageItsEnglishCopy(ConversationMessageCode code) throws IOException {
            assertThat(bundle("en").getProperty(code.key())).isNotBlank();
        }

        @Test
        void holdNoEnglishKeyWithoutAMessage() throws IOException {
            Set<String> codes = Arrays.stream(ConversationMessageCode.values())
                    .map(ConversationMessageCode::key)
                    .collect(Collectors.toSet());

            assertThat(bundle("en").stringPropertyNames()).isSubsetOf(codes);
        }

        @ParameterizedTest(name = "conversation-messages_{0}")
        @MethodSource("org.arghyam.jalsoochak.telemetry.service.ConversationMessageResolverTest#translationLocales")
        void translateOnlyKeysEnglishHasWithTheSamePlaceholders(String locale) throws IOException {
            Properties english = bundle("en");
            Properties translation = bundle(locale);

            assertThat(english.stringPropertyNames()).containsAll(translation.stringPropertyNames());
            for (String key : translation.stringPropertyNames()) {
                assertThat(placeholders(translation.getProperty(key)))
                        .as(key)
                        .isEqualTo(placeholders(english.getProperty(key)));
            }
        }

        @ParameterizedTest(name = "conversation-messages_{0}")
        @MethodSource("org.arghyam.jalsoochak.telemetry.service.ConversationMessageResolverTest#allLocales")
        void escapeEveryApostropheForMessageFormat(String locale) throws IOException {
            Properties bundle = bundle(locale);
            for (String key : bundle.stringPropertyNames()) {
                assertThat(LONE_APOSTROPHE.matcher(bundle.getProperty(key)).find()).as(key).isFalse();
            }
        }
    }

    @Nested
    @DisplayName("get")
    class Get {

        @Test
        void rendersTheEnglishCopy() {
            assertThat(resolver.get(ConversationMessageCode.ISSUE_REPORTED, "english"))
                    .isEqualTo("Issue reported. Thank you.");
        }

        @Test
        void rendersTheCopyInTheOperatorsLanguage() {
            assertThat(resolver.get(ConversationMessageCode.ISSUE_REPORTED, "hindi"))
                    .isEqualTo("समस्या रिपोर्ट हो गई है। धन्यवाद।");
        }

        @Test
        void fallsBackToEnglishForAMessageTheLanguageDoesNotTranslate() {
            assertThat(resolver.get(ConversationMessageCode.READING_REQUIRED, "hindi"))
                    .isEqualTo("Reading is required.");
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"punjabi", "klingon", "sign_language"})
        void fallsBackToEnglishForALanguageWithNoBundle(String languageKey) {
            assertThat(resolver.get(ConversationMessageCode.ISSUE_REPORTED, languageKey))
                    .isEqualTo("Issue reported. Thank you.");
        }

        @ParameterizedTest
        @NullAndEmptySource
        void treatsAMissingLanguageAsEnglish(String languageKey) {
            assertThat(resolver.get(ConversationMessageCode.ISSUE_REPORTED, languageKey))
                    .isEqualTo("Issue reported. Thank you.");
        }

        @Test
        void insertsArgumentsAsTextWithoutNumberFormatting() {
            assertThat(resolver.get(ConversationMessageCode.READING_ABOVE_SCHEME_MAXIMUM, "english", 1234567))
                    .isEqualTo("Reading rejected because it is above the allowed maximum for this scheme. "
                            + "Submitted: 1234567.");
        }

        @Test
        void unescapesApostrophes() {
            assertThat(resolver.get(ConversationMessageCode.READING_ABOVE_MAXIMUM, "english"))
                    .isEqualTo("Reading can't be more than the allowed limit.");
            assertThat(resolver.get(ConversationMessageCode.READING_ABOVE_MAXIMUM_VALUE, "english", "12.5"))
                    .isEqualTo("Reading can't be more than 12.5.");
        }

        @Test
        void rendersAMessageArgumentInTheSameLanguage() {
            assertThat(resolver.get(ConversationMessageCode.READING_ABOVE_MAXIMUM_VALUE_UNIT, "hindi",
                    "720", ConversationMessageCode.UNIT_MINUTES))
                    .isEqualTo("रीडिंग 720 मिनट से अधिक नहीं हो सकती।");
        }

        /** The Hindi copy that moved out of ConversationLocalizationService's text matching, still pinned. */
        @ParameterizedTest(name = "{0}")
        @MethodSource("org.arghyam.jalsoochak.telemetry.service.ConversationMessageResolverTest#movedHindiCopy")
        void keepsTheHindiCopyItHadBeforeMovingToTheBundles(ConversationMessageCode code,
                String english, String hindi) {
            assertThat(resolver.get(code, "english")).isEqualTo(english);
            assertThat(resolver.get(code, "hindi")).isEqualTo(hindi);
        }
    }

    @Nested
    @DisplayName("translation")
    class Translation {

        @Test
        void isTheCopyInTheOperatorsLanguage() {
            assertThat(resolver.translation(ConversationMessageCode.READING_CAPTURED_WITH_VALUE, "hindi", "1234"))
                    .contains("रीडिंग सफलतापूर्वक दर्ज हुई। प्राप्त रीडिंग: 1234");
        }

        @Test
        void isEmptyRatherThanEnglishWhenTheLanguageDoesNotTranslateIt() {
            assertThat(resolver.translation(ConversationMessageCode.READING_REQUIRED, "hindi")).isEmpty();
            assertThat(resolver.translation(ConversationMessageCode.ISSUE_REPORTED, "punjabi")).isEmpty();
        }
    }

    @Nested
    @DisplayName("isEnglish")
    class IsEnglish {

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"english", "English", "en", ""})
        void recognisesEnglish(String languageKey) {
            assertThat(resolver.isEnglish(languageKey)).isTrue();
        }

        @ParameterizedTest(name = "\"{0}\"")
        @ValueSource(strings = {"hindi", "punjabi", "assamese", "bodo"})
        void rejectsEveryOtherLanguage(String languageKey) {
            assertThat(resolver.isEnglish(languageKey)).isFalse();
        }
    }

    @Nested
    @DisplayName("missing translation log")
    class MissingTranslationLog {

        @Test
        void warnsOncePerLanguageAndMessageThenLogsAtDebug() {
            ConversationMessageResolver fresh = ConversationMessageResolverFixtures.real();

            List<ILoggingEvent> events = captureLogs(() -> {
                fresh.get(ConversationMessageCode.READING_REQUIRED, "hindi");
                fresh.get(ConversationMessageCode.READING_REQUIRED, "hindi");
                fresh.get(ConversationMessageCode.READING_REQUIRED, "punjabi");
                fresh.get(ConversationMessageCode.SCHEME_NOT_FOUND, "hindi");
            });

            assertThat(events)
                    .filteredOn(event -> event.getLevel() == Level.WARN)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly(
                            "chatbot_translation_missing language=hi code=error.readingRequired fallback=en",
                            "chatbot_translation_missing language=pa code=error.readingRequired fallback=en",
                            "chatbot_translation_missing language=hi code=error.schemeNotFound fallback=en");
            assertThat(events)
                    .filteredOn(event -> event.getLevel() == Level.DEBUG)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .containsExactly("chatbot_translation_missing language=hi code=error.readingRequired");
        }

        @Test
        void staysQuietForATranslatedMessage() {
            List<ILoggingEvent> events = captureLogs(() -> ConversationMessageResolverFixtures.real()
                    .get(ConversationMessageCode.ISSUE_REPORTED, "hindi"));

            assertThat(events).isEmpty();
        }
    }

    static Stream<Arguments> movedHindiCopy() {
        return Stream.of(
                Arguments.of(ConversationMessageCode.READING_REJECTED,
                        "Please enter the correct meter reading.", "कृपया सही मीटर रीडिंग दर्ज करें।"),
                Arguments.of(ConversationMessageCode.NOTHING_TO_REJECT,
                        "No reading from today was found to correct. Please send a new meter image.",
                        "आज की कोई रीडिंग सुधारने के लिए नहीं मिली। कृपया मीटर की नई इमेज भेजें।"),
                Arguments.of(ConversationMessageCode.REJECTION_FAILED,
                        "The reading could not be corrected. Please try again.",
                        "रीडिंग सुधारी नहीं जा सकी। कृपया फिर से प्रयास करें।"),
                Arguments.of(ConversationMessageCode.METER_CHANGE_SELECTED,
                        "Successfully selected.", "सफलतापूर्वक चयनित।"),
                Arguments.of(ConversationMessageCode.IMAGE_NOT_PROCESSED,
                        "Image could not be processed.",
                        "इमेज प्रोसेस नहीं हो सकी। कृपया दोबारा प्रयास करें।"),
                Arguments.of(ConversationMessageCode.MANUAL_READING_NOT_SAVED,
                        "Manual reading could not be saved.",
                        "मैनुअल रीडिंग सेव नहीं हो सकी। कृपया दोबारा प्रयास करें।"),
                Arguments.of(ConversationMessageCode.ISSUE_PROMPT,
                        "Please select your issue by typing any of the number",
                        "कृपया नंबर टाइप करके अपनी समस्या चुनें"),
                Arguments.of(ConversationMessageCode.METER_CHANGE_PROMPT,
                        "Please select the no submission reasons by typing any of the number",
                        "कृपया नंबर टाइप करके सबमिशन न होने के कारण चुनें"),
                Arguments.of(ConversationMessageCode.LOCATION_SAVED,
                        "Location saved successfully.", "लोकेशन सफलतापूर्वक सेव हो गई।")
        );
    }

    static Stream<String> allLocales() throws IOException {
        return Arrays.stream(new PathMatchingResourcePatternResolver()
                        .getResources("classpath*:i18n/conversation-messages_*.properties"))
                .map(Resource::getFilename)
                .map(name -> name.substring("conversation-messages_".length(), name.length() - ".properties".length()));
    }

    static Stream<String> translationLocales() throws IOException {
        return allLocales().filter(locale -> !"en".equals(locale));
    }

    private static Properties bundle(String locale) throws IOException {
        Properties properties = new Properties();
        Resource resource = new PathMatchingResourcePatternResolver()
                .getResource("classpath:i18n/conversation-messages_" + locale + ".properties");
        try (Reader reader = new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static Set<String> placeholders(String value) {
        Matcher matcher = PLACEHOLDER.matcher(value);
        return matcher.results().map(result -> result.group()).collect(Collectors.toSet());
    }

    /** Runs {@code action} with ConversationMessageResolver's logger at DEBUG and returns what it logged. */
    private static List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(ConversationMessageResolver.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
        }
        return appender.list;
    }
}
