package org.arghyam.jalsoochak.telemetry.repository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Data-driven language catalog backing {@code common_schema.language_master} /
 * {@code language_alias} (see migration V36). Resolves a language name/alias to its numeric
 * language id and to its locale code.
 *
 * <p>This is the single source of truth that replaces the hardcoded Java maps in
 * {@code GlificContactSyncService} and {@code WelcomeMessageService}; those services keep their
 * maps only as a fallback and consult this repository first. Adding a new language then becomes an
 * INSERT rather than a code change.
 *
 * <p>Every lookup is defensive: if the tables are absent (e.g. Flyway disabled in a test) or any
 * query fails, the methods return {@link Optional#empty()} so callers transparently fall back to
 * their hardcoded catalog. The catalog is small and effectively static, so it is loaded once and
 * cached in memory with a TTL.
 */
@Repository
public class LanguageCatalogRepository {

    private static final Logger log = LoggerFactory.getLogger(LanguageCatalogRepository.class);
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private final JdbcTemplate jdbcTemplate;

    @Value("${telemetry.cache.language-catalog.enabled:true}")
    private boolean cacheEnabled = true;
    @Value("${telemetry.cache.language-catalog.ttl-ms:600000}")
    private long cacheTtlMs = 600_000L;

    private final AtomicReference<TimedCacheValue> cache = new AtomicReference<>();

    public LanguageCatalogRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Resolves a language name/alias (any casing/separators) to its numeric language id. */
    public Optional<Integer> findLanguageIdByAlias(String alias) {
        LanguageEntry entry = lookup(alias);
        return entry == null ? Optional.empty() : Optional.of(entry.languageId());
    }

    /** Resolves a language name/alias (any casing/separators) to its locale code (e.g. "hi"). */
    public Optional<String> findLocaleCodeByAlias(String alias) {
        LanguageEntry entry = lookup(alias);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.localeCode());
    }

    /** Clears the in-memory catalog cache; the next lookup reloads from the database. */
    public void invalidateCache() {
        cache.set(null);
    }

    private LanguageEntry lookup(String alias) {
        String normalized = normalize(alias);
        if (normalized.isEmpty()) {
            return null;
        }
        return catalog().get(normalized);
    }

    private Map<String, LanguageEntry> catalog() {
        if (!cacheEnabled || cacheTtlMs <= 0L) {
            return queryCatalog();
        }
        long now = System.currentTimeMillis();
        TimedCacheValue cached = cache.get();
        if (cached != null && !cached.isExpired(now)) {
            return cached.value();
        }
        Map<String, LanguageEntry> refreshed = queryCatalog();
        cache.set(new TimedCacheValue(refreshed, now + cacheTtlMs));
        return refreshed;
    }

    private Map<String, LanguageEntry> queryCatalog() {
        String sql = """
                SELECT a.alias AS alias, a.language_id AS language_id, m.locale_code AS locale_code
                FROM common_schema.language_alias a
                JOIN common_schema.language_master m ON m.language_id = a.language_id
                """;
        Map<String, LanguageEntry> out = new ConcurrentHashMap<>();
        try {
            jdbcTemplate.query(sql, rs -> {
                String alias = normalize(rs.getString("alias"));
                if (!alias.isEmpty()) {
                    out.put(alias, new LanguageEntry(rs.getInt("language_id"), rs.getString("locale_code")));
                }
            });
        } catch (DataAccessException e) {
            // Table missing (Flyway disabled) or query failed: callers fall back to their hardcoded map.
            log.debug("Language catalog unavailable, callers will use hardcoded fallback: {}", e.getMessage());
            return Map.of();
        }
        return out;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return NON_ALNUM.matcher(lower).replaceAll(" ").trim();
    }

    private record LanguageEntry(int languageId, String localeCode) {
    }

    private record TimedCacheValue(Map<String, LanguageEntry> value, long expiresAtMs) {
        private boolean isExpired(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }
}
