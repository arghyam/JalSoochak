package org.arghyam.jalsoochak.telemetry.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.AbstractMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tenant configuration lookup: the whole {@code tenant_config_master_table} row set is loaded once
 * per tenant into a TTL cache, and the typed accessors read indexed ({@code language_1},
 * {@code channel_2_hindi}) and language-scoped keys out of that map.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("TenantConfigRepository")
class TenantConfigRepositoryTest {

    private static final int TENANT = 17;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private TenantConfigRepository repository;

    @BeforeEach
    void setUp() {
        repository = new TenantConfigRepository(jdbcTemplate);
        ReflectionTestUtils.setField(repository, "tenantConfigCacheEnabled", true);
        ReflectionTestUtils.setField(repository, "tenantConfigCacheTtlMs", 120_000L);
    }

    /** Stubs the whole-table config load with the given key/value pairs. */
    private void givenConfig(Map<String, String> config) {
        List<Map.Entry<String, String>> rows = config.entrySet().stream()
                .map(e -> (Map.Entry<String, String>) new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue()))
                .toList();
        lenient().when(jdbcTemplate.query(contains("SELECT config_key, config_value"),
                any(RowMapper.class), any(Object[].class))).thenReturn(rows);
    }

    private static Map<String, String> config(String... keyValuePairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            map.put(keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return map;
    }

    @Nested
    @DisplayName("tenant lookups")
    class TenantLookups {

        @Test
        void findTenantIdByStateCodeIsCaseInsensitive() {
            when(jdbcTemplate.query(contains("LOWER(state_code)"), any(RowMapper.class), any(Object[].class)))
                    .thenReturn(List.of(TENANT));

            assertThat(repository.findTenantIdByStateCode("As")).contains(TENANT);
        }

        @Test
        void findTenantIdByStateCodeIsEmptyForAnUnknownCode() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                    .thenReturn(List.of());

            assertThat(repository.findTenantIdByStateCode("ZZ")).isEmpty();
        }

        @Test
        void findTenantIdByApiKeyHashRejectsABlankHashWithoutQuerying() {
            assertThat(repository.findTenantIdByApiKeyHash(null)).isEmpty();
            assertThat(repository.findTenantIdByApiKeyHash("  ")).isEmpty();

            verify(jdbcTemplate, org.mockito.Mockito.never())
                    .query(anyString(), any(RowMapper.class), any(Object[].class));
        }

        @Test
        void findTenantIdByApiKeyHashIgnoresSoftDeletedTenants() {
            when(jdbcTemplate.query(contains("api_key_hash = ?"), any(RowMapper.class), any(Object[].class)))
                    .thenReturn(List.of(TENANT));

            assertThat(repository.findTenantIdByApiKeyHash("hash")).contains(TENANT);
            verify(jdbcTemplate).query(contains("deleted_at IS NULL"), any(RowMapper.class), any(Object[].class));
        }

        @Test
        void findTenantTitleByIdTrimsTheStoredTitle() {
            when(jdbcTemplate.query(contains("SELECT title"), any(RowMapper.class), any(Object[].class)))
                    .thenReturn(List.of("  Assam PHED  "));

            assertThat(repository.findTenantTitleById(TENANT)).contains("Assam PHED");
        }

        @Test
        void findTenantTitleByIdSkipsABlankTitle() {
            when(jdbcTemplate.query(contains("SELECT title"), any(RowMapper.class), any(Object[].class)))
                    .thenReturn(List.of("   "));

            assertThat(repository.findTenantTitleById(TENANT)).isEmpty();
        }

        @Test
        void findTenantTitleByIdIsEmptyForANullTenant() {
            assertThat(repository.findTenantTitleById(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("findConfigValue")
    class ConfigValues {

        @Test
        void readsAValueFromTheCachedConfigMap() {
            givenConfig(config("intro_message", "Hello"));

            assertThat(repository.findConfigValue(TENANT, "intro_message")).contains("Hello");
        }

        @Test
        void isEmptyForAnUnknownKey() {
            givenConfig(config("intro_message", "Hello"));

            assertThat(repository.findConfigValue(TENANT, "missing_key")).isEmpty();
        }

        @Test
        void isEmptyForAMissingTenantOrKey() {
            assertThat(repository.findConfigValue(null, "intro_message")).isEmpty();
            assertThat(repository.findConfigValue(TENANT, null)).isEmpty();
            assertThat(repository.findConfigValue(TENANT, "  ")).isEmpty();
        }

        @Test
        void readsSupportedChannelsStraightFromTheTableRatherThanTheCache() {
            // Channel support is invalidated by a Kafka event, so it must never be served stale.
            when(jdbcTemplate.query(contains("AND config_key = ?"), any(RowMapper.class), any(Object[].class)))
                    .thenReturn(List.of("BFM,ELM"));

            assertThat(repository.findConfigValue(TENANT, "TENANT_SUPPORTED_CHANNELS")).contains("BFM,ELM");
            verify(jdbcTemplate, org.mockito.Mockito.never())
                    .query(contains("SELECT config_key, config_value"), any(RowMapper.class), any(Object[].class));
        }

        @Test
        void keepsTheFirstValueWhenAKeyIsDuplicated() {
            List<Map.Entry<String, String>> rows = List.of(
                    new AbstractMap.SimpleEntry<>("intro_message", "first"),
                    new AbstractMap.SimpleEntry<>("intro_message", "second"));
            when(jdbcTemplate.query(contains("SELECT config_key, config_value"),
                    any(RowMapper.class), any(Object[].class))).thenReturn(rows);

            assertThat(repository.findConfigValue(TENANT, "intro_message")).contains("first");
        }

        @Test
        void ignoresRowsWithABlankKey() {
            List<Map.Entry<String, String>> rows = List.of(
                    new AbstractMap.SimpleEntry<>("  ", "orphan"),
                    new AbstractMap.SimpleEntry<>("intro_message", "Hello"));
            when(jdbcTemplate.query(contains("SELECT config_key, config_value"),
                    any(RowMapper.class), any(Object[].class))).thenReturn(rows);

            assertThat(repository.findConfigValue(TENANT, "intro_message")).contains("Hello");
        }
    }

    @Nested
    @DisplayName("caching")
    class Caching {

        @Test
        void loadsTheConfigTableOncePerTenantWithinTheTtl() {
            givenConfig(config("intro_message", "Hello"));

            repository.findConfigValue(TENANT, "intro_message");
            repository.findConfigValue(TENANT, "intro_message");

            verify(jdbcTemplate, times(1)).query(contains("SELECT config_key, config_value"),
                    any(RowMapper.class), any(Object[].class));
        }

        @Test
        void reloadsAfterTheTenantCacheIsInvalidated() {
            givenConfig(config("intro_message", "Hello"));

            repository.findConfigValue(TENANT, "intro_message");
            repository.invalidateTenantConfigCache(TENANT);
            repository.findConfigValue(TENANT, "intro_message");

            verify(jdbcTemplate, times(2)).query(contains("SELECT config_key, config_value"),
                    any(RowMapper.class), any(Object[].class));
        }

        @Test
        void reloadsAfterTheWholeCacheIsInvalidated() {
            givenConfig(config("intro_message", "Hello"));

            repository.findConfigValue(TENANT, "intro_message");
            repository.invalidateAllTenantConfigCache();
            repository.findConfigValue(TENANT, "intro_message");

            verify(jdbcTemplate, times(2)).query(contains("SELECT config_key, config_value"),
                    any(RowMapper.class), any(Object[].class));
        }

        @Test
        void invalidatingANullTenantIsANoOp() {
            givenConfig(config("intro_message", "Hello"));

            repository.findConfigValue(TENANT, "intro_message");
            repository.invalidateTenantConfigCache(null);
            repository.findConfigValue(TENANT, "intro_message");

            verify(jdbcTemplate, times(1)).query(contains("SELECT config_key, config_value"),
                    any(RowMapper.class), any(Object[].class));
        }

        @Test
        void queriesEveryTimeWhenCachingIsDisabled() {
            ReflectionTestUtils.setField(repository, "tenantConfigCacheEnabled", false);
            givenConfig(config("intro_message", "Hello"));

            repository.findConfigValue(TENANT, "intro_message");
            repository.findConfigValue(TENANT, "intro_message");

            verify(jdbcTemplate, times(2)).query(contains("SELECT config_key, config_value"),
                    any(RowMapper.class), any(Object[].class));
        }

        @Test
        void queriesEveryTimeWhenTheTtlIsNonPositive() {
            ReflectionTestUtils.setField(repository, "tenantConfigCacheTtlMs", 0L);
            givenConfig(config("intro_message", "Hello"));

            repository.findConfigValue(TENANT, "intro_message");
            repository.findConfigValue(TENANT, "intro_message");

            verify(jdbcTemplate, times(2)).query(contains("SELECT config_key, config_value"),
                    any(RowMapper.class), any(Object[].class));
        }
    }

    @Nested
    @DisplayName("indexed option lists")
    class IndexedOptions {

        @Test
        void returnsLanguageOptionsInIndexOrderRegardlessOfRowOrder() {
            givenConfig(config(
                    "language_3", "Assamese",
                    "language_1", "English",
                    "language_2", "Hindi"));

            assertThat(repository.findLanguageOptions(TENANT))
                    .containsExactly("English", "Hindi", "Assamese");
        }

        @Test
        void ordersDoubleDigitIndicesNumericallyRatherThanLexically() {
            givenConfig(config(
                    "language_10", "Tenth",
                    "language_2", "Second",
                    "language_1", "First"));

            assertThat(repository.findLanguageOptions(TENANT))
                    .containsExactly("First", "Second", "Tenth");
        }

        @Test
        void ignoresNonNumericAndLanguageScopedKeysForTheUnscopedList() {
            givenConfig(config(
                    "language_1", "English",
                    "language_selection_prompt", "Pick one",
                    "language_1_hindi", "अंग्रेज़ी"));

            assertThat(repository.findLanguageOptions(TENANT)).containsExactly("English");
        }

        @Test
        void returnsAnEmptyListWhenTheTenantHasNoConfig() {
            givenConfig(config());

            assertThat(repository.findLanguageOptions(TENANT)).isEmpty();
        }

        @Test
        void prefersLocalisedChannelOptionsWhenPresent() {
            givenConfig(config(
                    "channel_1", "Bulk Flow Meter",
                    "channel_2", "Electric Meter",
                    "channel_1_hindi", "बल्क फ्लो मीटर",
                    "channel_2_hindi", "इलेक्ट्रिक मीटर"));

            assertThat(repository.findChannelOptions(TENANT, "hindi"))
                    .containsExactly("बल्क फ्लो मीटर", "इलेक्ट्रिक मीटर");
        }

        @Test
        void fallsBackToUnscopedChannelOptionsWhenTheLanguageHasNone() {
            givenConfig(config("channel_1", "Bulk Flow Meter", "channel_2", "Electric Meter"));

            assertThat(repository.findChannelOptions(TENANT, "hindi"))
                    .containsExactly("Bulk Flow Meter", "Electric Meter");
        }

        @Test
        void treatsABlankLanguageKeyAsUnscoped() {
            givenConfig(config("channel_1", "Bulk Flow Meter"));

            assertThat(repository.findChannelOptions(TENANT, "  "))
                    .containsExactly("Bulk Flow Meter");
        }

        @Test
        void returnsLocalisedItemOptions() {
            givenConfig(config("item_1", "Submit reading", "item_1_hindi", "रीडिंग भेजें"));

            assertThat(repository.findItemOptions(TENANT, "hindi")).containsExactly("रीडिंग भेजें");
            assertThat(repository.findItemOptions(TENANT, null)).containsExactly("Submit reading");
        }

        @Test
        void returnsLocalisedMeterChangeReasons() {
            givenConfig(config(
                    "meter_change_reason_1", "Meter replaced",
                    "meter_change_reason_2", "Meter damaged"));

            assertThat(repository.findMeterChangeReasons(TENANT, "english"))
                    .containsExactly("Meter replaced", "Meter damaged");
        }

        @Test
        void returnsLocalisedIssueReportReasons() {
            givenConfig(config(
                    "issue_report_reason_1", "No electricity",
                    "issue_report_reason_1_hindi", "बिजली नहीं"));

            assertThat(repository.findIssueReportReasons(TENANT, "hindi")).containsExactly("बिजली नहीं");
        }
    }

    @Nested
    @DisplayName("language-scoped prompts")
    class LanguageScopedPrompts {

        @Test
        void prefersTheLanguageScopedLanguagePrompt() {
            givenConfig(config(
                    "language_selection_prompt", "Choose a language",
                    "language_selection_prompt_hindi", "भाषा चुनें"));

            assertThat(repository.findLanguageSelectionPrompt(TENANT, "hindi")).contains("भाषा चुनें");
        }

        @Test
        void fallsBackToTheUnscopedLanguagePrompt() {
            givenConfig(config("language_selection_prompt", "Choose a language"));

            assertThat(repository.findLanguageSelectionPrompt(TENANT, "hindi"))
                    .contains("Choose a language");
        }

        @Test
        void usesTheUnscopedLanguagePromptForABlankLanguageKey() {
            givenConfig(config("language_selection_prompt", "Choose a language"));

            assertThat(repository.findLanguageSelectionPrompt(TENANT, "  ")).contains("Choose a language");
            assertThat(repository.findLanguageSelectionPrompt(TENANT, null)).contains("Choose a language");
            assertThat(repository.findLanguageSelectionPrompt(TENANT)).contains("Choose a language");
        }

        @Test
        void resolvesEveryOtherLanguageScopedPromptWithTheSameFallback() {
            givenConfig(config(
                    "channel_selection_prompt", "Choose a channel",
                    "item_selection_prompt_hindi", "विकल्प चुनें",
                    "meter_change_prompt", "Meter change?",
                    "take_meter_reading_prompt_hindi", "रीडिंग लें",
                    "manual_reading_confirmation_template", "Confirm {reading}",
                    "meter_change_confirmation_template_hindi", "पुष्टि",
                    "issue_report_prompt", "Report an issue",
                    "issue_report_confirmation_template_hindi", "दर्ज"));

            assertThat(repository.findChannelSelectionPrompt(TENANT, "hindi")).contains("Choose a channel");
            assertThat(repository.findItemSelectionPrompt(TENANT, "hindi")).contains("विकल्प चुनें");
            assertThat(repository.findMeterChangePrompt(TENANT, "hindi")).contains("Meter change?");
            assertThat(repository.findTakeMeterReadingPrompt(TENANT, "hindi")).contains("रीडिंग लें");
            assertThat(repository.findManualReadingConfirmationTemplate(TENANT, "hindi"))
                    .contains("Confirm {reading}");
            assertThat(repository.findMeterChangeConfirmationTemplate(TENANT, "hindi")).contains("पुष्टि");
            assertThat(repository.findIssueReportPrompt(TENANT, "hindi")).contains("Report an issue");
            assertThat(repository.findIssueReportConfirmationTemplate(TENANT, "hindi")).contains("दर्ज");
        }

        @Test
        void isEmptyWhenNeitherTheScopedNorTheUnscopedKeyExists() {
            givenConfig(config());

            assertThat(repository.findChannelSelectionPrompt(TENANT, "hindi")).isEmpty();
            assertThat(repository.findItemSelectionPrompt(TENANT, "hindi")).isEmpty();
            assertThat(repository.findMeterChangePrompt(TENANT, "hindi")).isEmpty();
            assertThat(repository.findTakeMeterReadingPrompt(TENANT, "hindi")).isEmpty();
            assertThat(repository.findManualReadingConfirmationTemplate(TENANT, "hindi")).isEmpty();
            assertThat(repository.findMeterChangeConfirmationTemplate(TENANT, "hindi")).isEmpty();
            assertThat(repository.findIssueReportPrompt(TENANT, "hindi")).isEmpty();
            assertThat(repository.findIssueReportConfirmationTemplate(TENANT, "hindi")).isEmpty();
        }
    }
}
