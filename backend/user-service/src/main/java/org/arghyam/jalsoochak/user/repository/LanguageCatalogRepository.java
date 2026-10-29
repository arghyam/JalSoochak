package org.arghyam.jalsoochak.user.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the language catalog ({@code common_schema.language_alias}, migration V38). Its ids are the
 * WhatsApp provider's language ids, not a language's position in a tenant's {@code language_N} list.
 */
@Repository
@RequiredArgsConstructor
public class LanguageCatalogRepository {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private final JdbcTemplate jdbcTemplate;

    /** Resolves a language name or alias, in any casing or separators, to its catalog id. */
    public Optional<Integer> findLanguageIdByAlias(String alias) {
        String normalized = normalize(alias);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        List<Integer> ids = jdbcTemplate.query(
                "SELECT language_id FROM common_schema.language_alias WHERE alias = ?",
                (rs, n) -> rs.getInt("language_id"),
                normalized
        );
        return ids.stream().findFirst();
    }

    /** The form V38 stores aliases in: lower-case, each run of non-alphanumerics one space, trimmed. */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return NON_ALNUM.matcher(value.toLowerCase(Locale.ROOT)).replaceAll(" ").trim();
    }
}
