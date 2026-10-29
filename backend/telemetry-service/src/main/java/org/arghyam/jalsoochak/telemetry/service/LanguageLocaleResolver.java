package org.arghyam.jalsoochak.telemetry.service;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.telemetry.repository.LanguageCatalogRepository;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Maps an operator's language, as a name or code ("Hindi", "punjabi", "as_IN"), to the locale code
 * that keys translated copy ("hi", "pa", "as").
 *
 * <p>The language catalog is consulted first, so a new language needs only a catalog row. The
 * built-in list covers a catalog that is unavailable or has no row for the language.
 */
@Service
@RequiredArgsConstructor
public class LanguageLocaleResolver {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private final LanguageCatalogRepository languageCatalogRepository;

    /**
     * Returns the locale code for {@code language}: "en" when it is blank, and the normalized
     * language itself when neither the catalog nor the built-in list knows it, so a lookup by it
     * misses and the caller falls back to English.
     */
    public String localeCodeFor(String language) {
        String key = normalize(language);
        if (key.isEmpty()) {
            return "en";
        }
        return languageCatalogRepository.findLocaleCodeByAlias(key)
                .filter(code -> !code.isBlank())
                .orElseGet(() -> builtInLocaleCode(key));
    }

    private static String builtInLocaleCode(String key) {
        return switch (key) {
            case "english", "en" -> "en";
            case "hindi", "hi" -> "hi";
            case "tamil", "ta" -> "ta";
            case "kannada", "kn" -> "kn";
            case "malayalam", "ml" -> "ml";
            case "telugu", "te" -> "te";
            case "odia", "oriya", "or" -> "or";
            case "assamese", "as", "as_in" -> "as";
            case "gujarati", "gu" -> "gu";
            case "bengali", "bn" -> "bn";
            case "punjabi", "pa" -> "pa";
            case "marathi", "mr" -> "mr";
            case "urdu", "ur" -> "ur";
            case "spanish", "es" -> "es";
            case "french", "fr" -> "fr";
            case "swahili", "sw" -> "sw";
            case "indonesian", "id" -> "id";
            case "kinyarwanda", "rw", "rw_rw" -> "rw";
            case "sign_language", "isl" -> "isl";
            case "gondi", "gon", "koitur" -> "gon";
            case "malay", "ms" -> "ms";
            default -> key;
        };
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.trim().toLowerCase(Locale.ROOT);
        return NON_ALNUM.matcher(lower).replaceAll("_").replaceAll("^_+|_+$", "");
    }
}
