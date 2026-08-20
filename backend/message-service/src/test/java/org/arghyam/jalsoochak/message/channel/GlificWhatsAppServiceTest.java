package org.arghyam.jalsoochak.message.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import org.mockito.InOrder;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GlificWhatsAppService}.
 *
 * <p>Focuses on the two-step escalation flow:
 * <ol>
 *   <li>{@code uploadMedia} – calls {@code createMessageMedia} and extracts the media ID.</li>
 *   <li>{@code sendEscalationHsm} – uploads media first, then calls
 *       {@code createAndSendMessage} with the returned media ID.</li>
 * </ol>
 * Also covers opt-in and nudge HSM delegation.
 */
@ExtendWith(MockitoExtension.class)
class GlificWhatsAppServiceTest {

    @Mock
    private GlificGraphQLClient client;

    @InjectMocks
    private GlificWhatsAppService service;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "objectMapper", mapper);
        ReflectionTestUtils.setField(service, "nudgeTemplateId", "nudge-tmpl-1");
        ReflectionTestUtils.setField(service, "nudgeFlowId", "flow-123");
        ReflectionTestUtils.setField(service, "welcomeFlowId", "welcome-flow-456");
        ReflectionTestUtils.setField(service, "escalationTemplateId", "2");   // must be numeric for Integer.parseInt
        ReflectionTestUtils.setField(service, "escalationCaption", "Escalations");
        ReflectionTestUtils.setField(service, "escalationThumbnail", "");
        // Glific hands media URLs to Meta, which fetches them from the public internet, so every
        // sending path now requires a publicly reachable prefix.
        ReflectionTestUtils.setField(service, "mediaBaseUrl", "https://jalsoochak.jjmbrain.in/minio");
    }

    // ──────────────────────────── optIn ────────────────────────────────────────

    @Test
    void optIn_returnsContactId_fromGlificResponse() throws Exception {
        JsonNode response = mapper.readTree(
                """
                {"optinContact":{"contact":{"id":42}}}
                """);
        when(client.execute(contains("optinContact"), anyMap())).thenReturn(response);

        Long contactId = service.optIn("919876543210");

        assertThat(contactId).isEqualTo(42L);
        verify(client).execute(contains("optinContact"), argThat(vars ->
                "919876543210".equals(vars.get("phone"))));
    }

    // ──────────────────────────── sendNudgeHsm ─────────────────────────────────

    @Test
    void sendNudgeHsm_callsSendHsmMutation_withCorrectParameters() throws Exception {
        JsonNode response = mapper.readTree("""
                {"sendHsmMessage":{"message":{"id":1,"body":"Hi","isHSM":true},"errors":[]}}
                """);
        when(client.execute(contains("sendHsmMessage"), anyMap())).thenReturn(response);

        service.sendNudgeHsm(99L, "Ramesh", "02 March 2026");

        ArgumentCaptor<Map<String, Object>> varsCaptor = varsCaptor();
        verify(client).execute(contains("sendHsmMessage"), varsCaptor.capture());

        Map<String, Object> vars = varsCaptor.getValue();
        assertThat(vars.get("templateId")).isEqualTo("nudge-tmpl-1");
        assertThat(vars.get("receiverId")).isEqualTo(99L);
        assertThat(vars.get("parameters")).isEqualTo(List.of("Ramesh", "02 March 2026"));
    }

    // ──────────────────────── uploadMedia ──────────────────────────────────────

    @Test
    void uploadMedia_callsCreateMessageMediaMutation_withUrlAndSourceUrl() throws Exception {
        JsonNode response = mapper.readTree("""
                {"createMessageMedia":{"messageMedia":{"id":"777","url":"https://example.com/r.pdf"},"errors":[]}}
                """);
        when(client.execute(contains("createMessageMedia"), anyMap())).thenReturn(response);

        String mediaId = service.uploadMedia("https://example.com/r.pdf");

        assertThat(mediaId).isEqualTo("777");

        ArgumentCaptor<Map<String, Object>> varsCaptor = varsCaptor();
        verify(client).execute(contains("createMessageMedia"), varsCaptor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) varsCaptor.getValue().get("input");
        assertThat(input.get("url")).isEqualTo("https://example.com/r.pdf");
        assertThat(input.get("source_url")).isEqualTo("https://example.com/r.pdf");
        assertThat(input.get("isTemplateMedia")).isEqualTo(true);
    }

    @Test
    void uploadMedia_throwsException_whenClientFails() {
        when(client.execute(contains("createMessageMedia"), anyMap()))
                .thenThrow(new RuntimeException("Glific media upload failed"));

        assertThatThrownBy(() -> service.uploadMedia("https://example.com/r.pdf"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Glific media upload failed");
    }

    // ──────────────────────── sendEscalationHsm ────────────────────────────────

    @Test
    void sendEscalationHsm_uploadsMediaFirst_thenCallsCreateAndSendMessage() throws Exception {
        JsonNode uploadResponse = mapper.readTree("""
                {"createMessageMedia":{"messageMedia":{"id":"999","url":"https://minio.example.com/r.pdf"},"errors":[]}}
                """);
        JsonNode sendResponse = mapper.readTree("""
                {"createAndSendMessage":{"message":{"id":2,"body":"body","isHsm":true},"errors":[]}}
                """);

        when(client.execute(contains("createMessageMedia"), anyMap())).thenReturn(uploadResponse);
        when(client.execute(contains("createAndSendMessage"), anyMap())).thenReturn(sendResponse);

        service.sendEscalationHsm(55L, "https://minio.example.com/r.pdf");

        InOrder inOrder = inOrder(client);
        inOrder.verify(client).execute(contains("createMessageMedia"), anyMap());
        inOrder.verify(client).execute(contains("createAndSendMessage"), anyMap());
    }

    @Test
    void sendEscalationHsm_passesMediaIdFromUpload_toCreateAndSendMessage() throws Exception {
        JsonNode uploadResponse = mapper.readTree("""
                {"createMessageMedia":{"messageMedia":{"id":"123","url":"https://minio.example.com/r.pdf"}}}
                """);
        JsonNode sendResponse = mapper.readTree("""
                {"createAndSendMessage":{"message":{"id":3,"body":"ok","isHsm":true},"errors":[]}}
                """);

        when(client.execute(contains("createMessageMedia"), anyMap())).thenReturn(uploadResponse);
        when(client.execute(contains("createAndSendMessage"), anyMap())).thenReturn(sendResponse);

        service.sendEscalationHsm(77L, "https://minio.example.com/report.pdf");

        ArgumentCaptor<Map<String, Object>> sendVarsCaptor = varsCaptor();
        verify(client).execute(contains("createAndSendMessage"), sendVarsCaptor.capture());

        // Implementation wraps all fields in an "input" map for the GraphQL mutation
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) sendVarsCaptor.getValue().get("input");
        assertThat(input.get("mediaId")).isEqualTo(123);    // Integer.parseInt("123")
        assertThat(input.get("templateId")).isEqualTo(2);   // Integer.parseInt("2")
        assertThat(input.get("receiverId")).isEqualTo(77);  // contactId.intValue()
    }

    @Test
    void sendEscalationHsm_doesNotCallCreateAndSend_whenUploadFails() {
        when(client.execute(contains("createMessageMedia"), anyMap()))
                .thenThrow(new RuntimeException("MinIO URL unreachable"));

        assertThatThrownBy(() ->
                service.sendEscalationHsm(88L, "https://minio.example.com/r.pdf"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("MinIO URL unreachable");

        verify(client, never()).execute(contains("createAndSendMessage"), anyMap());
    }

    @Test
    void sendEscalationHsm_sendsDocumentAttachment_viaMutation() throws Exception {
        JsonNode uploadResponse = mapper.readTree("""
                {"createMessageMedia":{"messageMedia":{"id":"1"}}}
                """);
        JsonNode sendResponse = mapper.readTree("""
                {"createAndSendMessage":{"message":{"id":4,"body":"ok","isHsm":true},"errors":[]}}
                """);
        when(client.execute(contains("createMessageMedia"), anyMap())).thenReturn(uploadResponse);
        when(client.execute(contains("createAndSendMessage"), anyMap())).thenReturn(sendResponse);

        service.sendEscalationHsm(11L, "https://minio.example.com/r.pdf");

        ArgumentCaptor<Map<String, Object>> captor = varsCaptor();
        verify(client).execute(contains("createAndSendMessage"), captor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) captor.getValue().get("input");
        assertThat(input.get("mediaId")).isEqualTo(1);   // Integer.parseInt("1")
        assertThat(input.get("isHsm")).isEqualTo(true);
        assertThat((List<?>) input.get("params")).isEmpty();
    }

    // ──────────────────────── startNudgeFlow ───────────────────────────────────

    @Test
    void startNudgeFlow_callsStartContactFlowMutation_withFlowIdAndContactId() throws Exception {
        JsonNode response = mapper.readTree("""
                {"startContactFlow":{"success":true,"errors":[]}}
                """);
        when(client.execute(contains("startContactFlow"), anyMap())).thenReturn(response);

        service.startNudgeFlow(42L, "Ramesh", "06 March 2026");

        ArgumentCaptor<Map<String, Object>> varsCaptor = varsCaptor();
        verify(client).execute(contains("startContactFlow"), varsCaptor.capture());

        Map<String, Object> vars = varsCaptor.getValue();
        assertThat(vars.get("flowId")).isEqualTo("flow-123");
        assertThat(vars.get("contactId")).isEqualTo(42L);
    }

    @Test
    void startNudgeFlow_passesDefaultResults_withOperatorNameAndDate() throws Exception {
        JsonNode response = mapper.readTree("""
                {"startContactFlow":{"success":true,"errors":[]}}
                """);
        when(client.execute(contains("startContactFlow"), anyMap())).thenReturn(response);

        service.startNudgeFlow(42L, "Ramesh", "06 March 2026");

        ArgumentCaptor<Map<String, Object>> varsCaptor = varsCaptor();
        verify(client).execute(contains("startContactFlow"), varsCaptor.capture());

        String defaultResults = (String) varsCaptor.getValue().get("defaultResults");
        JsonNode parsed = mapper.readTree(defaultResults);
        assertThat(parsed.get("name").asText()).isEqualTo("Ramesh");
        assertThat(parsed.get("date").asText()).isEqualTo("06 March 2026");
    }

    @Test
    void startNudgeFlow_throwsIllegalState_whenFlowIdNotConfigured() {
        ReflectionTestUtils.setField(service, "nudgeFlowId", "");

        assertThatThrownBy(() -> service.startNudgeFlow(42L, "Ramesh", "06 March 2026"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("glific.flow.nudge-id");
    }

    @Test
    void startNudgeFlow_throwsException_whenGlificReturnsErrors() throws Exception {
        JsonNode response = mapper.readTree("""
                {"startContactFlow":{"success":false,"errors":[{"key":"flow","message":"not found"}]}}
                """);
        when(client.execute(contains("startContactFlow"), anyMap())).thenReturn(response);

        assertThatThrownBy(() -> service.startNudgeFlow(42L, "Ramesh", "06 March 2026"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("startContactFlow");
    }

    @Test
    void startNudgeFlow_throwsException_whenSuccessIsFalse() throws Exception {
        JsonNode response = mapper.readTree("""
                {"startContactFlow":{"success":false,"errors":[]}}
                """);
        when(client.execute(contains("startContactFlow"), anyMap())).thenReturn(response);

        assertThatThrownBy(() -> service.startNudgeFlow(42L, "Ramesh", "06 March 2026"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("success=false");
    }

    // ──────────────────────── GraphQL error handling ────────────────────────────

    @Test
    void optIn_throwsException_whenGraphQLErrorsReturned() throws Exception {
        JsonNode response = mapper.readTree("""
                {"optinContact":{"contact":null,"errors":[{"key":"phone","message":"invalid"}]}}
                """);
        when(client.execute(contains("optinContact"), anyMap())).thenReturn(response);

        assertThatThrownBy(() -> service.optIn("91invalid"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("optinContact");
    }

    @Test
    void sendNudgeHsm_throwsException_whenGraphQLErrorsReturned() throws Exception {
        JsonNode response = mapper.readTree("""
                {"sendHsmMessage":{"message":null,"errors":[{"key":"template","message":"not found"}]}}
                """);
        when(client.execute(contains("sendHsmMessage"), anyMap())).thenReturn(response);

        assertThatThrownBy(() -> service.sendNudgeHsm(1L, "Ramesh", "04 March 2026"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("sendHsmMessage");
    }

    @Test
    void uploadMedia_throwsException_whenGraphQLErrorsReturned() throws Exception {
        JsonNode response = mapper.readTree("""
                {"createMessageMedia":{"messageMedia":null,"errors":[{"key":"url","message":"unreachable"}]}}
                """);
        when(client.execute(contains("createMessageMedia"), anyMap())).thenReturn(response);

        assertThatThrownBy(() -> service.uploadMedia("https://example.com/r.pdf"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("createMessageMedia");
    }

    @Test
    void sendEscalationHsm_throwsException_whenCreateAndSendReturnsErrors() throws Exception {
        JsonNode uploadResponse = mapper.readTree("""
                {"createMessageMedia":{"messageMedia":{"id":"5"},"errors":[]}}
                """);
        JsonNode sendResponse = mapper.readTree("""
                {"createAndSendMessage":{"message":null,"errors":[{"key":"contact","message":"blocked"}]}}
                """);
        when(client.execute(contains("createMessageMedia"), anyMap())).thenReturn(uploadResponse);
        when(client.execute(contains("createAndSendMessage"), anyMap())).thenReturn(sendResponse);

        assertThatThrownBy(() ->
                service.sendEscalationHsm(22L, "https://minio.example.com/r.pdf"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("createAndSendMessage");
    }

    // ──────────────────────── startWelcomeFlow ─────────────────────────────────

    @Test
    void startWelcomeFlow_callsStartContactFlowMutation_withWelcomeFlowId() throws Exception {
        JsonNode response = mapper.readTree("""
                {"startContactFlow":{"success":true,"errors":[]}}
                """);
        when(client.execute(contains("startContactFlow"), anyMap())).thenReturn(response);

        service.startWelcomeFlow(55L, "Ramesh Kumar", "Madhya Pradesh");

        ArgumentCaptor<Map<String, Object>> varsCaptor = varsCaptor();
        verify(client).execute(contains("startContactFlow"), varsCaptor.capture());

        Map<String, Object> vars = varsCaptor.getValue();
        assertThat(vars.get("flowId")).isEqualTo("welcome-flow-456");
        assertThat(vars.get("contactId")).isEqualTo(55L);
    }

    @Test
    void startWelcomeFlow_passesNameAndState_inDefaultResults() throws Exception {
        JsonNode response = mapper.readTree("""
                {"startContactFlow":{"success":true,"errors":[]}}
                """);
        when(client.execute(contains("startContactFlow"), anyMap())).thenReturn(response);

        service.startWelcomeFlow(55L, "welcome-flow-456", "Ramesh Kumar", "Madhya Pradesh");

        ArgumentCaptor<Map<String, Object>> varsCaptor = varsCaptor();
        verify(client).execute(contains("startContactFlow"), varsCaptor.capture());

        String defaultResults = (String) varsCaptor.getValue().get("defaultResults");
        JsonNode results = mapper.readTree(defaultResults);
        assertThat(results.path("name").asText()).isEqualTo("Ramesh Kumar");
        assertThat(results.path("state").asText()).isEqualTo("Madhya Pradesh");
    }

    @Test
    void startWelcomeFlow_usesEmptyStrings_whenNameAndStateAreNull() throws Exception {
        JsonNode response = mapper.readTree("""
                {"startContactFlow":{"success":true,"errors":[]}}
                """);
        when(client.execute(contains("startContactFlow"), anyMap())).thenReturn(response);

        service.startWelcomeFlow(55L, "welcome-flow-456", null, null);

        ArgumentCaptor<Map<String, Object>> varsCaptor = varsCaptor();
        verify(client).execute(contains("startContactFlow"), varsCaptor.capture());

        String defaultResults = (String) varsCaptor.getValue().get("defaultResults");
        JsonNode results = mapper.readTree(defaultResults);
        assertThat(results.path("name").asText()).isEqualTo("");
        assertThat(results.path("state").asText()).isEqualTo("");
    }

    @Test
    void startWelcomeFlow_throwsException_whenGlificReturnsErrors() throws Exception {
        JsonNode response = mapper.readTree("""
                {"startContactFlow":{"success":false,"errors":[{"key":"flow","message":"not found"}]}}
                """);
        when(client.execute(contains("startContactFlow"), anyMap())).thenReturn(response);

        assertThatThrownBy(() -> service.startWelcomeFlow(55L, "Ramesh", "MP"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("startContactFlow");
    }

    @Test
    void startWelcomeFlow_throwsException_whenSuccessIsFalse() throws Exception {
        JsonNode response = mapper.readTree("""
                {"startContactFlow":{"success":false,"errors":[]}}
                """);
        when(client.execute(contains("startContactFlow"), anyMap())).thenReturn(response);

        assertThatThrownBy(() -> service.startWelcomeFlow(55L, "Ramesh", "MP"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("success=false");
    }

    // ──────────────────────── updateContactLanguage ────────────────────────────

    @Test
    void updateContactLanguage_callsUpdateContactMutation_withCorrectIdAndLanguageId() throws Exception {
        JsonNode response = mapper.readTree("""
                {"updateContact":{"contact":{"id":42,"language":{"id":2}},"errors":[]}}
                """);
        when(client.execute(contains("updateContact"), anyMap())).thenReturn(response);

        service.updateContactLanguage(42L, 2);

        ArgumentCaptor<Map<String, Object>> varsCaptor = varsCaptor();
        verify(client).execute(contains("updateContact"), varsCaptor.capture());

        Map<String, Object> vars = varsCaptor.getValue();
        assertThat(vars.get("id")).isEqualTo(42L);
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) vars.get("input");
        assertThat(input.get("language_id")).isEqualTo(2);
    }

    @Test
    void updateContactLanguage_throwsException_whenGraphQLErrorsReturned() throws Exception {
        JsonNode response = mapper.readTree("""
                {"updateContact":{"contact":null,"errors":[{"key":"id","message":"not found"}]}}
                """);
        when(client.execute(contains("updateContact"), anyMap())).thenReturn(response);

        assertThatThrownBy(() -> service.updateContactLanguage(99L, 3))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("updateContact");
    }

    // ──────────────────────────── sendLoginOtpHsm ──────────────────────────────

    @Test
    void sendLoginOtpHsm_callsSendHsmMutation_withOtpParameter() throws Exception {
        ReflectionTestUtils.setField(service, "loginOtpTemplateId", "otp-tmpl-1");
        JsonNode response = mapper.readTree("""
                {"sendHsmMessage":{"message":{"id":5,"body":"otp","isHSM":true},"errors":[]}}
                """);
        when(client.execute(contains("sendHsmMessage"), anyMap())).thenReturn(response);

        service.sendLoginOtpHsm(11L, "654321");

        ArgumentCaptor<Map<String, Object>> varsCaptor = varsCaptor();
        verify(client).execute(contains("sendHsmMessage"), varsCaptor.capture());
        Map<String, Object> vars = varsCaptor.getValue();
        assertThat(vars.get("templateId")).isEqualTo("otp-tmpl-1");
        assertThat(vars.get("receiverId")).isEqualTo(11L);
        assertThat(vars.get("parameters")).isEqualTo(List.of("654321"));
    }

    @Test
    void sendLoginOtpHsm_throwsException_whenTemplateIdNotConfigured() {
        ReflectionTestUtils.setField(service, "loginOtpTemplateId", "");

        assertThatThrownBy(() -> service.sendLoginOtpHsm(11L, "000000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("login-otp-id");
    }

    @Test
    void sendLoginOtpHsm_throwsException_whenGraphQLErrorsReturned() throws Exception {
        ReflectionTestUtils.setField(service, "loginOtpTemplateId", "otp-tmpl-1");
        JsonNode response = mapper.readTree("""
                {"sendHsmMessage":{"message":null,"errors":[{"key":"contact","message":"blocked"}]}}
                """);
        when(client.execute(contains("sendHsmMessage"), anyMap())).thenReturn(response);

        assertThatThrownBy(() -> service.sendLoginOtpHsm(11L, "654321"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("sendHsmMessage");
    }

    // ────────────── daily-report template-id validation (@PostConstruct) ────────────

    @Test
    void validateTemplates_throwsWhenDailyReportSoTemplateIdNotNumeric() {
        // Delivery enabled (no dry-run) and the SO template id is present but non-numeric —
        // sendDailyReportHsm would fail at Integer.parseInt, so we must fail fast at startup.
        ReflectionTestUtils.setField(service, "loginOtpTemplateId", "otp-tmpl-1");
        ReflectionTestUtils.setField(service, "dailyReportSoTemplateId", "not-a-number");

        assertThatThrownBy(() -> service.validateTemplates())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("daily-report-so-id");
    }

    @Test
    void validateTemplates_throwsWhenDailyReportSdoTemplateIdNotNumeric() {
        ReflectionTestUtils.setField(service, "loginOtpTemplateId", "otp-tmpl-1");
        ReflectionTestUtils.setField(service, "dailyReportSoTemplateId", "42");
        ReflectionTestUtils.setField(service, "dailyReportSdoTemplateId", "abc");

        assertThatThrownBy(() -> service.validateTemplates())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("daily-report-sdo-id");
    }

    @Test
    void validateTemplates_throwsWhenDailyReportLiveButSoTemplateIdMissing_evenIfEveryOtherPurposeIsDry() {
        // The all-dry short circuit used to ignore the daily-report flag, so this configuration
        // started up without ever validating the template that the live purpose needs.
        ReflectionTestUtils.setField(service, "whatsappDryRun", true);
        ReflectionTestUtils.setField(service, "nudgeDryRun", true);
        ReflectionTestUtils.setField(service, "escalationDryRun", true);
        ReflectionTestUtils.setField(service, "dailyReportDryRun", false);
        ReflectionTestUtils.setField(service, "dailyReportSoTemplateId", "");

        assertThatThrownBy(() -> service.validateTemplates())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("daily-report-so-id");
    }

    @Test
    void validateTemplates_passesWhenDailyReportTemplateIdsAreNumeric() {
        ReflectionTestUtils.setField(service, "loginOtpTemplateId", "otp-tmpl-1");
        ReflectionTestUtils.setField(service, "dailyReportSoTemplateId", "42");
        ReflectionTestUtils.setField(service, "dailyReportSdoTemplateId", "43");

        assertThatCode(() -> service.validateTemplates()).doesNotThrowAnyException();
    }

    // ────────────────────────────── helpers ────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> varsCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    // ─────────────────── daily report document name (WhatsApp) ─────────────────

    @Test
    void dailyReportDocumentName_appendsTheReportDataDate() {
        ReflectionTestUtils.setField(service, "dailyReportCaption", "Daily Water Service Situation Report");

        // A report delivered on 14 Aug covers 13 Aug, and is named for the day it describes.
        assertThat(service.dailyReportDocumentName(LocalDate.of(2026, 8, 13)))
                .isEqualTo("Daily Water Service Situation Report 13-08-2026");
        // Single-digit day and month stay zero-padded.
        assertThat(service.dailyReportDocumentName(LocalDate.of(2026, 1, 5)))
                .isEqualTo("Daily Water Service Situation Report 05-01-2026");
    }

    @Test
    void dailyReportDocumentName_withoutADate_fallsBackToTheBareCaption() {
        ReflectionTestUtils.setField(service, "dailyReportCaption", "Daily Water Service Situation Report");

        assertThat(service.dailyReportDocumentName(null))
                .isEqualTo("Daily Water Service Situation Report");
    }

    @Test
    void sendDailyReportHsm_uploadsMediaUnderTheDatedDocumentName() throws Exception {
        // The createMessageMedia caption is what Glific surfaces as the document's filename in
        // WhatsApp, so this is the assertion that pins the recipient-visible name.
        ReflectionTestUtils.setField(service, "dailyReportCaption", "Daily Water Service Situation Report");
        ReflectionTestUtils.setField(service, "dailyReportSoTemplateId", "42");
        ReflectionTestUtils.setField(service, "escalationThumbnail", "");
        when(client.execute(contains("createMessageMedia"), anyMap()))
                .thenReturn(mapper.readTree("{\"createMessageMedia\":{\"messageMedia\":{\"id\":\"77\"}}}"));
        when(client.execute(contains("createAndSendMessage"), anyMap()))
                .thenReturn(mapper.readTree("{\"createAndSendMessage\":{\"message\":{\"id\":\"1\"}}}"));

        service.sendDailyReportHsm(555L, "https://minio.example.com/report.pdf", "SECTION_OFFICER",
                LocalDate.of(2026, 8, 13));

        ArgumentCaptor<Map<String, Object>> vars = varsCaptor();
        verify(client).execute(contains("createMessageMedia"), vars.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) vars.getValue().get("input");
        assertThat(input).containsEntry("caption", "Daily Water Service Situation Report 13-08-2026");
        assertThat(input).containsEntry("url", "https://minio.example.com/report.pdf");
    }

    // ──────────────────────────── dry-run mode ─────────────────────────────────

    @Nested
    class DryRunMode {

        @BeforeEach
        void enableDryRun() {
            // Master + every purpose flag on → every Glific call suppressed, opt-in included.
            ReflectionTestUtils.setField(service, "whatsappDryRun", true);
            ReflectionTestUtils.setField(service, "nudgeDryRun", true);
            ReflectionTestUtils.setField(service, "escalationDryRun", true);
            ReflectionTestUtils.setField(service, "dailyReportDryRun", true);
        }

        @Test
        void optIn_returnsZero_andDoesNotCallClient() {
            Long result = service.optIn("919876543210");

            assertThat(result).isEqualTo(0L);
            verifyNoInteractions(client);
        }

        @Test
        void sendNudgeHsm_isNoOp() {
            service.sendNudgeHsm(42L, "Ramesh", "22 March 2026");

            verifyNoInteractions(client);
        }

        @Test
        void uploadMedia_returnsDryRunId_andDoesNotCallClient() {
            String mediaId = service.uploadMedia("https://minio.example.com/r.pdf");

            assertThat(mediaId).isEqualTo("dry-run-media-id");
            verifyNoInteractions(client);
        }

        @Test
        void sendEscalationHsm_isNoOp() {
            service.sendEscalationHsm(55L, "https://minio.example.com/r.pdf");

            verifyNoInteractions(client);
        }

        @Test
        void startNudgeFlow_isNoOp() {
            service.startNudgeFlow(42L, "Ramesh", "22 March 2026");

            verifyNoInteractions(client);
        }

        @Test
        void startWelcomeFlow_isNoOp() {
            service.startWelcomeFlow(55L, "Ramesh", "MP");

            verifyNoInteractions(client);
        }

        @Test
        void updateContactLanguage_isNoOp() {
            service.updateContactLanguage(42L, 2);

            verifyNoInteractions(client);
        }

        @Test
        void sendLoginOtpHsm_isNoOp() {
            ReflectionTestUtils.setField(service, "loginOtpTemplateId", "otp-tmpl-1");

            service.sendLoginOtpHsm(99L, "123456");

            verifyNoInteractions(client);
        }

        @Test
        void sendDailyReportHsm_isNoOp() {
            service.sendDailyReportHsm(0L, "https://minio.example.com/daily.pdf", "SECTION_OFFICER",
                    LocalDate.of(2026, 8, 19));

            verifyNoInteractions(client);
        }

        @Test
        void validateTemplates_doesNotThrowWhenTemplateIdsBlank() {
            ReflectionTestUtils.setField(service, "nudgeFlowId", "");
            ReflectionTestUtils.setField(service, "escalationTemplateId", "");
            ReflectionTestUtils.setField(service, "welcomeFlowId", "");
            ReflectionTestUtils.setField(service, "loginOtpTemplateId", "");

            assertThatCode(() -> service.validateTemplates()).doesNotThrowAnyException();
        }
    }

    // ──────────────────── decoupled nudge / escalation dry-run ──────────────────

    /**
     * Verifies the nudge and escalation dry-run guards are independent: muting one
     * must not mute the other. The account master flag ({@code whatsappDryRun}) is
     * kept off so opt-in remains live for the enabled flow.
     */
    @Nested
    class DecoupledDryRun {

        @Test
        void nudgeMuted_escalationStillDelivered() throws Exception {
            ReflectionTestUtils.setField(service, "nudgeDryRun", true);
            ReflectionTestUtils.setField(service, "escalationDryRun", false);
            ReflectionTestUtils.setField(service, "whatsappDryRun", false);
            when(client.execute(contains("createMessageMedia"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"createMessageMedia":{"messageMedia":{"id":"777","url":"https://x/r.pdf"},"errors":[]}}
                    """));
            when(client.execute(contains("createAndSendMessage"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"createAndSendMessage":{"message":{"id":1,"body":"b","isHsm":true},"errors":[]}}
                    """));

            service.sendNudgeHsm(1L, "Ramesh", "02 March 2026");
            service.startNudgeFlow(1L, "Ramesh", "02 March 2026");
            service.sendEscalationHsm(55L, "https://minio.example.com/r.pdf");

            verify(client, never()).execute(contains("sendHsmMessage"), anyMap());
            verify(client, never()).execute(contains("startContactFlow"), anyMap());
            verify(client).execute(contains("createAndSendMessage"), anyMap());
        }

        @Test
        void escalationMuted_nudgeStillDelivered() throws Exception {
            ReflectionTestUtils.setField(service, "nudgeDryRun", false);
            ReflectionTestUtils.setField(service, "escalationDryRun", true);
            ReflectionTestUtils.setField(service, "whatsappDryRun", false);
            when(client.execute(contains("sendHsmMessage"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"sendHsmMessage":{"message":{"id":1,"body":"Hi","isHSM":true},"errors":[]}}
                    """));

            service.sendNudgeHsm(1L, "Ramesh", "02 March 2026");
            String mediaId = service.uploadMedia("https://minio.example.com/r.pdf");
            service.sendEscalationHsm(55L, "https://minio.example.com/r.pdf");

            assertThat(mediaId).isEqualTo("dry-run-media-id");
            verify(client).execute(contains("sendHsmMessage"), anyMap());
            verify(client, never()).execute(contains("createMessageMedia"), anyMap());
            verify(client, never()).execute(contains("createAndSendMessage"), anyMap());
        }

        @Test
        void optIn_staysLive_whenEscalationDeliveryIsEnabled() throws Exception {
            // Opt-in is the prerequisite for delivery, not a message: muting the account operations
            // must not strip the contact id out from under an escalation that is switched live.
            ReflectionTestUtils.setField(service, "nudgeDryRun", true);
            ReflectionTestUtils.setField(service, "escalationDryRun", false);
            ReflectionTestUtils.setField(service, "dailyReportDryRun", true);
            ReflectionTestUtils.setField(service, "whatsappDryRun", true);
            when(client.execute(contains("optinContact"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"optinContact":{"contact":{"id":42},"errors":[]}}
                    """));

            assertThat(service.optIn("919876543210")).isEqualTo(42L);
        }

        /**
         * Regression: the exact production configuration behind "Receiver does not exist" —
         * master/nudge/escalation muted, daily report switched live. Opt-in was gated on the master
         * flag, so it returned 0 and the daily report was sent with {@code receiverId=0}.
         */
        @Test
        void optIn_staysLive_whenOnlyDailyReportDeliveryIsEnabled() throws Exception {
            ReflectionTestUtils.setField(service, "whatsappDryRun", true);
            ReflectionTestUtils.setField(service, "nudgeDryRun", true);
            ReflectionTestUtils.setField(service, "escalationDryRun", true);
            ReflectionTestUtils.setField(service, "dailyReportDryRun", false);
            when(client.execute(contains("optinContact"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"optinContact":{"contact":{"id":16363},"errors":[]}}
                    """));

            assertThat(service.optIn("919876543210")).isEqualTo(16363L);
        }

        @Test
        void optIn_isMuted_onlyWhenEveryPurposeIsDry() {
            ReflectionTestUtils.setField(service, "whatsappDryRun", true);
            ReflectionTestUtils.setField(service, "nudgeDryRun", true);
            ReflectionTestUtils.setField(service, "escalationDryRun", true);
            ReflectionTestUtils.setField(service, "dailyReportDryRun", true);

            assertThat(service.optIn("919876543210")).isEqualTo(0L);
            verifyNoInteractions(client);
        }
    }

    // ─────────────── unresolved contact id must never reach Glific ──────────────

    /**
     * A {@code receiverId} of 0 is what a suppressed or failed opt-in leaves behind. Glific answers it
     * with "Receiver does not exist", which reads like a template fault, so the send is refused before
     * the media upload spends a round-trip.
     */
    @Nested
    class UnresolvedContactId {

        @Test
        void sendDailyReportHsm_refusesZeroContactId_withoutUploadingMedia() {
            ReflectionTestUtils.setField(service, "dailyReportSoTemplateId", "42");

            assertThatThrownBy(() -> service.sendDailyReportHsm(0L, "https://minio.example.com/daily.pdf",
                    "SECTION_OFFICER", LocalDate.of(2026, 8, 19)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sendDailyReportHsm");

            verifyNoInteractions(client);
        }

        @Test
        void sendEscalationHsm_refusesNullContactId_withoutUploadingMedia() {
            assertThatThrownBy(() -> service.sendEscalationHsm(null, "https://minio.example.com/escalation.pdf"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sendEscalationHsm");

            verifyNoInteractions(client);
        }

        @Test
        void sendLoginOtpHsm_refusesZeroContactId() {
            ReflectionTestUtils.setField(service, "loginOtpTemplateId", "otp-tmpl-1");

            assertThatThrownBy(() -> service.sendLoginOtpHsm(0L, "654321"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sendLoginOtpHsm");

            verifyNoInteractions(client);
        }

        @Test
        void sendNudgeHsm_refusesZeroContactId() {
            assertThatThrownBy(() -> service.sendNudgeHsm(0L, "Ramesh", "19 August 2026"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sendNudgeHsm");

            verifyNoInteractions(client);
        }

        @Test
        void sendNudgeHsm_refusesNullContactId() {
            assertThatThrownBy(() -> service.sendNudgeHsm(null, "Ramesh", "19 August 2026"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sendNudgeHsm");

            verifyNoInteractions(client);
        }

        /**
         * A flow start with {@code contactId=0} is answered with a bare {@code success=false}, which the
         * caller turns into a rethrow and a Kafka retry — the least informative way to learn that the
         * operator was never opted in.
         */
        @Test
        void startNudgeFlow_refusesZeroContactId() {
            assertThatThrownBy(() -> service.startNudgeFlow(0L, "Ramesh", "19 August 2026"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("startNudgeFlow");

            verifyNoInteractions(client);
        }

        @Test
        void startNudgeFlow_refusesNullContactId() {
            assertThatThrownBy(() -> service.startNudgeFlow(null, "Ramesh", "19 August 2026"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("startNudgeFlow");

            verifyNoInteractions(client);
        }

        @Test
        void startWelcomeFlow_refusesZeroContactId() {
            assertThatThrownBy(() -> service.startWelcomeFlow(0L, "Ramesh Kumar", "Madhya Pradesh"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("startWelcomeFlow");

            verifyNoInteractions(client);
        }

        @Test
        void startWelcomeFlow_refusesNullContactId_onTheFlowIdOverride() {
            assertThatThrownBy(() -> service.startWelcomeFlow(null, "welcome-flow-456", "Ramesh Kumar", "MP"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("startWelcomeFlow");

            verifyNoInteractions(client);
        }
    }

    /**
     * The contact-id guard sits behind the dry-run return, so a muted purpose stays a silent no-op even
     * when the contact was never opted in — dry-run is the state in which a contact id of 0 is expected.
     */
    @Nested
    class UnresolvedContactIdUnderDryRun {

        @Test
        void sendNudgeHsm_isNoOp_withNoContactId() {
            ReflectionTestUtils.setField(service, "nudgeDryRun", true);

            assertThatCode(() -> service.sendNudgeHsm(0L, "Ramesh", "19 August 2026"))
                    .doesNotThrowAnyException();

            verifyNoInteractions(client);
        }

        @Test
        void startNudgeFlow_isNoOp_withNoContactId() {
            ReflectionTestUtils.setField(service, "nudgeDryRun", true);

            assertThatCode(() -> service.startNudgeFlow(null, "Ramesh", "19 August 2026"))
                    .doesNotThrowAnyException();

            verifyNoInteractions(client);
        }

        @Test
        void startWelcomeFlow_isNoOp_withNoContactId() {
            ReflectionTestUtils.setField(service, "whatsappDryRun", true);

            assertThatCode(() -> service.startWelcomeFlow(0L, "Ramesh Kumar", "MP"))
                    .doesNotThrowAnyException();

            verifyNoInteractions(client);
        }
    }

    // ──────────── media URL must be fetchable by Meta, not just by us ───────────

    /**
     * Glific registers the media URL and Meta downloads it from the public internet. An internal
     * address uploads fine, yields a media id and an accepted send, and fails only inside Meta with
     * {@code (#131053) … blocked by a destination filter} — so the officer receives a document that
     * will not open and nothing on our side says why.
     */
    @Nested
    class MediaBaseUrlMustBePublic {

        @Test
        void validateTemplates_failsFast_whenDailyReportIsLiveButMediaBaseUrlIsInternal() {
            ReflectionTestUtils.setField(service, "whatsappDryRun", true);
            ReflectionTestUtils.setField(service, "nudgeDryRun", true);
            ReflectionTestUtils.setField(service, "escalationDryRun", true);
            ReflectionTestUtils.setField(service, "dailyReportDryRun", false);
            ReflectionTestUtils.setField(service, "dailyReportSoTemplateId", "42");
            ReflectionTestUtils.setField(service, "mediaBaseUrl", "http://192.168.20.143:9000");

            assertThatThrownBy(() -> service.validateTemplates())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("minio.base-url")
                    .hasMessageContaining("MINIO_BASE_URL");
        }

        @Test
        void validateTemplates_failsFast_whenEscalationIsLiveButMediaBaseUrlIsInternal() {
            ReflectionTestUtils.setField(service, "whatsappDryRun", true);
            ReflectionTestUtils.setField(service, "nudgeDryRun", true);
            ReflectionTestUtils.setField(service, "escalationDryRun", false);
            ReflectionTestUtils.setField(service, "dailyReportDryRun", true);
            ReflectionTestUtils.setField(service, "mediaBaseUrl", "http://localhost:9000");

            assertThatThrownBy(() -> service.validateTemplates())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("minio.base-url");
        }

        @Test
        void validateTemplates_passes_withThePublicProductionBaseUrl() {
            ReflectionTestUtils.setField(service, "whatsappDryRun", true);
            ReflectionTestUtils.setField(service, "nudgeDryRun", true);
            ReflectionTestUtils.setField(service, "escalationDryRun", true);
            ReflectionTestUtils.setField(service, "dailyReportDryRun", false);
            ReflectionTestUtils.setField(service, "dailyReportSoTemplateId", "42");
            ReflectionTestUtils.setField(service, "mediaBaseUrl", "https://jalsoochak.jjmbrain.in/minio");

            assertThatCode(() -> service.validateTemplates()).doesNotThrowAnyException();
        }

        /** A localhost MinIO is normal for local and CI runs, where nothing is delivered. */
        @Test
        void validateTemplates_toleratesAnInternalBaseUrl_whenNoDocumentIsEverSent() {
            ReflectionTestUtils.setField(service, "whatsappDryRun", true);
            ReflectionTestUtils.setField(service, "nudgeDryRun", true);
            ReflectionTestUtils.setField(service, "escalationDryRun", true);
            ReflectionTestUtils.setField(service, "dailyReportDryRun", true);
            ReflectionTestUtils.setField(service, "mediaBaseUrl", "http://localhost:9000");

            assertThatCode(() -> service.validateTemplates()).doesNotThrowAnyException();
        }

        @Test
        void sendDailyReportHsm_refusesAnInternalUrl_withoutCallingGlific() {
            ReflectionTestUtils.setField(service, "dailyReportSoTemplateId", "42");

            assertThatThrownBy(() -> service.sendDailyReportHsm(16363L,
                    "http://192.168.20.143:9000/escalation-reports/daily_report.pdf",
                    "SECTION_OFFICER", LocalDate.of(2026, 8, 19)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MINIO_BASE_URL");

            verifyNoInteractions(client);
        }

        @Test
        void sendEscalationHsm_refusesAnInternalUrl_withoutCallingGlific() {
            assertThatThrownBy(() -> service.sendEscalationHsm(55L,
                    "http://minio:9000/escalation-reports/escalation.pdf"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("MINIO_BASE_URL");

            verifyNoInteractions(client);
        }

        @Test
        void sendDailyReportHsm_registersThePublicUrl_andSends() throws Exception {
            ReflectionTestUtils.setField(service, "dailyReportSoTemplateId", "42");
            ReflectionTestUtils.setField(service, "dailyReportCaption", "Daily Water Service Situation Report");
            String publicUrl = "https://jalsoochak.jjmbrain.in/minio/escalation-reports/"
                    + "daily_report_SECTION_OFFICER_16743_2026-08-19.pdf";
            when(client.execute(contains("createMessageMedia"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"createMessageMedia":{"messageMedia":{"id":"28569193"},"errors":[]}}
                    """));
            when(client.execute(contains("createAndSendMessage"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"createAndSendMessage":{"message":{"id":1,"body":"b","isHsm":true},"errors":[]}}
                    """));

            service.sendDailyReportHsm(16743L, publicUrl, "SECTION_OFFICER", LocalDate.of(2026, 8, 19));

            ArgumentCaptor<Map<String, Object>> vars = varsCaptor();
            verify(client).execute(contains("createMessageMedia"), vars.capture());
            @SuppressWarnings("unchecked")
            Map<String, Object> input = (Map<String, Object>) vars.getValue().get("input");
            assertThat(input).containsEntry("url", publicUrl).containsEntry("source_url", publicUrl);
            verify(client).execute(contains("createAndSendMessage"), anyMap());
        }
    }
}