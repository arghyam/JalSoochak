package org.arghyam.jalsoochak.message.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MessageTemplateService}.
 *
 * <p>Verifies language resolution, the fallback chain for both nudge and
 * escalation messages, Hindi normalization, and placeholder substitution.</p>
 */
@ExtendWith(MockitoExtension.class)
class MessageTemplateServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private MessageTemplateService service;

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void stubConfig(int tenantId, String key, String value) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(tenantId), eq(key)))
                .thenReturn(List.of(value));
    }

    private void stubConfigAbsent(int tenantId, String key) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(tenantId), eq(key)))
                .thenReturn(Collections.emptyList());
    }

    // ── findNudgeMessage ─────────────────────────────────────────────────────────

    @Test
    void findNudgeMessage_returnsLocalizedMessage_whenLanguageKeyMatches() {
        stubConfig(1, "language_2", "Marathi");
        stubConfig(1, "nudge_message_marathi", "प्रिय {name}, scheme {scheme} साठी आजची रीडिंग द्या.");

        String result = service.findNudgeMessage(1, 2, "Ramesh", "S-001");

        assertThat(result).isEqualTo("प्रिय Ramesh, scheme S-001 साठी आजची रीडिंग द्या.");
    }

    @Test
    void findNudgeMessage_fallsBackToEnglishKey_whenLanguageKeyNotFound() {
        stubConfig(1, "language_2", "Odia");
        stubConfigAbsent(1, "nudge_message_odia");
        stubConfig(1, "nudge_message_english", "Dear {name}, submit reading for scheme {scheme}.");

        String result = service.findNudgeMessage(1, 2, "Suresh", "S-101");

        assertThat(result).isEqualTo("Dear Suresh, submit reading for scheme S-101.");
    }

    @Test
    void findNudgeMessage_fallsBackToGenericKey_whenEnglishKeyNotFound() {
        stubConfig(1, "language_2", "Telugu");
        stubConfigAbsent(1, "nudge_message_telugu");
        stubConfigAbsent(1, "nudge_message_english");
        stubConfig(1, "nudge_message", "Submit reading for {scheme} today.");

        String result = service.findNudgeMessage(1, 2, "Operator", "S-200");

        assertThat(result).isEqualTo("Submit reading for S-200 today.");
    }

    @Test
    void findNudgeMessage_returnsHardcodedDefault_whenNoConfigFound() {
        stubConfig(1, "language_1", "English");
        stubConfigAbsent(1, "nudge_message_english");
        stubConfigAbsent(1, "nudge_message");

        String result = service.findNudgeMessage(1, 1, "Dev", "S-5");

        assertThat(result).contains("Dev").contains("S-5").contains("daily water reading");
    }

    @Test
    void findNudgeMessage_usesEnglishKey_whenLanguageIdIsZero() {
        // languageId <= 0 → skip the language lookup, use "english" directly
        stubConfig(1, "nudge_message_english", "English fallback for {name}.");

        String result = service.findNudgeMessage(1, 0, "Op", "S-0");

        assertThat(result).isEqualTo("English fallback for Op.");
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), eq(1), eq("language_0"));
    }

    @Test
    void findNudgeMessage_usesEnglishKey_whenLanguageIdIsNegative() {
        stubConfig(1, "nudge_message_english", "Negative id fallback for {name}.");

        String result = service.findNudgeMessage(1, -5, "Op", "S-0");

        assertThat(result).isEqualTo("Negative id fallback for Op.");
    }

    @Test
    void findNudgeMessage_handlesNullOperatorName() {
        stubConfig(1, "language_1", "English");
        stubConfig(1, "nudge_message_english", "Dear {name}, reading for {scheme}.");

        String result = service.findNudgeMessage(1, 1, null, "S-1");

        assertThat(result).isEqualTo("Dear , reading for S-1.");
    }

    @Test
    void findNudgeMessage_normalizesHindiDevanagari() {
        stubConfig(1, "language_3", "हिंदी");
        stubConfig(1, "nudge_message_hindi", "नमस्ते {name}, {scheme} के लिए रीडिंग दें।");

        String result = service.findNudgeMessage(1, 3, "Ram", "S-10");

        assertThat(result).isEqualTo("नमस्ते Ram, S-10 के लिए रीडिंग दें।");
    }

    @Test
    void findNudgeMessage_normalizesSpacesInLanguageName() {
        stubConfig(1, "language_5", "Dogri Gojri");
        stubConfig(1, "nudge_message_dogri_gojri", "Message for {name} re {scheme}.");

        String result = service.findNudgeMessage(1, 5, "Op", "S-5");

        assertThat(result).isEqualTo("Message for Op re S-5.");
    }

    @Test
    void findNudgeMessage_substitutesSchemeAndNamePlaceholders() {
        stubConfig(1, "language_1", "English");
        stubConfig(1, "nudge_message_english", "Hello {name}, please update {scheme}.");

        String result = service.findNudgeMessage(1, 1, "Priya", "MP-101");

        assertThat(result).isEqualTo("Hello Priya, please update MP-101.");
    }

    // ── findEscalationMessage ─────────────────────────────────────────────────────

    @Test
    void findEscalationMessage_returnsLocalizedMessage_whenLanguageKeyMatches() {
        stubConfig(2, "language_1", "English");
        stubConfig(2, "escalation_message_english", "Please review the attached escalation report.");

        String result = service.findEscalationMessage(2, 1);

        assertThat(result).isEqualTo("Please review the attached escalation report.");
    }

    @Test
    void findEscalationMessage_fallsBackToGenericKey() {
        stubConfig(2, "language_1", "Unknown");
        stubConfigAbsent(2, "escalation_message_unknown");
        stubConfigAbsent(2, "escalation_message_english");
        stubConfig(2, "escalation_message", "Escalation attached.");

        String result = service.findEscalationMessage(2, 1);

        assertThat(result).isEqualTo("Escalation attached.");
    }

    @Test
    void findEscalationMessage_returnsHardcodedDefault_whenNoConfigFound() {
        stubConfig(2, "language_1", "English");
        stubConfigAbsent(2, "escalation_message_english");
        stubConfigAbsent(2, "escalation_message");

        String result = service.findEscalationMessage(2, 1);

        assertThat(result).isEqualTo("Please find the escalation report attached.");
    }

    @Test
    void findEscalationMessage_usesEnglishKey_whenLanguageIdIsZero() {
        stubConfig(2, "escalation_message_english", "Report enclosed.");

        String result = service.findEscalationMessage(2, 0);

        assertThat(result).isEqualTo("Report enclosed.");
        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), eq(2), eq("language_0"));
    }
}
