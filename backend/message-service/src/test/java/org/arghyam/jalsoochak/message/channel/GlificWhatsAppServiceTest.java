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

    // ────────────────────────────── helpers ────────────────────────────────────

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> varsCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    // ──────────────────────────── dry-run mode ─────────────────────────────────

    @Nested
    class DryRunMode {

        @BeforeEach
        void enableDryRun() {
            ReflectionTestUtils.setField(service, "dryRun", true);
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
        void validateTemplates_doesNotThrowWhenTemplateIdsBlank() {
            ReflectionTestUtils.setField(service, "nudgeFlowId", "");
            ReflectionTestUtils.setField(service, "escalationTemplateId", "");
            ReflectionTestUtils.setField(service, "welcomeFlowId", "");
            ReflectionTestUtils.setField(service, "loginOtpTemplateId", "");

            assertThatCode(() -> service.validateTemplates()).doesNotThrowAnyException();
        }
    }
}