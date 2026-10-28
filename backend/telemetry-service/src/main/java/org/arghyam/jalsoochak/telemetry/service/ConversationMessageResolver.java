package org.arghyam.jalsoochak.telemetry.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves a {@link ConversationMessageCode} to its copy in an operator's language, from the
 * {@code i18n/conversation-messages_<locale>.properties} bundles.
 *
 * <p>English lives in {@code conversation-messages_en} like any other language, and there is no base
 * bundle, so a code that a language has not translated resolves to nothing rather than silently to
 * English. That gap is logged at WARN the first time each language misses each code, and at DEBUG
 * after that.
 *
 * <p>The bundles are owned here rather than exposed as a {@code MessageSource} bean: a second bean
 * of that type beside the context's own {@code messageSource} would make any by-type injection of
 * {@code MessageSource} ambiguous.
 */
@Slf4j
@Component
public class ConversationMessageResolver {

    private static final String BASENAME = "classpath:i18n/conversation-messages";
    private static final String ENGLISH = "en";

    private final LanguageLocaleResolver languageLocaleResolver;
    private final MessageSource bundles;
    private final Set<String> reportedGaps = ConcurrentHashMap.newKeySet();

    public ConversationMessageResolver(LanguageLocaleResolver languageLocaleResolver) {
        this.languageLocaleResolver = languageLocaleResolver;
        this.bundles = loadBundles();
    }

    /**
     * The copy for {@code code} in the language of {@code languageKey}, or in English when that
     * language does not translate it. A {@link ConversationMessageCode} argument is rendered in the
     * same language; any other argument is inserted as {@link String#valueOf} text, never
     * number-formatted.
     */
    public String get(ConversationMessageCode code, String languageKey, Object... args) {
        return textOrEnglish(code, languageLocaleResolver.localeCodeFor(languageKey), args);
    }

    /**
     * The copy for {@code code} in the language of {@code languageKey} only: empty, after the gap is
     * logged, when that language does not translate it.
     */
    public Optional<String> translation(ConversationMessageCode code, String languageKey, Object... args) {
        String localeCode = languageLocaleResolver.localeCodeFor(languageKey);
        String text = render(code, localeCode, args);
        if (text == null) {
            reportGap(code, localeCode);
        }
        return Optional.ofNullable(text);
    }

    /** Whether {@code languageKey} is English, whose copy needs no translation. */
    public boolean isEnglish(String languageKey) {
        return ENGLISH.equals(languageLocaleResolver.localeCodeFor(languageKey));
    }

    private String textOrEnglish(ConversationMessageCode code, String localeCode, Object... args) {
        String text = render(code, localeCode, args);
        if (text != null) {
            return text;
        }
        reportGap(code, localeCode);
        return render(code, ENGLISH, args);
    }

    /** {@code null} when the bundle for {@code localeCode} has no entry for {@code code}. */
    private String render(ConversationMessageCode code, String localeCode, Object[] args) {
        Object[] renderedArgs = Arrays.stream(args)
                .map(arg -> arg instanceof ConversationMessageCode nested
                        ? textOrEnglish(nested, localeCode)
                        : String.valueOf(arg))
                .toArray();
        return bundles.getMessage(code.key(), renderedArgs, null, Locale.forLanguageTag(localeCode));
    }

    private void reportGap(ConversationMessageCode code, String localeCode) {
        if (reportedGaps.add(localeCode + ":" + code.key())) {
            log.warn("chatbot_translation_missing language={} code={} fallback=en", localeCode, code.key());
        } else {
            log.debug("chatbot_translation_missing language={} code={}", localeCode, code.key());
        }
    }

    private static MessageSource loadBundles() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasename(BASENAME);
        source.setDefaultEncoding(StandardCharsets.UTF_8.name());
        source.setFallbackToSystemLocale(false);
        // Every value is a MessageFormat pattern, so the '' escape means the same in every entry.
        source.setAlwaysUseMessageFormat(true);
        return source;
    }
}
