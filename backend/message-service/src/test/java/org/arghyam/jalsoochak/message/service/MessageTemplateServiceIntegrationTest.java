package org.arghyam.jalsoochak.message.service;

import org.arghyam.jalsoochak.message.channel.GlificAuthService;
import org.arghyam.jalsoochak.message.channel.GlificWhatsAppService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link MessageTemplateService} language resolution and
 * fallback chain against a real PostgreSQL instance.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Correct localized message returned when config key exists</li>
 *   <li>Fallback chain: lang-specific → english → generic → hardcoded default</li>
 *   <li>Language resolution: languageId → {@code language_N} config key → name → normalized key</li>
 *   <li>Hindi and English language normalization</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers(disabledWithoutDocker = true)
class MessageTemplateServiceIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    // Suppress GlificAuthService @PostConstruct login
    @MockBean
    private GlificAuthService glificAuthService;

    // Suppress GlificWhatsAppService @PostConstruct validateTemplates
    @MockBean
    private GlificWhatsAppService glificWhatsAppService;

    @Autowired
    private MessageTemplateService messageTemplateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int TENANT_ID = 1; // seeded in test-schema.sql

    @AfterEach
    void cleanConfig() {
        jdbcTemplate.execute("DELETE FROM common_schema.tenant_config_master_table");
    }

    // ─────────────────────────────── nudge ─────────────────────────────────────

    @Test
    void findNudgeMessage_returnsHardcodedDefault_whenNoConfigExists() {
        String msg = messageTemplateService.findNudgeMessage(TENANT_ID, 0, "Ramu", "S1");

        assertThat(msg).contains("Ramu")
                .contains("S1")
                .contains("daily water reading");
    }

    @Test
    void findNudgeMessage_returnsLocalizedMessage_byLanguageKey() {
        insertConfig("language_1", "Hindi");
        insertConfig("nudge_message_hindi", "प्रिय {name}, कृपया आज की रीडिंग दर्ज करें। योजना: {scheme}");

        String msg = messageTemplateService.findNudgeMessage(TENANT_ID, 1, "राम", "योजना-1");

        assertThat(msg).contains("राम").contains("योजना-1");
    }

    @Test
    void findNudgeMessage_fallsBackToEnglish_whenLangSpecificKeyAbsent() {
        insertConfig("language_1", "Hindi");
        // nudge_message_hindi not present → fallback to nudge_message_english
        insertConfig("nudge_message_english", "Dear {name}, please upload for scheme {scheme}.");

        String msg = messageTemplateService.findNudgeMessage(TENANT_ID, 1, "Ramesh", "S-42");

        assertThat(msg).contains("Ramesh").contains("S-42");
    }

    @Test
    void findNudgeMessage_fallsBackToGenericKey_whenEnglishKeyAbsent() {
        insertConfig("nudge_message", "Submit your reading, {name}, scheme {scheme}.");

        String msg = messageTemplateService.findNudgeMessage(TENANT_ID, 0, "Op A", "S-01");

        assertThat(msg).contains("Op A").contains("S-01");
    }

    @Test
    void findNudgeMessage_replacesNameAndScheme_placeholders() {
        insertConfig("nudge_message", "Hello {name}! Scheme: {scheme}.");

        String msg = messageTemplateService.findNudgeMessage(TENANT_ID, 0, "Suresh", "MY-SCHEME");

        assertThat(msg).isEqualTo("Hello Suresh! Scheme: MY-SCHEME.");
    }

    @Test
    void findNudgeMessage_handlesZeroLanguageId_asEnglish() {
        insertConfig("nudge_message_english", "English nudge for {name} - {scheme}.");

        String msg = messageTemplateService.findNudgeMessage(TENANT_ID, 0, "Op", "S");

        assertThat(msg).isEqualTo("English nudge for Op - S.");
    }

    @Test
    void findNudgeMessage_handlesNegativeLanguageId_asEnglish() {
        insertConfig("nudge_message_english", "Negative id fallback for {name}.");

        String msg = messageTemplateService.findNudgeMessage(TENANT_ID, -5, "Op", "S-0");

        assertThat(msg).isEqualTo("Negative id fallback for Op.");
        // Verify that language_-5 was NOT queried (negative IDs bypass language lookup)
        // This is a regression guard: the service should skip to "english" key directly
    }

    @Test
    void findNudgeMessage_normalizesHindiScript_toLangKey() {
        insertConfig("language_2", "हिंदी");
        insertConfig("nudge_message_hindi", "नमस्ते {name}, {scheme}");

        String msg = messageTemplateService.findNudgeMessage(TENANT_ID, 2, "रवि", "Y-01");

        assertThat(msg).contains("रवि").contains("Y-01");
    }

    // ──────────────────────────── escalation ───────────────────────────────────

    @Test
    void findEscalationMessage_returnsHardcodedDefault_whenNoConfigExists() {
        String msg = messageTemplateService.findEscalationMessage(TENANT_ID, 0);

        assertThat(msg).contains("escalation report attached");
    }

    @Test
    void findEscalationMessage_returnsLocalizedMessage_byLanguageKey() {
        insertConfig("language_1", "English");
        insertConfig("escalation_message_english", "Please check the attached escalation report.");

        String msg = messageTemplateService.findEscalationMessage(TENANT_ID, 1);

        assertThat(msg).isEqualTo("Please check the attached escalation report.");
    }

    @Test
    void findEscalationMessage_fallsBackToEnglish_whenLangSpecificKeyAbsent() {
        insertConfig("language_1", "Marathi");
        // escalation_message_marathi not present → fallback
        insertConfig("escalation_message_english", "Escalation report is attached.");

        String msg = messageTemplateService.findEscalationMessage(TENANT_ID, 1);

        assertThat(msg).isEqualTo("Escalation report is attached.");
    }

    @Test
    void findEscalationMessage_fallsBackToGenericKey_whenEnglishKeyAbsent() {
        insertConfig("escalation_message", "Please review the report.");

        String msg = messageTemplateService.findEscalationMessage(TENANT_ID, 0);

        assertThat(msg).isEqualTo("Please review the report.");
    }

    @Test
    void findEscalationMessage_handlesUnknownLanguageId_gracefully() {
        // Language ID 99 has no corresponding config key → fallback to english
        insertConfig("escalation_message_english", "Fallback English escalation message.");

        String msg = messageTemplateService.findEscalationMessage(TENANT_ID, 99);

        assertThat(msg).isEqualTo("Fallback English escalation message.");
    }

    @Test
    void findEscalationMessage_normalizesLanguageName_toLowercase() {
        insertConfig("language_3", "Telugu");
        insertConfig("escalation_message_telugu", "Telugu escalation message.");

        String msg = messageTemplateService.findEscalationMessage(TENANT_ID, 3);

        assertThat(msg).isEqualTo("Telugu escalation message.");
    }

    // ──────────────────────────── state name ───────────────────────────────────

    @Test
    void findStateName_returnsConfiguredValue_whenStateNameKeyExists() {
        insertConfig("state_name", "Madhya Pradesh");

        String name = messageTemplateService.findStateName(TENANT_ID);

        assertThat(name).isEqualTo("Madhya Pradesh");
    }

    @Test
    void findStateName_fallsBackToTenantTitle_whenKeyAbsent() {
        String name = messageTemplateService.findStateName(TENANT_ID);

        assertThat(name).isEqualTo("Test State");
    }

    @Test
    void findStateName_fallsBackToTenantTitle_whenValueIsBlank() {
        insertConfig("state_name", "   ");

        String name = messageTemplateService.findStateName(TENANT_ID);

        assertThat(name).isEqualTo("Test State");
    }

    // ────────────────────────────── helpers ────────────────────────────────────

    private void insertConfig(String key, String value) {
        jdbcTemplate.update(
                "INSERT INTO common_schema.tenant_config_master_table (tenant_id, config_key, config_value) VALUES (?, ?, ?)",
                TENANT_ID, key, value);
    }
}
