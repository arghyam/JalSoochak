package org.arghyam.jalsoochak.message.channel.provider.glific;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.message.channel.WhatsAppChannel;
import org.arghyam.jalsoochak.message.channel.provider.ReportDeliveryMode;
import org.arghyam.jalsoochak.message.channel.provider.ReportSendOutcome;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSendException;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSendResult;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSendStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
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
 * Unit tests for {@link GlificWhatsAppSender}.
 *
 * <p>Focuses on the two-step escalation flow: {@code sendEscalationHsm} registers the document with
 * {@code createMessageMedia} first, then calls {@code createAndSendMessage} with the returned media ID.
 * Also covers opt-in and nudge HSM delegation, and the failure stage each Glific failure carries.
 */
@ExtendWith(MockitoExtension.class)
class GlificWhatsAppSenderTest {

    @Mock
    private GlificGraphQLClient client;

    private final ObjectMapper mapper = new ObjectMapper();

    private final SettingsFixture settings = new SettingsFixture();

    /**
     * The adapter's settings as mutable fields, named after the adapter's former {@code @Value} fields,
     * so a test can change one before calling {@link #sender()}. Anything left unset is null or false,
     * as it was when these tests wrote those fields directly.
     */
    private static final class SettingsFixture {
        boolean whatsappDryRun;
        boolean nudgeDryRun;
        boolean escalationDryRun;
        boolean dailyReportDryRun;
        boolean weeklyReportDryRun;
        String nudgeTemplateId;
        String escalationTemplateId;
        String loginOtpTemplateId;
        String dailyReportSoTemplateId;
        String dailyReportSdoTemplateId;
        String dailyReportSoLinkTemplateId;
        String dailyReportSdoLinkTemplateId;
        String weeklyReportSoLinkTemplateId;
        String weeklyReportSdoLinkTemplateId;
        String nudgeFlowId;
        String welcomeFlowId;
        String mediaBaseUrl;
        String escalationCaption;
        String escalationThumbnail;
        String dailyReportCaption;
        String dailyReportDeliveryMode;
        String dailyReportLinkButtonBaseUrl;

        GlificWhatsAppSettings toSettings() {
            return new GlificWhatsAppSettings(
                    new GlificWhatsAppSettings.DryRun(whatsappDryRun, nudgeDryRun, escalationDryRun,
                            dailyReportDryRun, weeklyReportDryRun),
                    new GlificWhatsAppSettings.Templates(
                            nudgeTemplateId,
                            escalationTemplateId,
                            loginOtpTemplateId,
                            dailyReportSoTemplateId,
                            dailyReportSdoTemplateId,
                            dailyReportSoLinkTemplateId,
                            dailyReportSdoLinkTemplateId,
                            weeklyReportSoLinkTemplateId,
                            weeklyReportSdoLinkTemplateId),
                    new GlificWhatsAppSettings.Flows(nudgeFlowId, welcomeFlowId),
                    new GlificWhatsAppSettings.Media(
                            mediaBaseUrl, escalationCaption, escalationThumbnail, dailyReportCaption),
                    dailyReportDeliveryMode,
                    dailyReportLinkButtonBaseUrl);
        }
    }

    /** A sender built from the current {@link #settings}. Stateless, so a fresh one per call is equivalent. */
    private GlificWhatsAppSender sender() {
        return new GlificWhatsAppSender(client, mapper, settings.toSettings());
    }

    @BeforeEach
    void setUp() {
        settings.nudgeTemplateId = "nudge-tmpl-1";
        settings.nudgeFlowId = "flow-123";
        settings.welcomeFlowId = "welcome-flow-456";
        settings.escalationTemplateId = "2";   // must be numeric for Integer.parseInt
        settings.escalationCaption = "Escalations";
        settings.escalationThumbnail = "";
        // Glific hands media URLs to Meta, which fetches them from the public internet, so every
        // sending path now requires a publicly reachable prefix.
        settings.mediaBaseUrl = "https://jalsoochak.jjmbrain.in/minio";
        // Weekly reports ship suppressed until their Meta templates are approved, which is also the
        // production default. Tests that exercise weekly delivery turn it on explicitly.
        settings.weeklyReportDryRun = true;
    }

    // ──────────────────────────── optIn ────────────────────────────────────────

    @Test
    void optIn_returnsContactId_fromGlificResponse() throws Exception {
        JsonNode response = mapper.readTree(
                """
                {"optinContact":{"contact":{"id":42}}}
                """);
        when(client.execute(contains("optinContact"), anyMap())).thenReturn(response);

        Long contactId = sender().optIn("919876543210");

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

        sender().sendNudgeHsm(99L, "Ramesh", "02 March 2026");

        ArgumentCaptor<Map<String, Object>> varsCaptor = varsCaptor();
        verify(client).execute(contains("sendHsmMessage"), varsCaptor.capture());

        Map<String, Object> vars = varsCaptor.getValue();
        assertThat(vars.get("templateId")).isEqualTo("nudge-tmpl-1");
        assertThat(vars.get("receiverId")).isEqualTo(99L);
        assertThat(vars.get("parameters")).isEqualTo(List.of("Ramesh", "02 March 2026"));
    }

    // ──────────────────────── sendEscalationHsm ────────────────────────────────

    @Test
    void sendEscalationHsm_registersTheDocumentUrlAsTemplateMedia() throws Exception {
        when(client.execute(contains("createMessageMedia"), anyMap())).thenReturn(mapper.readTree("""
                {"createMessageMedia":{"messageMedia":{"id":"777","url":"https://example.com/r.pdf"},"errors":[]}}
                """));
        when(client.execute(contains("createAndSendMessage"), anyMap())).thenReturn(mapper.readTree("""
                {"createAndSendMessage":{"message":{"id":2,"body":"body","isHsm":true},"errors":[]}}
                """));

        sender().sendEscalationHsm(55L, "https://example.com/r.pdf");

        ArgumentCaptor<Map<String, Object>> varsCaptor = varsCaptor();
        verify(client).execute(contains("createMessageMedia"), varsCaptor.capture());

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) varsCaptor.getValue().get("input");
        assertThat(input.get("url")).isEqualTo("https://example.com/r.pdf");
        assertThat(input.get("source_url")).isEqualTo("https://example.com/r.pdf");
        assertThat(input.get("caption")).isEqualTo("Escalations");
        assertThat(input.get("isTemplateMedia")).isEqualTo(true);
    }

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

        sender().sendEscalationHsm(55L, "https://minio.example.com/r.pdf");

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

        sender().sendEscalationHsm(77L, "https://minio.example.com/report.pdf");

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
                .thenThrow(new RuntimeException("Report URL unreachable"));

        assertThatThrownBy(() ->
                sender().sendEscalationHsm(88L, "https://minio.example.com/r.pdf"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Report URL unreachable");

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

        sender().sendEscalationHsm(11L, "https://minio.example.com/r.pdf");

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

        sender().startNudgeFlow(42L, "Ramesh", "06 March 2026");

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

        sender().startNudgeFlow(42L, "Ramesh", "06 March 2026");

        ArgumentCaptor<Map<String, Object>> varsCaptor = varsCaptor();
        verify(client).execute(contains("startContactFlow"), varsCaptor.capture());

        String defaultResults = (String) varsCaptor.getValue().get("defaultResults");
        JsonNode parsed = mapper.readTree(defaultResults);
        assertThat(parsed.get("name").asText()).isEqualTo("Ramesh");
        assertThat(parsed.get("date").asText()).isEqualTo("06 March 2026");
    }

    @Test
    void startNudgeFlow_throwsIllegalState_whenFlowIdNotConfigured() {
        settings.nudgeFlowId = "";

        assertThatThrownBy(() -> sender().startNudgeFlow(42L, "Ramesh", "06 March 2026"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("whatsapp.flow.nudge-id");
    }

    @Test
    void startNudgeFlow_throwsException_whenGlificReturnsErrors() throws Exception {
        JsonNode response = mapper.readTree("""
                {"startContactFlow":{"success":false,"errors":[{"key":"flow","message":"not found"}]}}
                """);
        when(client.execute(contains("startContactFlow"), anyMap())).thenReturn(response);

        assertThatThrownBy(() -> sender().startNudgeFlow(42L, "Ramesh", "06 March 2026"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("startContactFlow");
    }

    @Test
    void startNudgeFlow_throwsException_whenSuccessIsFalse() throws Exception {
        JsonNode response = mapper.readTree("""
                {"startContactFlow":{"success":false,"errors":[]}}
                """);
        when(client.execute(contains("startContactFlow"), anyMap())).thenReturn(response);

        assertThatThrownBy(() -> sender().startNudgeFlow(42L, "Ramesh", "06 March 2026"))
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

        assertThatThrownBy(() -> sender().optIn("91invalid"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("optinContact");
    }

    @Test
    void sendNudgeHsm_throwsException_whenGraphQLErrorsReturned() throws Exception {
        JsonNode response = mapper.readTree("""
                {"sendHsmMessage":{"message":null,"errors":[{"key":"template","message":"not found"}]}}
                """);
        when(client.execute(contains("sendHsmMessage"), anyMap())).thenReturn(response);

        assertThatThrownBy(() -> sender().sendNudgeHsm(1L, "Ramesh", "04 March 2026"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("sendHsmMessage");
    }

    @Test
    void sendEscalationHsm_failsAtMediaRegistration_whenGlificRejectsTheMedia() throws Exception {
        JsonNode response = mapper.readTree("""
                {"createMessageMedia":{"messageMedia":null,"errors":[{"key":"url","message":"unreachable"}]}}
                """);
        when(client.execute(contains("createMessageMedia"), anyMap())).thenReturn(response);

        assertThatThrownBy(() -> sender().sendEscalationHsm(22L, "https://example.com/r.pdf"))
                .isInstanceOfSatisfying(WhatsAppSendException.class, e -> {
                    assertThat(e).hasMessageContaining("createMessageMedia");
                    assertThat(e.getStage()).isEqualTo(WhatsAppSendStage.MEDIA_REGISTER);
                    assertThat(e.getErrorKey()).isEqualTo("url");
                });
        verify(client, never()).execute(contains("createAndSendMessage"), anyMap());
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
                sender().sendEscalationHsm(22L, "https://minio.example.com/r.pdf"))
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

        sender().startWelcomeFlow(55L, "Ramesh Kumar", "Madhya Pradesh");

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

        sender().startWelcomeFlow(55L, "welcome-flow-456", "Ramesh Kumar", "Madhya Pradesh");

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

        sender().startWelcomeFlow(55L, "welcome-flow-456", null, null);

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

        assertThatThrownBy(() -> sender().startWelcomeFlow(55L, "Ramesh", "MP"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("startContactFlow");
    }

    @Test
    void startWelcomeFlow_throwsException_whenSuccessIsFalse() throws Exception {
        JsonNode response = mapper.readTree("""
                {"startContactFlow":{"success":false,"errors":[]}}
                """);
        when(client.execute(contains("startContactFlow"), anyMap())).thenReturn(response);

        assertThatThrownBy(() -> sender().startWelcomeFlow(55L, "Ramesh", "MP"))
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

        sender().updateContactLanguage(42L, 2);

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

        assertThatThrownBy(() -> sender().updateContactLanguage(99L, 3))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("updateContact");
    }

    // ──────────────────────────── sendLoginOtpHsm ──────────────────────────────

    @Test
    void sendLoginOtpHsm_callsSendHsmMutation_withOtpParameter() throws Exception {
        settings.loginOtpTemplateId = "otp-tmpl-1";
        JsonNode response = mapper.readTree("""
                {"sendHsmMessage":{"message":{"id":5,"body":"otp","isHSM":true},"errors":[]}}
                """);
        when(client.execute(contains("sendHsmMessage"), anyMap())).thenReturn(response);

        sender().sendLoginOtpHsm(11L, "654321");

        ArgumentCaptor<Map<String, Object>> varsCaptor = varsCaptor();
        verify(client).execute(contains("sendHsmMessage"), varsCaptor.capture());
        Map<String, Object> vars = varsCaptor.getValue();
        assertThat(vars.get("templateId")).isEqualTo("otp-tmpl-1");
        assertThat(vars.get("receiverId")).isEqualTo(11L);
        assertThat(vars.get("parameters")).isEqualTo(List.of("654321"));
    }

    @Test
    void sendLoginOtpHsm_throwsException_whenTemplateIdNotConfigured() {
        settings.loginOtpTemplateId = "";

        assertThatThrownBy(() -> sender().sendLoginOtpHsm(11L, "000000"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("login-otp-id");
    }

    @Test
    void sendLoginOtpHsm_throwsException_whenGraphQLErrorsReturned() throws Exception {
        settings.loginOtpTemplateId = "otp-tmpl-1";
        JsonNode response = mapper.readTree("""
                {"sendHsmMessage":{"message":null,"errors":[{"key":"contact","message":"blocked"}]}}
                """);
        when(client.execute(contains("sendHsmMessage"), anyMap())).thenReturn(response);

        assertThatThrownBy(() -> sender().sendLoginOtpHsm(11L, "654321"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("sendHsmMessage");
    }

    // ────────────── required ids name their variable (@PostConstruct) ──────────────

    /**
     * Ops set environment variables, not properties, and the {@code WHATSAPP_*} names replaced the old ones
     * with no fallback — so a refused boot must name the variable that was missed.
     */
    @ParameterizedTest
    @CsvSource({
            "nudgeFlowId,          whatsapp.flow.nudge-id,          WHATSAPP_NUDGE_FLOW_ID",
            "escalationTemplateId, whatsapp.template.escalation-id, WHATSAPP_ESCALATION_TEMPLATE_ID",
            "welcomeFlowId,        whatsapp.flow.welcome-id,        WHATSAPP_WELCOME_FLOW_ID",
            "loginOtpTemplateId,   whatsapp.template.login-otp-id,  WHATSAPP_LOGIN_OTP_TEMPLATE_ID"})
    void validateTemplates_namesThePropertyAndTheVariable_whenARequiredIdIsBlank(
            String fixtureField, String property, String variable) {
        settings.loginOtpTemplateId = "otp-tmpl-1";
        settings.dailyReportDryRun = true;
        ReflectionTestUtils.setField(settings, fixtureField, "");

        assertThatThrownBy(() -> sender().validateTemplates())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(property + " (" + variable + ")");
    }

    // ────────────── daily-report template-id validation (@PostConstruct) ────────────

    @Test
    void validateTemplates_throwsWhenDailyReportSoTemplateIdNotNumeric() {
        // Delivery enabled (no dry-run) and the SO template id is present but non-numeric —
        // sendDailyReportHsm would fail at Integer.parseInt, so we must fail fast at startup.
        settings.loginOtpTemplateId = "otp-tmpl-1";
        settings.dailyReportSoTemplateId = "not-a-number";

        assertThatThrownBy(() -> sender().validateTemplates())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("daily-report-so-id")
                .hasMessageContaining("WHATSAPP_DAILY_REPORT_SO_TEMPLATE_ID");
    }

    @Test
    void validateTemplates_throwsWhenDailyReportSdoTemplateIdNotNumeric() {
        settings.loginOtpTemplateId = "otp-tmpl-1";
        settings.dailyReportSoTemplateId = "42";
        settings.dailyReportSdoTemplateId = "abc";

        assertThatThrownBy(() -> sender().validateTemplates())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("daily-report-sdo-id")
                .hasMessageContaining("WHATSAPP_DAILY_REPORT_SDO_TEMPLATE_ID");
    }

    @Test
    void validateTemplates_throwsWhenDailyReportLiveButSoTemplateIdMissing_evenIfEveryOtherPurposeIsDry() {
        // The all-dry short circuit used to ignore the daily-report flag, so this configuration
        // started up without ever validating the template that the live purpose needs.
        settings.whatsappDryRun = true;
        settings.nudgeDryRun = true;
        settings.escalationDryRun = true;
        settings.dailyReportDryRun = false;
        settings.dailyReportSoTemplateId = "";

        assertThatThrownBy(() -> sender().validateTemplates())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("daily-report-so-id")
                .hasMessageContaining("WHATSAPP_DAILY_REPORT_SO_TEMPLATE_ID");
    }

    @Test
    void validateTemplates_passesWhenDailyReportTemplateIdsAreNumeric() {
        settings.loginOtpTemplateId = "otp-tmpl-1";
        settings.dailyReportSoTemplateId = "42";
        settings.dailyReportSdoTemplateId = "43";

        assertThatCode(() -> sender().validateTemplates()).doesNotThrowAnyException();
    }

    // ────────────────────────────── helpers ────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> varsCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    // ─────────────────── daily report document name (WhatsApp) ─────────────────

    @Test
    void dailyReportDocumentName_appendsTheReportDataDate() {
        settings.dailyReportCaption = "Daily Water Service Situation Report";

        // A report delivered on 14 Aug covers 13 Aug, and is named for the day it describes.
        assertThat(sender().dailyReportDocumentName(LocalDate.of(2026, 8, 13)))
                .isEqualTo("Daily Water Service Situation Report 13-08-2026");
        // Single-digit day and month stay zero-padded.
        assertThat(sender().dailyReportDocumentName(LocalDate.of(2026, 1, 5)))
                .isEqualTo("Daily Water Service Situation Report 05-01-2026");
    }

    @Test
    void dailyReportDocumentName_withoutADate_fallsBackToTheBareCaption() {
        settings.dailyReportCaption = "Daily Water Service Situation Report";

        assertThat(sender().dailyReportDocumentName(null))
                .isEqualTo("Daily Water Service Situation Report");
    }

    @Test
    void sendDailyReportHsm_uploadsMediaUnderTheDatedDocumentName() throws Exception {
        // The createMessageMedia caption is what Glific surfaces as the document's filename in
        // WhatsApp, so this is the assertion that pins the recipient-visible name.
        settings.dailyReportCaption = "Daily Water Service Situation Report";
        settings.dailyReportSoTemplateId = "42";
        settings.escalationThumbnail = "";
        when(client.execute(contains("createMessageMedia"), anyMap()))
                .thenReturn(mapper.readTree("{\"createMessageMedia\":{\"messageMedia\":{\"id\":\"77\"}}}"));
        when(client.execute(contains("createAndSendMessage"), anyMap()))
                .thenReturn(mapper.readTree("{\"createAndSendMessage\":{\"message\":{\"id\":\"1\"}}}"));

        sender().sendDailyReportHsm(555L, "https://minio.example.com/report.pdf", "SECTION_OFFICER",
                LocalDate.of(2026, 8, 13), "Ramesh Kumar");

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
            settings.whatsappDryRun = true;
            settings.nudgeDryRun = true;
            settings.escalationDryRun = true;
            settings.dailyReportDryRun = true;
            settings.weeklyReportDryRun = true;
        }

        @Test
        void optIn_returnsZero_andDoesNotCallClient() {
            Long result = sender().optIn("919876543210");

            assertThat(result).isEqualTo(0L);
            verifyNoInteractions(client);
        }

        @Test
        void sendNudgeHsm_isNoOp() {
            sender().sendNudgeHsm(42L, "Ramesh", "22 March 2026");

            verifyNoInteractions(client);
        }

        @Test
        void sendEscalationHsm_isNoOp() {
            sender().sendEscalationHsm(55L, "https://minio.example.com/r.pdf");

            verifyNoInteractions(client);
        }

        @Test
        void startNudgeFlow_isNoOp() {
            sender().startNudgeFlow(42L, "Ramesh", "22 March 2026");

            verifyNoInteractions(client);
        }

        @Test
        void startWelcomeFlow_isNoOp() {
            sender().startWelcomeFlow(55L, "Ramesh", "MP");

            verifyNoInteractions(client);
        }

        @Test
        void updateContactLanguage_isNoOp() {
            sender().updateContactLanguage(42L, 2);

            verifyNoInteractions(client);
        }

        @Test
        void sendLoginOtpHsm_isNoOp() {
            settings.loginOtpTemplateId = "otp-tmpl-1";

            sender().sendLoginOtpHsm(99L, "123456");

            verifyNoInteractions(client);
        }

        @Test
        void sendDailyReportHsm_isNoOp() {
            sender().sendDailyReportHsm(0L, "https://minio.example.com/daily.pdf", "SECTION_OFFICER",
                    LocalDate.of(2026, 8, 19), "Ramesh Kumar");

            verifyNoInteractions(client);
        }

        @Test
        void validateTemplates_doesNotThrowWhenTemplateIdsBlank() {
            settings.nudgeFlowId = "";
            settings.escalationTemplateId = "";
            settings.welcomeFlowId = "";
            settings.loginOtpTemplateId = "";

            assertThatCode(() -> sender().validateTemplates()).doesNotThrowAnyException();
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
            settings.nudgeDryRun = true;
            settings.escalationDryRun = false;
            settings.whatsappDryRun = false;
            when(client.execute(contains("createMessageMedia"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"createMessageMedia":{"messageMedia":{"id":"777","url":"https://x/r.pdf"},"errors":[]}}
                    """));
            when(client.execute(contains("createAndSendMessage"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"createAndSendMessage":{"message":{"id":1,"body":"b","isHsm":true},"errors":[]}}
                    """));

            sender().sendNudgeHsm(1L, "Ramesh", "02 March 2026");
            sender().startNudgeFlow(1L, "Ramesh", "02 March 2026");
            sender().sendEscalationHsm(55L, "https://minio.example.com/r.pdf");

            verify(client, never()).execute(contains("sendHsmMessage"), anyMap());
            verify(client, never()).execute(contains("startContactFlow"), anyMap());
            verify(client).execute(contains("createAndSendMessage"), anyMap());
        }

        @Test
        void escalationMuted_nudgeStillDelivered() throws Exception {
            settings.nudgeDryRun = false;
            settings.escalationDryRun = true;
            settings.whatsappDryRun = false;
            when(client.execute(contains("sendHsmMessage"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"sendHsmMessage":{"message":{"id":1,"body":"Hi","isHSM":true},"errors":[]}}
                    """));

            sender().sendNudgeHsm(1L, "Ramesh", "02 March 2026");
            sender().sendEscalationHsm(55L, "https://minio.example.com/r.pdf");

            verify(client).execute(contains("sendHsmMessage"), anyMap());
            verify(client, never()).execute(contains("createMessageMedia"), anyMap());
            verify(client, never()).execute(contains("createAndSendMessage"), anyMap());
        }

        @Test
        void optIn_staysLive_whenEscalationDeliveryIsEnabled() throws Exception {
            // Opt-in is the prerequisite for delivery, not a message: muting the account operations
            // must not strip the contact id out from under an escalation that is switched live.
            settings.nudgeDryRun = true;
            settings.escalationDryRun = false;
            settings.dailyReportDryRun = true;
            settings.whatsappDryRun = true;
            when(client.execute(contains("optinContact"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"optinContact":{"contact":{"id":42},"errors":[]}}
                    """));

            assertThat(sender().optIn("919876543210")).isEqualTo(42L);
        }

        /**
         * Regression: the exact production configuration behind "Receiver does not exist" —
         * master/nudge/escalation muted, daily report switched live. Opt-in was gated on the master
         * flag, so it returned 0 and the daily report was sent with {@code receiverId=0}.
         */
        @Test
        void optIn_staysLive_whenOnlyDailyReportDeliveryIsEnabled() throws Exception {
            settings.whatsappDryRun = true;
            settings.nudgeDryRun = true;
            settings.escalationDryRun = true;
            settings.dailyReportDryRun = false;
            when(client.execute(contains("optinContact"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"optinContact":{"contact":{"id":16363},"errors":[]}}
                    """));

            assertThat(sender().optIn("919876543210")).isEqualTo(16363L);
        }

        @Test
        void optIn_isMuted_onlyWhenEveryPurposeIsDry() {
            settings.whatsappDryRun = true;
            settings.nudgeDryRun = true;
            settings.escalationDryRun = true;
            settings.dailyReportDryRun = true;

            assertThat(sender().optIn("919876543210")).isEqualTo(0L);
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
            settings.dailyReportSoTemplateId = "42";

            assertThatThrownBy(() -> sender().sendDailyReportHsm(0L, "https://minio.example.com/daily.pdf",
                    "SECTION_OFFICER", LocalDate.of(2026, 8, 19), "Ramesh Kumar"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sendDailyReportHsm");

            verifyNoInteractions(client);
        }

        @Test
        void sendEscalationHsm_refusesNullContactId_withoutUploadingMedia() {
            assertThatThrownBy(() -> sender().sendEscalationHsm(null, "https://minio.example.com/escalation.pdf"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sendEscalationHsm");

            verifyNoInteractions(client);
        }

        @Test
        void sendLoginOtpHsm_refusesZeroContactId() {
            settings.loginOtpTemplateId = "otp-tmpl-1";

            assertThatThrownBy(() -> sender().sendLoginOtpHsm(0L, "654321"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sendLoginOtpHsm");

            verifyNoInteractions(client);
        }

        @Test
        void sendNudgeHsm_refusesZeroContactId() {
            assertThatThrownBy(() -> sender().sendNudgeHsm(0L, "Ramesh", "19 August 2026"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sendNudgeHsm");

            verifyNoInteractions(client);
        }

        @Test
        void sendNudgeHsm_refusesNullContactId() {
            assertThatThrownBy(() -> sender().sendNudgeHsm(null, "Ramesh", "19 August 2026"))
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
            assertThatThrownBy(() -> sender().startNudgeFlow(0L, "Ramesh", "19 August 2026"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("startNudgeFlow");

            verifyNoInteractions(client);
        }

        @Test
        void startNudgeFlow_refusesNullContactId() {
            assertThatThrownBy(() -> sender().startNudgeFlow(null, "Ramesh", "19 August 2026"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("startNudgeFlow");

            verifyNoInteractions(client);
        }

        @Test
        void startWelcomeFlow_refusesZeroContactId() {
            assertThatThrownBy(() -> sender().startWelcomeFlow(0L, "Ramesh Kumar", "Madhya Pradesh"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("startWelcomeFlow");

            verifyNoInteractions(client);
        }

        @Test
        void startWelcomeFlow_refusesNullContactId_onTheFlowIdOverride() {
            assertThatThrownBy(() -> sender().startWelcomeFlow(null, "welcome-flow-456", "Ramesh Kumar", "MP"))
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
            settings.nudgeDryRun = true;

            assertThatCode(() -> sender().sendNudgeHsm(0L, "Ramesh", "19 August 2026"))
                    .doesNotThrowAnyException();

            verifyNoInteractions(client);
        }

        @Test
        void startNudgeFlow_isNoOp_withNoContactId() {
            settings.nudgeDryRun = true;

            assertThatCode(() -> sender().startNudgeFlow(null, "Ramesh", "19 August 2026"))
                    .doesNotThrowAnyException();

            verifyNoInteractions(client);
        }

        @Test
        void startWelcomeFlow_isNoOp_withNoContactId() {
            settings.whatsappDryRun = true;

            assertThatCode(() -> sender().startWelcomeFlow(0L, "Ramesh Kumar", "MP"))
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
            settings.whatsappDryRun = true;
            settings.nudgeDryRun = true;
            settings.escalationDryRun = true;
            settings.dailyReportDryRun = false;
            settings.dailyReportSoTemplateId = "42";
            settings.mediaBaseUrl = "http://192.168.20.143:9000";

            assertThatThrownBy(() -> sender().validateTemplates())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("storage.public-base-url")
                    .hasMessageContaining("STORAGE_PUBLIC_BASE_URL");
        }

        @Test
        void validateTemplates_failsFast_whenEscalationIsLiveButMediaBaseUrlIsInternal() {
            settings.whatsappDryRun = true;
            settings.nudgeDryRun = true;
            settings.escalationDryRun = false;
            settings.dailyReportDryRun = true;
            settings.mediaBaseUrl = "http://localhost:9000";

            assertThatThrownBy(() -> sender().validateTemplates())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("storage.public-base-url");
        }

        @Test
        void validateTemplates_passes_withThePublicProductionBaseUrl() {
            settings.whatsappDryRun = true;
            settings.nudgeDryRun = true;
            settings.escalationDryRun = true;
            settings.dailyReportDryRun = false;
            settings.dailyReportSoTemplateId = "42";
            settings.mediaBaseUrl = "https://jalsoochak.jjmbrain.in/minio";

            assertThatCode(() -> sender().validateTemplates()).doesNotThrowAnyException();
        }

        @Test
        void validateTemplates_failsFast_whenOnlyTheWeeklyReportIsLiveButMediaBaseUrlIsInternal() {
            // The weekly report is LINK-only, so Meta never downloads the file — but the same prefix
            // is what the officer's phone opens and what is frozen into the approved template. The
            // gate used to consult only the daily and escalation flags, so a weekly-only deployment
            // started happily and delivered buttons that lead nowhere.
            settings.whatsappDryRun = true;
            settings.nudgeDryRun = true;
            settings.escalationDryRun = true;
            settings.dailyReportDryRun = true;
            settings.weeklyReportDryRun = false;
            settings.weeklyReportSoLinkTemplateId = "77";
            settings.mediaBaseUrl = "http://192.168.20.143:9000";

            assertThatThrownBy(() -> sender().validateTemplates())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("storage.public-base-url")
                    .hasMessageContaining("STORAGE_PUBLIC_BASE_URL");
        }

        @Test
        void validateTemplates_passes_whenOnlyTheWeeklyReportIsLiveAndTheBaseUrlIsPublic() {
            settings.whatsappDryRun = true;
            settings.nudgeDryRun = true;
            settings.escalationDryRun = true;
            settings.dailyReportDryRun = true;
            settings.weeklyReportDryRun = false;
            settings.weeklyReportSoLinkTemplateId = "77";
            settings.mediaBaseUrl = "https://jalsoochak.jjmbrain.in/minio";

            assertThatCode(() -> sender().validateTemplates()).doesNotThrowAnyException();
        }

        /** A localhost object store is normal for local and CI runs, where nothing is delivered. */
        @Test
        void validateTemplates_toleratesAnInternalBaseUrl_whenNoDocumentIsEverSent() {
            settings.whatsappDryRun = true;
            settings.nudgeDryRun = true;
            settings.escalationDryRun = true;
            settings.dailyReportDryRun = true;
            settings.weeklyReportDryRun = true;
            settings.mediaBaseUrl = "http://localhost:9000";

            assertThatCode(() -> sender().validateTemplates()).doesNotThrowAnyException();
        }

        @Test
        void sendDailyReportHsm_refusesAnInternalUrl_withoutCallingGlific() {
            settings.dailyReportSoTemplateId = "42";

            assertThatThrownBy(() -> sender().sendDailyReportHsm(16363L,
                    "http://192.168.20.143:9000/escalation-reports/daily_report.pdf",
                    "SECTION_OFFICER", LocalDate.of(2026, 8, 19), "Ramesh Kumar"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("STORAGE_PUBLIC_BASE_URL");

            verifyNoInteractions(client);
        }

        @Test
        void sendEscalationHsm_refusesAnInternalUrl_withoutCallingGlific() {
            assertThatThrownBy(() -> sender().sendEscalationHsm(55L,
                    "http://minio:9000/escalation-reports/escalation.pdf"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("STORAGE_PUBLIC_BASE_URL");

            verifyNoInteractions(client);
        }

        @Test
        void sendDailyReportHsm_registersThePublicUrl_andSends() throws Exception {
            settings.dailyReportSoTemplateId = "42";
            settings.dailyReportCaption = "Daily Water Service Situation Report";
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

            sender().sendDailyReportHsm(16743L, publicUrl, "SECTION_OFFICER", LocalDate.of(2026, 8, 19),
                    "Ramesh Kumar");

            ArgumentCaptor<Map<String, Object>> vars = varsCaptor();
            verify(client).execute(contains("createMessageMedia"), vars.capture());
            @SuppressWarnings("unchecked")
            Map<String, Object> input = (Map<String, Object>) vars.getValue().get("input");
            assertThat(input).containsEntry("url", publicUrl).containsEntry("source_url", publicUrl);
            verify(client).execute(contains("createAndSendMessage"), anyMap());
        }
    }

    // ─────────────────── daily report: LINK delivery mode ──────────────────────

    /**
     * {@code notifications.daily-report.delivery-mode=LINK} sends a text HSM whose "View Report"
     * button carries the report's path, instead of a document HSM Meta has to download itself. The
     * behaviour that matters is that no media is registered at all — that round trip is exactly what
     * fails with {@code (#131053)} behind the India-only firewall in front of the production object store.
     */
@Nested
    @DisplayName("weekly report")
    class WeeklyReport {

        private static final String PUBLIC_BASE = "https://jalsoochak.jjmbrain.in/minio";
        private static final String OBJECT_PATH =
                "weekly-water-reports/SO/2026-07-13_to_2026-07-19/weekly_water_report_SECTION_OFFICER_21343_2026-07-13_to_2026-07-19.pdf";
        private static final String PUBLIC_URL = PUBLIC_BASE + "/" + OBJECT_PATH;
        private static final LocalDate WEEK_START = LocalDate.of(2026, 7, 13);

        @BeforeEach
        void enableWeeklyDelivery() {
            settings.weeklyReportDryRun = false;
            settings.weeklyReportSoLinkTemplateId = "7001";
            settings.mediaBaseUrl = PUBLIC_BASE;
        }

        private void stubSendHsm() throws Exception {
            when(client.execute(contains("sendHsmMessage"), anyMap())).thenReturn(mapper.readTree("""
                    {"sendHsmMessage":{"message":{"id":1,"body":"b","isHSM":true},"errors":[]}}
                    """));
        }

        @Test
        void sendsOneHsmAndNeverRegistersMedia() throws Exception {
            // No DOCUMENT path at all: registering media would make Meta fetch the PDF through the
            // India-only firewall, which is the failure the link mode exists to avoid.
            stubSendHsm();

            sender().sendWeeklyReportHsm(21343L, PUBLIC_URL, "SECTION_OFFICER", WEEK_START, "Binod Nimoli");

            verify(client).execute(contains("sendHsmMessage"), anyMap());
            verify(client, never()).execute(contains("createMessageMedia"), anyMap());
            verify(client, never()).execute(contains("createAndSendMessage"), anyMap());
        }

        @Test
        @SuppressWarnings("unchecked")
        void putsTheUrlSuffixLastInTheParameterList() throws Exception {
            // Glific flattens the list in order of occurrence: body variables first, the button's URL
            // suffix last. Reordering them sends the officer a message addressed to a date.
            stubSendHsm();

            sender().sendWeeklyReportHsm(21343L, PUBLIC_URL, "SECTION_OFFICER", WEEK_START, "Binod Nimoli");

            ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
            verify(client).execute(contains("sendHsmMessage"), vars.capture());
            List<String> params = (List<String>) vars.getValue().get("parameters");
            assertThat(params).containsExactly("Binod Nimoli", "13-07-2026", OBJECT_PATH);
        }

        @Test
        void prefersTheSdoTemplateWhenOneIsConfigured() throws Exception {
            stubSendHsm();
            settings.weeklyReportSdoLinkTemplateId = "7002";

            WhatsAppSendResult result = sender().sendWeeklyReportHsm(5521L, PUBLIC_URL,
                    "SUB_DIVISIONAL_OFFICER", WEEK_START, "Bharat Sharma");

            assertThat(result.templateId()).isEqualTo("7002");
        }

        @Test
        void fallsBackToTheSoTemplateWhenNoSdoTemplateIsApproved() throws Exception {
            stubSendHsm();
            settings.weeklyReportSdoLinkTemplateId = "";

            WhatsAppSendResult result = sender().sendWeeklyReportHsm(5521L, PUBLIC_URL,
                    "SUB_DIVISIONAL_OFFICER", WEEK_START, "Bharat Sharma");

            assertThat(result.templateId()).isEqualTo("7001");
        }

        @Test
        void refusesAUrlFromSomeOtherHostRatherThanSendingADeadButton() throws Exception {
            // Meta appends the remainder to the template's frozen prefix verbatim, so a foreign URL
            // yields a button pointing nowhere — and Glific would accept the send regardless.
            assertThatThrownBy(() -> sender().sendWeeklyReportHsm(21343L,
                    "https://elsewhere.example.com/x.pdf", "SECTION_OFFICER", WEEK_START, "Binod"))
                    .isInstanceOf(IllegalStateException.class);

            verify(client, never()).execute(anyString(), anyMap());
        }

        @Test
        void requiresTheWeekStartSinceItIsATemplateVariable() {
            assertThatThrownBy(() -> sender().sendWeeklyReportHsm(21343L, PUBLIC_URL,
                    "SECTION_OFFICER", null, "Binod"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void suppressesTheSendWhileInDryRun() {
            settings.weeklyReportDryRun = true;

            WhatsAppSendResult result = sender().sendWeeklyReportHsm(21343L, PUBLIC_URL,
                    "SECTION_OFFICER", WEEK_START, "Binod");

            assertThat(result.messageId()).isNull();
            assertThat(sender().isWeeklyReportDeliveryEnabled()).isFalse();
            verifyNoInteractions(client);
        }

        @Test
        void startupFailsWhenDeliveryIsLiveButNoTemplateIsConfigured() {
            // Otherwise the job runs every Monday, builds and uploads a PDF, then fails per message —
            // discovered from the logs rather than at deploy, with officers receiving nothing.
            settings.weeklyReportSoLinkTemplateId = "";
            settings.loginOtpTemplateId = "otp-1";
            settings.dailyReportDryRun = true;

            assertThatThrownBy(() -> sender().validateTemplates())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("weekly-report-so-link-id")
                    .hasMessageContaining("WHATSAPP_WEEKLY_REPORT_SO_LINK_TEMPLATE_ID");
        }

        @Test
        void startupPassesWhenTheTemplateIsConfigured() {
            settings.loginOtpTemplateId = "otp-1";
            settings.dailyReportDryRun = true;

            sender().validateTemplates();
        }
    }

        @Nested
    class LinkDeliveryMode {

        private static final String PUBLIC_BASE = "https://jalsoochak.jjmbrain.in/minio";
        private static final String OBJECT_PATH =
                "escalation-reports/daily_report_SECTION_OFFICER_16714_2026-08-19.pdf";
        private static final String PUBLIC_URL = PUBLIC_BASE + "/" + OBJECT_PATH;

        @BeforeEach
        void enableLinkMode() {
            settings.dailyReportDeliveryMode = "LINK";
            settings.dailyReportSoLinkTemplateId = "9101";
            settings.mediaBaseUrl = PUBLIC_BASE;
        }

        private void stubSendHsm() throws Exception {
            when(client.execute(contains("sendHsmMessage"), anyMap())).thenReturn(mapper.readTree("""
                    {"sendHsmMessage":{"message":{"id":1,"body":"b","isHSM":true},"errors":[]}}
                    """));
        }

        @Test
        void sendsOneHsm_andNeverRegistersMedia() throws Exception {
            stubSendHsm();

            sender().sendDailyReportHsm(16714L, PUBLIC_URL, "SECTION_OFFICER",
                    LocalDate.of(2026, 8, 19), "Ramesh Kumar");

            verify(client).execute(contains("sendHsmMessage"), anyMap());
            verify(client, never()).execute(contains("createMessageMedia"), anyMap());
            verify(client, never()).execute(contains("createAndSendMessage"), anyMap());
        }

        @Test
        void passesNameThenDateThenUrlSuffix_inThatOrder() throws Exception {
            stubSendHsm();

            sender().sendDailyReportHsm(16714L, PUBLIC_URL, "SECTION_OFFICER",
                    LocalDate.of(2026, 8, 19), "Ramesh Kumar");

            ArgumentCaptor<Map<String, Object>> vars = varsCaptor();
            verify(client).execute(contains("sendHsmMessage"), vars.capture());
            // Glific forwards this list to Gupshup as a flat params array filled in order of
            // occurrence: body variables first, the button's URL suffix last.
            assertThat(vars.getValue())
                    .containsEntry("templateId", "9101")
                    .containsEntry("receiverId", 16714L)
                    .containsEntry("parameters", List.of("Ramesh Kumar", "19-08-2026", OBJECT_PATH));
        }

        @Test
        void suffixExcludesTheTemplatesFrozenPrefix_evenWhenBaseUrlHasATrailingSlash() {
            settings.mediaBaseUrl = PUBLIC_BASE + "/";

            assertThat(sender().linkSuffix(PUBLIC_URL)).isEqualTo(OBJECT_PATH);
        }

        @Test
        void refusesAUrlFromAnotherHost_withoutCallingGlific() {
            assertThatThrownBy(() -> sender().sendDailyReportHsm(16714L,
                    "https://some-other-host.example.com/minio/" + OBJECT_PATH,
                    "SECTION_OFFICER", LocalDate.of(2026, 8, 19), "Ramesh Kumar"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not start with the template's URL prefix");

            verifyNoInteractions(client);
        }

        @Test
        void refusesTheBarePrefixWithNoObjectPath() {
            assertThatThrownBy(() -> sender().linkSuffix(PUBLIC_BASE + "/"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("no object path");
        }

        @Test
        void blankOfficerNameDegradesToOfficer_ratherThanFailingTheSend() throws Exception {
            stubSendHsm();

            sender().sendDailyReportHsm(16714L, PUBLIC_URL, "SECTION_OFFICER",
                    LocalDate.of(2026, 8, 19), "   ");

            ArgumentCaptor<Map<String, Object>> vars = varsCaptor();
            verify(client).execute(contains("sendHsmMessage"), vars.capture());
            assertThat(vars.getValue())
                    .containsEntry("parameters", List.of("Officer", "19-08-2026", OBJECT_PATH));
        }

        @Test
        void refusesAMissingReportDate_becauseItIsATemplateVariable() {
            assertThatThrownBy(() -> sender().sendDailyReportHsm(16714L, PUBLIC_URL, "SECTION_OFFICER",
                    null, "Ramesh Kumar"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("report date");

            verifyNoInteractions(client);
        }

        @Test
        void sdoUsesItsOwnLinkTemplate_whenConfigured() throws Exception {
            settings.dailyReportSdoLinkTemplateId = "9102";
            stubSendHsm();

            sender().sendDailyReportHsm(16714L, PUBLIC_URL, "SUB_DIVISIONAL_OFFICER",
                    LocalDate.of(2026, 8, 19), "SDO Kumar");

            ArgumentCaptor<Map<String, Object>> vars = varsCaptor();
            verify(client).execute(contains("sendHsmMessage"), vars.capture());
            assertThat(vars.getValue()).containsEntry("templateId", "9102");
        }

        @Test
        void sdoFallsBackToTheSoLinkTemplate_whenItsOwnIsBlank() throws Exception {
            stubSendHsm();

            sender().sendDailyReportHsm(16714L, PUBLIC_URL, "SUB_DIVISIONAL_OFFICER",
                    LocalDate.of(2026, 8, 19), "SDO Kumar");

            ArgumentCaptor<Map<String, Object>> vars = varsCaptor();
            verify(client).execute(contains("sendHsmMessage"), vars.capture());
            assertThat(vars.getValue()).containsEntry("templateId", "9101");
        }

        @Test
        void isNoOp_underDailyReportDryRun() {
            settings.dailyReportDryRun = true;

            sender().sendDailyReportHsm(16714L, PUBLIC_URL, "SECTION_OFFICER",
                    LocalDate.of(2026, 8, 19), "Ramesh Kumar");

            verifyNoInteractions(client);
        }

        @Test
        void refusesAnUnresolvedContactId_beforeAnyGlificCall() {
            assertThatThrownBy(() -> sender().sendDailyReportHsm(0L, PUBLIC_URL, "SECTION_OFFICER",
                    LocalDate.of(2026, 8, 19), "Ramesh Kumar"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sendDailyReportHsm");

            verifyNoInteractions(client);
        }

        @Test
        void surfacesGlificErrors_soTheRouterCanFailTheDelivery() throws Exception {
            when(client.execute(contains("sendHsmMessage"), anyMap())).thenReturn(mapper.readTree("""
                    {"sendHsmMessage":{"errors":[{"key":"params","message":"wrong number of parameters"}]}}
                    """));

            assertThatThrownBy(() -> sender().sendDailyReportHsm(16714L, PUBLIC_URL, "SECTION_OFFICER",
                    LocalDate.of(2026, 8, 19), "Ramesh Kumar"))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("wrong number of parameters");
        }

        @Test
        void validateTemplates_requiresTheSoLinkTemplate_andNotTheDocumentIds() {
            settings.whatsappDryRun = true;
            settings.nudgeDryRun = true;
            settings.escalationDryRun = true;
            settings.dailyReportDryRun = false;
            // No daily-report-so-id at all: LINK mode never reads it, so it must not be demanded.
            settings.dailyReportSoTemplateId = "";

            assertThatCode(() -> sender().validateTemplates()).doesNotThrowAnyException();

            settings.dailyReportSoLinkTemplateId = "";
            assertThatThrownBy(() -> sender().validateTemplates())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("whatsapp.template.daily-report-so-link-id")
                    .hasMessageContaining("WHATSAPP_DAILY_REPORT_SO_LINK_TEMPLATE_ID");
        }

        @Test
        void validateTemplates_failsWhenTheButtonBaseUrlDoesNotMatchThePublicBaseUrl() {
            settings.whatsappDryRun = true;
            settings.nudgeDryRun = true;
            settings.escalationDryRun = true;
            settings.dailyReportDryRun = false;
            // The staging template's prefix against a production STORAGE_PUBLIC_BASE_URL — the mistake this
            // guard exists for, because Glific accepts the send and only the officer sees the dead link.
            settings.dailyReportLinkButtonBaseUrl = "https://jalsoochak.in/minio/";

            assertThatThrownBy(() -> sender().validateTemplates())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("daily-report.link.button-base-url")
                    .hasMessageContaining(PUBLIC_BASE + "/");
        }

        @Test
        void validateTemplates_passesWhenTheButtonBaseUrlMatches() {
            settings.whatsappDryRun = true;
            settings.nudgeDryRun = true;
            settings.escalationDryRun = true;
            settings.dailyReportDryRun = false;
            settings.dailyReportLinkButtonBaseUrl = PUBLIC_BASE + "/";

            assertThatCode(() -> sender().validateTemplates()).doesNotThrowAnyException();
        }
    }

    // ─────────────────── delivery mode parsing ─────────────────────────────────

    @Test
    void deliveryMode_defaultsToDocument_soAnUnsetPropertyChangesNothing() {
        assertThat(ReportDeliveryMode.from(null)).isEqualTo(ReportDeliveryMode.DOCUMENT);
        assertThat(ReportDeliveryMode.from("  ")).isEqualTo(ReportDeliveryMode.DOCUMENT);
    }

    @Test
    void deliveryMode_toleratesCaseAndWhitespace() {
        assertThat(ReportDeliveryMode.from("link")).isEqualTo(ReportDeliveryMode.LINK);
        assertThat(ReportDeliveryMode.from(" LINK ")).isEqualTo(ReportDeliveryMode.LINK);
        assertThat(ReportDeliveryMode.from("Document")).isEqualTo(ReportDeliveryMode.DOCUMENT);
    }

    @Test
    void deliveryMode_rejectsAnUnknownValue_namingTheValidOnes() {
        assertThatThrownBy(() -> ReportDeliveryMode.from("pdf"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DOCUMENT or LINK")
                .hasMessageContaining("pdf");
    }

    /**
     * The Glific message id that {@code sendHsmMessage} / {@code createAndSendMessage} return used to be
     * parsed and discarded. It is the only join key between a report we sent and the delivery status
     * Gupshup and Meta later report back to Glific, so these tests pin it down.
     */
    @Nested
    class MessageIdCapture {

        private static final String PUBLIC_BASE = "https://jalsoochak.jjmbrain.in/minio";
        private static final String PUBLIC_URL =
                PUBLIC_BASE + "/escalation-reports/daily_report_SECTION_OFFICER_16714_2026-08-19.pdf";

        @BeforeEach
        void publicBaseUrl() {
            settings.mediaBaseUrl = PUBLIC_BASE;
        }

        @Test
        void linkMode_returnsTheMessageIdTemplateIdAndMode() throws Exception {
            settings.dailyReportDeliveryMode = "LINK";
            settings.dailyReportSoLinkTemplateId = "880557";
            when(client.execute(contains("sendHsmMessage"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"sendHsmMessage":{"message":{"id":241952654,"body":"b","isHSM":true},"errors":[]}}
                    """));

            WhatsAppSendResult result = sender().sendDailyReportHsm(16714L, PUBLIC_URL, "SECTION_OFFICER",
                    LocalDate.of(2026, 8, 19), "Ramesh Kumar");

            assertThat(result.messageId()).isEqualTo("241952654");
            assertThat(result.templateId()).isEqualTo("880557");
            assertThat(result.mode()).isEqualTo(ReportDeliveryMode.LINK);
            assertThat(result.hasMessageId()).isTrue();
        }

        @Test
        void documentMode_returnsTheMessageIdTemplateIdAndMode() throws Exception {
            settings.dailyReportDeliveryMode = "DOCUMENT";
            settings.dailyReportSoTemplateId = "880600";
            when(client.execute(contains("createMessageMedia"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"createMessageMedia":{"messageMedia":{"id":77,"url":"u"},"errors":[]}}
                    """));
            when(client.execute(contains("createAndSendMessage"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"createAndSendMessage":{"message":{"id":241952700,"body":"b","isHsm":true},"errors":[]}}
                    """));

            WhatsAppSendResult result = sender().sendDailyReportHsm(16714L, PUBLIC_URL, "SECTION_OFFICER",
                    LocalDate.of(2026, 8, 19), "Ramesh Kumar");

            assertThat(result.messageId()).isEqualTo("241952700");
            assertThat(result.templateId()).isEqualTo("880600");
            assertThat(result.mode()).isEqualTo(ReportDeliveryMode.DOCUMENT);
        }

        /**
         * A send with no id must not come back as a result at all. Returning one reported the report as
         * accepted while it carried no join key — counted as delivered and simultaneously invisible to
         * reconciliation, which is the one combination that cannot be noticed later.
         */
        @Test
        void aSendThatReturnsNoIdIsRefused() throws Exception {
            settings.dailyReportDeliveryMode = "LINK";
            settings.dailyReportSoLinkTemplateId = "880557";
            when(client.execute(contains("sendHsmMessage"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"sendHsmMessage":{"message":{},"errors":[]}}
                    """));

            assertThatThrownBy(() -> sender().sendDailyReportHsm(16714L, PUBLIC_URL, "SECTION_OFFICER",
                    LocalDate.of(2026, 8, 19), "Ramesh Kumar"))
                    .isInstanceOf(GlificMissingMessageIdException.class)
                    .hasMessageContaining("returned no message.id")
                    .satisfies(e -> assertThat(((GlificMutationException) e).getMutationKey())
                            .isEqualTo("sendHsmMessage"));
        }

        /** The document path shares {@code extractMessageId}, so it must refuse a blank id too. */
        @Test
        void documentMode_refusesASendThatReturnsABlankId() throws Exception {
            settings.dailyReportDeliveryMode = "DOCUMENT";
            settings.dailyReportSoTemplateId = "880600";
            when(client.execute(contains("createMessageMedia"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"createMessageMedia":{"messageMedia":{"id":77,"url":"u"},"errors":[]}}
                    """));
            when(client.execute(contains("createAndSendMessage"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"createAndSendMessage":{"message":{"id":"  "},"errors":[]}}
                    """));

            assertThatThrownBy(() -> sender().sendDailyReportHsm(16714L, PUBLIC_URL, "SECTION_OFFICER",
                    LocalDate.of(2026, 8, 19), "Ramesh Kumar"))
                    .isInstanceOf(GlificMissingMessageIdException.class)
                    .satisfies(e -> assertThat(((GlificMutationException) e).getMutationKey())
                            .isEqualTo("createAndSendMessage"));
        }

        /** A dry-run reports the mode but never a message id — a fake one would poison reconciliation. */
        @Test
        void aDryRunReportsTheModeButNoMessageId() {
            settings.dailyReportDryRun = true;
            settings.dailyReportDeliveryMode = "LINK";

            WhatsAppSendResult result = sender().sendDailyReportHsm(16714L, PUBLIC_URL, "SECTION_OFFICER",
                    LocalDate.of(2026, 8, 19), "Ramesh Kumar");

            assertThat(result.hasMessageId()).isFalse();
            assertThat(result.mode()).isEqualTo(ReportDeliveryMode.LINK);
            verifyNoInteractions(client);
        }

        /**
         * A suppressed send must not start throwing on a delivery-mode typo it never used to read —
         * that would be a new failure on a path that is switched off.
         */
        @Test
        void aDryRunToleratesAnUnparseableDeliveryMode() {
            settings.dailyReportDryRun = true;
            settings.dailyReportDeliveryMode = "pdf";

            WhatsAppSendResult result = sender().sendDailyReportHsm(16714L, PUBLIC_URL, "SECTION_OFFICER",
                    LocalDate.of(2026, 8, 19), "Ramesh Kumar");

            assertThat(result.mode()).isNull();
            assertThat(result.modeForLog()).isEqualTo("-");
        }

        /** Glific's error key is carried on the exception so the caller can tag the failure stage. */
        @Test
        void aRejectedSendCarriesTheMutationKeyAndErrorKey() throws Exception {
            settings.dailyReportDeliveryMode = "LINK";
            settings.dailyReportSoLinkTemplateId = "880557";
            when(client.execute(contains("sendHsmMessage"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"sendHsmMessage":{"message":null,"errors":[{"key":"receiver","message":"Receiver does not exist"}]}}
                    """));

            assertThatThrownBy(() -> sender().sendDailyReportHsm(16714L, PUBLIC_URL, "SECTION_OFFICER",
                    LocalDate.of(2026, 8, 19), "Ramesh Kumar"))
                    .isInstanceOf(GlificMutationException.class)
                    .hasMessageContaining("Glific GraphQL error in sendHsmMessage")
                    .satisfies(e -> {
                        GlificMutationException gme = (GlificMutationException) e;
                        assertThat(gme.getMutationKey()).isEqualTo("sendHsmMessage");
                        assertThat(gme.getErrorKey()).isEqualTo("receiver");
                    });
        }

        /** The 20 Aug incident's shape: the media step is what failed, and the stage must say so. */
        @Test
        void aMediaRegistrationFailureIsTaggedWithItsOwnMutationKey() throws Exception {
            settings.dailyReportDeliveryMode = "DOCUMENT";
            settings.dailyReportSoTemplateId = "880600";
            when(client.execute(contains("createMessageMedia"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"createMessageMedia":{"messageMedia":null,"errors":[{"key":"media","message":"(#131053) Media upload error"}]}}
                    """));

            assertThatThrownBy(() -> sender().sendDailyReportHsm(16714L, PUBLIC_URL, "SECTION_OFFICER",
                    LocalDate.of(2026, 8, 19), "Ramesh Kumar"))
                    .isInstanceOf(GlificMutationException.class)
                    .satisfies(e -> assertThat(((GlificMutationException) e).getMutationKey())
                            .isEqualTo("createMessageMedia"));
        }
    }

    /**
     * Where a failed report send broke, as {@link WhatsAppChannel} reports it — the one logic change in
     * putting this adapter behind the port.
     *
     * <p>The channel used to work the stage out by inspecting this adapter's exceptions. The adapter
     * now assigns it, and the channel reads it off the port's {@link WhatsAppSendException}. Each test
     * drives a real channel over a real adapter, so the stage the router logs is checked across the
     * whole seam, and each pins a stage the old classification gave.</p>
     */
    @Nested
    class FailureStage {

        private static final String PUBLIC_URL = "https://jalsoochak.jjmbrain.in/minio"
                + "/escalation-reports/daily_report_SECTION_OFFICER_16714_2026-08-19.pdf";
        private static final LocalDate REPORT_DATE = LocalDate.of(2026, 8, 19);

        @BeforeEach
        void documentMode() {
            settings.dailyReportDeliveryMode = "DOCUMENT";
            settings.dailyReportSoTemplateId = "880600";
        }

        private ReportSendOutcome sendThroughTheChannel(long contactId) {
            return new WhatsAppChannel(sender()).sendDailyReport(
                    contactId, PUBLIC_URL, "SECTION_OFFICER", REPORT_DATE, "Ramesh Kumar");
        }

        private void stubMediaRegistered() throws Exception {
            when(client.execute(contains("createMessageMedia"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"createMessageMedia":{"messageMedia":{"id":77,"url":"u"},"errors":[]}}
                    """));
        }

        /** The 20 Aug (#131053) incident: Meta could not fetch the PDF, so Glific refused the media. */
        @Test
        void aRejectedMediaRegistration_isMediaRegister() throws Exception {
            when(client.execute(contains("createMessageMedia"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"createMessageMedia":{"messageMedia":null,"errors":[{"key":"media","message":"(#131053) Media upload error"}]}}
                    """));

            ReportSendOutcome outcome = sendThroughTheChannel(16714L);

            assertThat(outcome.failure().stage()).isEqualTo(WhatsAppSendStage.MEDIA_REGISTER);
            assertThat(outcome.failure().errorKey()).isEqualTo("media");
            verify(client, never()).execute(contains("createAndSendMessage"), anyMap());
        }

        /**
         * Glific accepted the send but returned no id. Classed as a plain {@code SEND} it would be
         * retried, sending the officer a second copy of a report Glific already holds.
         */
        @Test
        void anAcceptedSendWithoutAMessageId_isSendNoMessageId() throws Exception {
            stubMediaRegistered();
            when(client.execute(contains("createAndSendMessage"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"createAndSendMessage":{"message":{},"errors":[]}}
                    """));

            ReportSendOutcome outcome = sendThroughTheChannel(16714L);

            assertThat(outcome.failure().stage()).isEqualTo(WhatsAppSendStage.SEND_NO_MESSAGE_ID);
            assertThat(outcome.failure().errorKey()).isNull();
        }

        @Test
        void aRejectedSend_isSend_withGlificsErrorKey() throws Exception {
            stubMediaRegistered();
            when(client.execute(contains("createAndSendMessage"), anyMap())).thenReturn(mapper.readTree(
                    """
                    {"createAndSendMessage":{"message":null,"errors":[{"key":"receiver","message":"Receiver does not exist"}]}}
                    """));

            ReportSendOutcome outcome = sendThroughTheChannel(16714L);

            assertThat(outcome.failure().stage()).isEqualTo(WhatsAppSendStage.SEND);
            assertThat(outcome.failure().errorKey()).isEqualTo("receiver");
        }

        /**
         * Reactor reports an expired {@code block()} as an {@link IllegalStateException}, the type our
         * own configuration errors use. The adapter must pass it through untouched, and it must still
         * come out as {@code TIMEOUT}: Glific may already have sent the message, so this is the one
         * failure a retry makes worse.
         */
        @Test
        void aBlockTimeout_passesThroughTheAdapter_andIsTimeoutNotConfig() {
            IllegalStateException timeout =
                    new IllegalStateException("Timeout on blocking read for 30000 MILLISECONDS");
            when(client.execute(contains("createMessageMedia"), anyMap())).thenThrow(timeout);

            assertThatThrownBy(() -> sender().sendDailyReportHsm(16714L, PUBLIC_URL, "SECTION_OFFICER",
                    REPORT_DATE, "Ramesh Kumar"))
                    .isSameAs(timeout);
            assertThat(sendThroughTheChannel(16714L).failure().stage()).isEqualTo(WhatsAppSendStage.TIMEOUT);
        }

        /** Nothing reached Glific because the contact was never opted in, so retrying cannot help. */
        @Test
        void anUnresolvedContactId_isConfig() {
            ReportSendOutcome outcome = sendThroughTheChannel(0L);

            assertThat(outcome.failure().stage()).isEqualTo(WhatsAppSendStage.CONFIG);
            assertThat(outcome.failure().errorKey()).isNull();
            verifyNoInteractions(client);
        }

        /**
         * The port takes a boxed {@code Long}, so a null contact id reaches the adapter and is refused
         * as our own input error — which the channel reads as {@code CONFIG} — rather than failing to
         * unbox before the call and being read as a send failure.
         */
        @Test
        void aNullContactId_isRefusedAsOurInputError_notAsAProviderFailure() {
            assertThatThrownBy(() -> sender().sendDailyReportHsm(null, PUBLIC_URL, "SECTION_OFFICER",
                    REPORT_DATE, "Ramesh Kumar"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .isNotInstanceOf(WhatsAppSendException.class);
            verifyNoInteractions(client);
        }
    }
}
