package org.arghyam.jalsoochak.message.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Handles Glific GraphQL operations: opt-in a contact and send HSM messages.
 *
 * <p>Nudge HSM: {{1}} = operator name, {{2}} = today's date.</p>
 * <p>Escalation HSM (document type, two-step):
 * <ol>
 *   <li>Upload the MinIO PDF URL via {@code createMessageMedia} → receive {@code mediaId}.</li>
 *   <li>Send via {@code createAndSendMessage} with {@code mediaId} (document header)
 *       and {@code parameters[0]} = localized body text.</li>
 * </ol>
 * </p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GlificWhatsAppService {

    private static final String OPTIN_MUTATION = """
            mutation optinContact($phone: String!) {
              optinContact(phone: $phone) {
                contact { id }
                errors { key message }
              }
            }""";

    private static final String NUDGE_HSM_MUTATION = """
            mutation sendHsmMessage($templateId: ID!, $receiverId: ID!, $parameters: [String]) {
              sendHsmMessage(templateId: $templateId, receiverId: $receiverId, parameters: $parameters) {
                message { id body isHSM }
                errors { key message }
              }
            }""";

    private static final String CREATE_MESSAGE_MEDIA_MUTATION = """
            mutation createMessageMedia($input: MessageMediaInput!) {
              createMessageMedia(input: $input) {
                messageMedia { id url }
                errors { key message }
              }
            }""";

    private static final String START_CONTACT_FLOW_MUTATION = """
            mutation startContactFlow($flowId: ID!, $contactId: ID!, $defaultResults: Json!) {
              startContactFlow(flowId: $flowId, contactId: $contactId, defaultResults: $defaultResults) {
                success
                errors { key message }
              }
            }""";

    private static final String UPDATE_CONTACT_MUTATION = """
            mutation updateContact($id: ID!, $input: ContactInput!) {
              updateContact(id: $id, input: $input) {
                contact { id language { id } }
                errors { key message }
              }
            }""";

    private static final String CREATE_AND_SEND_MESSAGE_MUTATION = """
    mutation createAndSendMessage($input: MessageInput!) {
      createAndSendMessage(input: $input) {
        message {
          id
          body
          isHsm
        }
        errors {
          key
          message
        }
      }
    }
    """;

    private final GlificGraphQLClient client;
    private final ObjectMapper objectMapper;

    @Value("${notifications.dry-run:false}")
    private boolean dryRun;

    @Value("${glific.template.nudge-id:}")
    private String nudgeTemplateId;

    @Value("${glific.template.escalation-id:}")
    private String escalationTemplateId;

    @Value("${glific.template.login-otp-id:}")
    private String loginOtpTemplateId;

    @Value("${glific.flow.nudge-id:}")
    private String nudgeFlowId;

    @Value("${glific.flow.welcome-id:}")
    private String welcomeFlowId;

    @PostConstruct
    void validateTemplates() {
        if (dryRun) {
            log.warn("[Glific] DRY-RUN mode active — all Glific API calls will be suppressed."
                    + " Set NOTIFICATIONS_DRY_RUN=false for production.");
            return;
        }
        if (nudgeFlowId == null || nudgeFlowId.isBlank()
                || escalationTemplateId == null || escalationTemplateId.isBlank()
                || welcomeFlowId == null || welcomeFlowId.isBlank()) {
            throw new IllegalStateException(
                    "glific.flow.nudge-id, glific.template.escalation-id and glific.flow.welcome-id must be configured");
        }
        if (loginOtpTemplateId == null || loginOtpTemplateId.isBlank()) {
            throw new IllegalStateException(
                    "glific.template.login-otp-id must be configured — SEND_LOGIN_OTP events cannot be delivered without it");
        }
    }

    private boolean isDryRun(String operation) {
        if (dryRun) {
            log.info("[Glific] DRY-RUN: suppressing {} — no message sent", operation);
            return true;
        }
        return false;
    }

    @Value("${glific.media.escalation-caption:Escalations}")
    private String escalationCaption;

    @Value("${glific.media.escalation-thumbnail:}")
    private String escalationThumbnail;

    /**
     * Sends the login OTP HSM template to an officer.
     * Template variable {{1}} = OTP.
     *
     * @param contactId Glific contact ID of the officer
     * @param otp       one-time password for template {@code {{1}}}
     */
    public void sendLoginOtpHsm(Long contactId, String otp) {
        if (isDryRun("sendLoginOtpHsm")) return;
        if (loginOtpTemplateId == null || loginOtpTemplateId.isBlank()) {
            throw new IllegalStateException("glific.template.login-otp-id is not configured");
        }
        JsonNode response = client.execute(NUDGE_HSM_MUTATION, Map.of(
                "templateId", loginOtpTemplateId,
                "receiverId", contactId,
                "parameters", List.of(otp)));
        checkErrors(response, "sendHsmMessage");
        log.debug("[Glific] Login OTP HSM sent to contactId={}", contactId);
    }

    /**
     * Opts in the contact by phone number and returns the Glific contact ID.
     * Phone must be in E.164 format (e.g., 919876543210).
     */
    public Long optIn(String phone) {
        if (isDryRun("optIn")) return 0L;
        log.debug("[Glific] Opting in contact");
        JsonNode response = client.execute(OPTIN_MUTATION, Map.of("phone", phone));
        checkErrors(response, "optinContact");
        return response.path("optinContact").path("contact").path("id").asLong();
    }

    /**
     * Sends the nudge HSM template to the contact.
     * Template variable {{1}} = operator name, {{2}} = today's date.
     */
    public void sendNudgeHsm(Long contactId, String operatorName, String date) {
        if (isDryRun("sendNudgeHsm")) return;
        JsonNode response = client.execute(NUDGE_HSM_MUTATION, Map.of(
                "templateId", nudgeTemplateId,
                "receiverId", contactId,
                "parameters", List.of(operatorName, date)));
        checkErrors(response, "sendHsmMessage");
        log.debug("[Glific] Nudge HSM sent to contactId={}", contactId);
    }

    /**
     * Uploads a media file to Glific via its publicly reachable URL and returns the Glific media ID.
     *
     * @param publicUrl publicly reachable URL of the file (e.g. MinIO presigned URL or ngrok URL)
     * @return Glific {@code messageMedia.id} to pass to {@link #sendEscalationHsm}
     */
    public String uploadMedia(String publicUrl) {
        if (isDryRun("uploadMedia")) return "dry-run-media-id";
        log.debug("[Glific] Uploading media");
        JsonNode response = client.execute(CREATE_MESSAGE_MEDIA_MUTATION, Map.of(
                        "input", Map.of(
                                "url", publicUrl,
                                "source_url", publicUrl,
                                "caption", escalationCaption,
                                "thumbnail", escalationThumbnail,
                                "isTemplateMedia", true)));
        checkErrors(response, "createMessageMedia");
        String mediaId = response.path("createMessageMedia").path("messageMedia").path("id").asText();
        log.info("[Glific] Media uploaded, mediaId={}", mediaId);
        return mediaId;
    }

    /**
     * Sends the escalation document HSM to the officer.
     *
     * <p>Two-step process:
     * <ol>
     *   <li>Upload {@code minioUrl} via {@code createMessageMedia} → {@code mediaId}</li>
     *   <li>Send {@code createAndSendMessage} with the {@code mediaId} as the document
     *       header attachment and {@code bodyText} as the body template parameter.</li>
     * </ol>
     *
     * @param contactId Glific contact ID of the officer
     * @param minioUrl  publicly reachable URL of the escalation PDF
     */
    public void sendEscalationHsm(Long contactId, String minioUrl) {
        if (isDryRun("sendEscalationHsm")) return;

        String mediaId = uploadMedia(minioUrl);

        Map<String, Object> input = new HashMap<>();
        input.put("templateId", Integer.parseInt(escalationTemplateId));
        input.put("receiverId", contactId.intValue());
        input.put("isHsm", true);
        input.put("params", List.of());

        if (mediaId != null && !mediaId.isBlank()) {
            input.put("mediaId", Integer.parseInt(mediaId));
        }

        JsonNode response = client.execute(
                CREATE_AND_SEND_MESSAGE_MUTATION,
                Map.of("input", input)
        );
        checkErrors(response, "createAndSendMessage");

        log.debug("[Glific] Escalation HSM sent to contactId={}", contactId);
    }

    /**
     * Initiates a Glific flow for the nudge contact via the {@code startContactFlow} mutation.
     *
     * <p>Instead of sending a plain HSM message, this triggers the interactive nudge flow
     * configured in Glific (identified by {@code glific.flow.nudge-id}). The flow sends
     * an HSM template with clickable buttons and continues the conversation based on
     * the operator's button response.</p>
     *
     * <p>Operator name and date are passed as {@code defaultResults} using the keys
     * {@code "name"} and {@code "date"} respectively, matching the HSM template parameter names.</p>
     *
     * <p>{@code glific.flow.nudge-id} is a required configuration — startup fails fast
     * if it is absent (see {@code @PostConstruct} validation).</p>
     *
     * @param contactId    Glific contact ID obtained from {@link #optIn}
     * @param operatorName operator name; passed as {@code defaultResults} key {@code "name"}
     * @param date         today's date string; passed as {@code defaultResults} key {@code "date"}
     * @throws IllegalStateException if {@code glific.flow.nudge-id} is blank
     * @throws RuntimeException      if Glific returns GraphQL errors or {@code success=false}
     */
    public void startNudgeFlow(Long contactId, String operatorName, String date) {
        if (isDryRun("startNudgeFlow")) return;
        if (nudgeFlowId == null || nudgeFlowId.isBlank()) {
            throw new IllegalStateException("glific.flow.nudge-id is not configured");
        }

        String defaultResults;
        try {
            defaultResults = objectMapper.writeValueAsString(
                    Map.of("name", operatorName != null ? operatorName : "",
                           "date", date != null ? date : ""));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize flow defaultResults", e);
        }

        JsonNode response = client.execute(START_CONTACT_FLOW_MUTATION, Map.of(
                "flowId", nudgeFlowId,
                "contactId", contactId,
                "defaultResults", defaultResults));

        checkErrors(response, "startContactFlow");
        JsonNode flowNode = response.path("startContactFlow");

        boolean success = flowNode.path("success").asBoolean(false);
        if (!success) {
            throw new RuntimeException("Glific startContactFlow returned success=false for contactId=" + contactId);
        }
        log.debug("[Glific] Nudge flow started for contactId={}", contactId);
    }

    /**
     * Initiates the Glific welcome flow for a newly onboarded operator.
     *
     * @param contactId Glific contact ID obtained from {@link #optIn}
     * @param name      operator display name passed as {@code @results.name} in the flow
     * @param state     tenant state name passed as {@code @results.state} in the flow
     * @throws RuntimeException if Glific returns GraphQL errors or {@code success=false}
     */
    public void startWelcomeFlow(Long contactId, String name, String state) {
        startWelcomeFlow(contactId, welcomeFlowId, name, state);
    }

    /**
     * Initiates the Glific welcome flow using an explicit flow ID override.
     *
     * @param contactId Glific contact ID obtained from {@link #optIn}
     * @param flowId    Glific flow ID to use for welcome flow
     * @param name      operator display name passed as {@code @results.name} in the flow
     * @param state     tenant state name passed as {@code @results.state} in the flow
     * @throws IllegalStateException if {@code flowId} is blank
     * @throws RuntimeException      if Glific returns GraphQL errors or {@code success=false}
     */
    public void startWelcomeFlow(Long contactId, String flowId, String name, String state) {
        if (isDryRun("startWelcomeFlow")) return;
        if (flowId == null || flowId.isBlank()) {
            throw new IllegalStateException("glific.flow.welcome-id is not configured");
        }

        String defaultResults = serializeDefaultResults(name, state);

        JsonNode response = client.execute(START_CONTACT_FLOW_MUTATION, Map.of(
                "flowId",         flowId,
                "contactId",      contactId,
                "defaultResults", defaultResults));
        checkErrors(response, "startContactFlow");
        boolean success = response.path("startContactFlow").path("success").asBoolean(false);
        if (!success) {
            throw new RuntimeException("Glific startContactFlow returned success=false for contactId=" + contactId);
        }
        log.debug("[Glific] Welcome flow started for contactId={}", contactId);
    }

    /**
     * Updates the language of a Glific contact.
     *
     * @param contactId        Glific contact ID
     * @param glificLanguageId Glific-side language ID
     */
    public void updateContactLanguage(Long contactId, int glificLanguageId) {
        if (isDryRun("updateContactLanguage")) return;
        JsonNode response = client.execute(UPDATE_CONTACT_MUTATION, Map.of(
                "id", contactId,
                "input", Map.of("language_id", glificLanguageId)));
        checkErrors(response, "updateContact");
        log.debug("[Glific] Contact language updated contactId={} languageId={}", contactId, glificLanguageId);
    }

    private void checkErrors(JsonNode response, String mutationKey) {
        JsonNode mutationNode = response.path(mutationKey);
        if (mutationNode.isMissingNode() || mutationNode.isNull()) {
            throw new RuntimeException("Glific GraphQL response missing key: " + mutationKey);
        }
        JsonNode errors = mutationNode.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            String msg = errors.toString();
            log.error("[Glific] GraphQL errors in {}: {}", mutationKey, msg);
            throw new RuntimeException("Glific GraphQL error in " + mutationKey + ": " + msg);
        }
    }

    /**
     * Serializes flow defaultResults as JSON for name and state parameters.
     * Both values are null-safe (converted to empty string if null).
     *
     * @param name  operator name (null-safe)
     * @param state context value (e.g., tenant state or date) (null-safe)
     * @return JSON string for the welcome flow's defaultResults with keys "name" and "state"
     * @throws RuntimeException if JSON serialization fails
     */
    private String serializeDefaultResults(String name, String state) {
        try {
            return objectMapper.writeValueAsString(
                    Map.of("name", name != null ? name : "", "state", state != null ? state : ""));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize flow defaultResults", e);
        }
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() <= 4) return "****";
        return "****" + phone.substring(phone.length() - 4);
    }
}