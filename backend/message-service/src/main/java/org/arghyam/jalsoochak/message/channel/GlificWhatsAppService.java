package org.arghyam.jalsoochak.message.channel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.util.PublicUrlValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
 * <p>Daily report: either shape, chosen by {@code notifications.daily-report.delivery-mode} — see
 * {@link DailyReportDeliveryMode}. {@code LINK} mode is a single {@code sendHsmMessage} with
 * {{1}} = officer name, {{2}} = report date and the button's URL suffix last.</p>
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

    /**
     * The plain HSM send. Shared by every template whose variables are all text — the nudge, the
     * login OTP and the daily report in {@link DailyReportDeliveryMode#LINK} mode. Only the document
     * templates need the two-step {@code createMessageMedia} + {@code createAndSendMessage} pair.
     */
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

    /**
     * Master WhatsApp dry-run. Gates the shared account operations that are neither a nudge, an
     * escalation nor a daily report: login OTP, welcome flow and language updates. The
     * {@link #nudgeDryRun}, {@link #escalationDryRun} and {@link #dailyReportDryRun} flags below
     * default to this value when their own properties are unset, so a single
     * {@code NOTIFICATIONS_WHATSAPP_DRY_RUN=true} still suppresses every Glific call
     * (backwards compatible with the previous single-flag behaviour).
     *
     * <p>Contact opt-in is deliberately <em>not</em> gated on this flag — see
     * {@link #isOptInDryRun()}. It sends the recipient nothing and is the prerequisite for every
     * delivery, so muting account operations must not break a purpose that is switched live.</p>
     */
    @Value("${notifications.whatsapp.dry-run:false}")
    private boolean whatsappDryRun;

    /**
     * Suppresses only the operator nudge (flow + HSM). Defaults to {@link #whatsappDryRun}.
     * Set {@code NOTIFICATIONS_NUDGE_DRY_RUN=true} to mute nudges while escalations stay live.
     */
    @Value("${notifications.nudge.dry-run:${notifications.whatsapp.dry-run:false}}")
    private boolean nudgeDryRun;

    /**
     * Suppresses only the officer escalation document HSM (and its media upload).
     * Defaults to {@link #whatsappDryRun}. Set {@code NOTIFICATIONS_ESCALATION_DRY_RUN=false}
     * to deliver escalations to officers (SO/SDO) while nudges stay muted.
     */
    @Value("${notifications.escalation.dry-run:${notifications.whatsapp.dry-run:false}}")
    private boolean escalationDryRun;

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

    /**
     * Suppresses only the Daily Water Service Situation Report document HSM (and its media upload).
     * Defaults to {@link #whatsappDryRun}. Set {@code NOTIFICATIONS_DAILY_REPORT_DRY_RUN=false} to
     * deliver officer daily reports while nudges, escalations and account operations stay muted.
     */
    @Value("${notifications.daily-report.dry-run:${notifications.whatsapp.dry-run:false}}")
    private boolean dailyReportDryRun;

    /** Document HSM template id for the SECTION_OFFICER daily report. */
    @Value("${glific.template.daily-report-so-id:}")
    private String dailyReportSoTemplateId;

    /** Document HSM template id for the SUB_DIVISIONAL_OFFICER daily report. */
    @Value("${glific.template.daily-report-sdo-id:}")
    private String dailyReportSdoTemplateId;

    /**
     * Chooses how the daily report reaches the officer — {@code DOCUMENT} (PDF attachment, which Meta
     * must download itself) or {@code LINK} (dynamic-URL button, which Meta never fetches). Bound as a
     * String and parsed on use so an unrecognised value fails loudly with the valid ones named, and so
     * an unset property behaves exactly as before this mode existed.
     */
    @Value("${notifications.daily-report.delivery-mode:DOCUMENT}")
    private String dailyReportDeliveryMode;

    /** Text HSM template id (dynamic-URL button) for the SECTION_OFFICER daily report — LINK mode. */
    @Value("${glific.template.daily-report-so-link-id:}")
    private String dailyReportSoLinkTemplateId;

    /** Text HSM template id (dynamic-URL button) for the SUB_DIVISIONAL_OFFICER daily report — LINK mode. */
    @Value("${glific.template.daily-report-sdo-link-id:}")
    private String dailyReportSdoLinkTemplateId;

    /**
     * Optional mirror of the URL prefix frozen into the approved LINK template, e.g.
     * {@code https://jalsoochak.jjmbrain.in/minio/}. When set it must match the prefix this service
     * strips off the MinIO URL to build the button's variable ({@link #mediaUrlPrefix()}); a mismatch
     * fails startup. It is the only check that catches an environment deployed with another
     * environment's template id or base URL — the send still succeeds in that case, and the officer is
     * the one who discovers the button leads nowhere.
     */
    @Value("${daily-report.link.button-base-url:}")
    private String dailyReportLinkButtonBaseUrl;

    /**
     * The prefix of every media URL handed to Glific. Read here — not only in
     * {@code MinioStorageService} — because this is the class that owns the Glific contract and the
     * only one that knows whether a document-sending purpose is live. Meta downloads the URL from the
     * public internet, so an internal address must stop the service from starting rather than reach
     * officers as an unopenable attachment.
     */
    @Value("${minio.base-url:}")
    private String mediaBaseUrl;

    @PostConstruct
    void validateTemplates() {
        if (isAllDryRun()) {
            log.warn("[Glific] DRY-RUN mode active — all Glific API calls will be suppressed."
                    + " Set NOTIFICATIONS_WHATSAPP_DRY_RUN=false for production.");
            return;
        }
        if (nudgeDryRun || escalationDryRun || dailyReportDryRun || whatsappDryRun) {
            log.warn("[Glific] Partial DRY-RUN — nudge={}, escalation={}, daily-report={},"
                            + " account-ops(OTP/welcome/language)={}. Contact opt-in stays live because"
                            + " at least one delivery purpose is enabled.",
                    nudgeDryRun, escalationDryRun, dailyReportDryRun, whatsappDryRun);
        }
        // Validate only the templates whose delivery is enabled.
        if (!nudgeDryRun && (nudgeFlowId == null || nudgeFlowId.isBlank())) {
            throw new IllegalStateException(
                    "glific.flow.nudge-id must be configured when nudge delivery is enabled"
                    + " (set NOTIFICATIONS_NUDGE_DRY_RUN=true to suppress nudges)");
        }
        if (!escalationDryRun && (escalationTemplateId == null || escalationTemplateId.isBlank())) {
            throw new IllegalStateException(
                    "glific.template.escalation-id must be configured when escalation delivery is enabled"
                    + " (set NOTIFICATIONS_ESCALATION_DRY_RUN=true to suppress escalations)");
        }
        if (!whatsappDryRun && (welcomeFlowId == null || welcomeFlowId.isBlank())) {
            throw new IllegalStateException(
                    "glific.flow.welcome-id must be configured");
        }
        validateAccountAndReportTemplates();
    }

    private void validateAccountAndReportTemplates() {
        if (!whatsappDryRun && isBlank(loginOtpTemplateId)) {
            throw new IllegalStateException(
                    "glific.template.login-otp-id must be configured — SEND_LOGIN_OTP events cannot be delivered without it");
        }
        if (!dailyReportDryRun) {
            // Only the templates the configured mode actually sends are required. A LINK deployment
            // never reads the document ids and vice versa, so demanding both would force every
            // environment to carry configuration it does not use.
            switch (deliveryMode()) {
                case DOCUMENT -> validateDailyReportDocumentTemplates();
                case LINK -> validateDailyReportLinkTemplates();
            }
        }
        validateMediaBaseUrl();
    }

    private void validateDailyReportDocumentTemplates() {
        if (isBlank(dailyReportSoTemplateId)) {
            throw new IllegalStateException(
                    "glific.template.daily-report-so-id must be configured when daily-report delivery is enabled"
                    + " (set NOTIFICATIONS_DAILY_REPORT_DRY_RUN=true to suppress daily reports)");
        }
        // sendDailyReportDocumentHsm does Integer.parseInt on the resolved template id, so fail fast at
        // startup on a non-numeric id rather than per-message (retry → DLT) at delivery time.
        requireNumericTemplateId(dailyReportSoTemplateId, "glific.template.daily-report-so-id");
        // The SDO id is optional (resolveDailyReportTemplateId falls back to the SO template),
        // so validate it only when it has been configured.
        if (!isBlank(dailyReportSdoTemplateId)) {
            requireNumericTemplateId(dailyReportSdoTemplateId, "glific.template.daily-report-sdo-id");
        }
    }

    /**
     * LINK mode passes the template id to {@code sendHsmMessage} as a GraphQL {@code ID!}, so unlike
     * the document path it is never parsed as an int and needs no numeric check — an id that does not
     * exist comes back as a Glific error rather than a {@link NumberFormatException}.
     */
    private void validateDailyReportLinkTemplates() {
        if (isBlank(dailyReportSoLinkTemplateId)) {
            throw new IllegalStateException(
                    "glific.template.daily-report-so-link-id must be configured when daily-report delivery is"
                    + " enabled and notifications.daily-report.delivery-mode=LINK"
                    + " (set NOTIFICATIONS_DAILY_REPORT_DRY_RUN=true to suppress daily reports,"
                    + " or NOTIFICATIONS_DAILY_REPORT_DELIVERY_MODE=DOCUMENT to send the PDF as an attachment)");
        }
        validateLinkButtonBaseUrl();
    }

    /**
     * Cross-checks the optional {@code daily-report.link.button-base-url} against the prefix that
     * {@link #linkSuffix(String)} will strip. The approved template owns that prefix and it cannot be
     * changed after approval, so if the two disagree every button we send resolves against the wrong
     * host or path — a failure invisible on our side, because Glific accepts the send either way.
     */
    private void validateLinkButtonBaseUrl() {
        String expected = mediaUrlPrefix();
        if (isBlank(dailyReportLinkButtonBaseUrl)) {
            log.warn("[Glific] daily-report.link.button-base-url is not set. Button links will be built as"
                            + " '{}<bucket>/<file>.pdf' — confirm that prefix is exactly the one frozen into"
                            + " the approved LINK template, because a mismatch is only visible to the officer"
                            + " tapping the button. Set DAILY_REPORT_LINK_BUTTON_BASE_URL to have this"
                            + " checked at startup.", expected);
            return;
        }
        if (!expected.equals(dailyReportLinkButtonBaseUrl.trim())) {
            throw new IllegalStateException(
                    "daily-report.link.button-base-url is '" + dailyReportLinkButtonBaseUrl.trim()
                    + "' but minio.base-url yields the prefix '" + expected + "'. These must be identical:"
                    + " the first is the prefix frozen into the approved WhatsApp template, the second is"
                    + " what this service strips off the MinIO URL to build the button's variable."
                    + " A mismatch delivers a button pointing at the wrong host or path — most likely one"
                    + " environment was deployed with another environment's template id or MINIO_BASE_URL.");
        }
    }

    /**
     * Refuses to start when a document-sending purpose is live but {@code minio.base-url} is an
     * address Meta cannot reach. Both the escalation and the daily report attach a MinIO PDF, and a
     * wrong prefix here is invisible on our side: the upload succeeds, {@code createMessageMedia}
     * returns a media id, the send is accepted, and only the recipient discovers the document will
     * not open. Failing at startup keeps that from reaching officers at all.
     */
    private void validateMediaBaseUrl() {
        boolean sendsDocuments = !dailyReportDryRun || !escalationDryRun;
        if (!sendsDocuments) {
            return;
        }
        String reason = PublicUrlValidator.unreachableReason(mediaBaseUrl);
        if (reason != null) {
            throw new IllegalStateException(
                    "minio.base-url must be a publicly reachable URL when WhatsApp document delivery is"
                    + " enabled, but '" + mediaBaseUrl + "' is unusable: " + reason
                    + ". Glific hands this URL to Meta, which downloads it from the public internet and"
                    + " rejects internal addresses with '(#131053) … blocked by a destination filter'."
                    + " Set MINIO_BASE_URL to the public URL (e.g. https://jalsoochak.jjmbrain.in/minio)"
                    + " — note minio.endpoint stays internal, it is only the upload address."
                    + " In LINK mode Meta no longer downloads the file, but this same prefix is what the"
                    + " officer's phone opens and what is frozen into the approved template, so it must be"
                    + " publicly reachable there too.");
        }
    }

    private static void requireNumericTemplateId(String value, String propertyName) {
        try {
            Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    propertyName + " must be a numeric Glific template id but was '" + value + "'", e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private boolean isDryRun(boolean flag, String operation) {
        if (flag) {
            log.info("[Glific] DRY-RUN: suppressing {} — no message sent", operation);
            return true;
        }
        return false;
    }

    /** True only when every WhatsApp purpose is muted — no Glific call of any kind may be made. */
    private boolean isAllDryRun() {
        return whatsappDryRun && nudgeDryRun && escalationDryRun && dailyReportDryRun;
    }

    /**
     * Dry-run guard for {@link #optIn}. Opt-in registers the contact with Glific and sends the
     * recipient nothing, but it is the prerequisite for <em>every</em> delivery: without a real
     * contact id, {@code receiverId} is 0 and Glific rejects the send with
     * {@code "Receiver does not exist"}. It therefore follows {@link #isAllDryRun()} rather than the
     * master {@link #whatsappDryRun} flag — muting account operations (OTP / welcome / language)
     * must not break a purpose that is explicitly switched live, e.g.
     * {@code NOTIFICATIONS_WHATSAPP_DRY_RUN=true} with {@code NOTIFICATIONS_DAILY_REPORT_DRY_RUN=false}.
     * A lone {@code NOTIFICATIONS_WHATSAPP_DRY_RUN=true} still mutes opt-in, because every purpose
     * flag defaults to it.
     */
    private boolean isOptInDryRun() {
        return isAllDryRun();
    }

    /**
     * Fails fast when a send or flow start is attempted without a resolved Glific contact id. A contact
     * id of 0 (or null) is what an opt-in that was suppressed or that returned nothing leaves behind;
     * passing it to Glific costs a round-trip (a media upload, for the document templates) and comes
     * back as "Receiver does not exist" or a bare {@code success=false}, which then looks like a
     * template or flow problem in the logs. Called before the first {@code client.execute} of every
     * contact-addressed operation, so the diagnosis reads correctly at the point of failure.
     */
    private static void requireContactId(Long contactId, String operation) {
        if (contactId == null || contactId <= 0) {
            throw new IllegalArgumentException(
                    operation + " requires a resolved Glific contact id but got " + contactId
                    + " — the contact was never opted in (check NOTIFICATIONS_* dry-run flags)");
        }
    }

    @Value("${glific.media.escalation-caption:Escalations}")
    private String escalationCaption;

    @Value("${glific.media.escalation-thumbnail:}")
    private String escalationThumbnail;

    @Value("${glific.media.daily-report-caption:Daily Water Service Situation Report}")
    private String dailyReportCaption;

    /** Date suffix of the recipient-visible document name, e.g. "13-08-2026". */
    private static final DateTimeFormatter DOCUMENT_NAME_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    /**
     * Sends the login OTP HSM template to an officer.
     * Template variable {{1}} = OTP.
     *
     * @param contactId Glific contact ID of the officer
     * @param otp       one-time password for template {@code {{1}}}
     */
    public void sendLoginOtpHsm(Long contactId, String otp) {
        if (isDryRun(whatsappDryRun, "sendLoginOtpHsm")) return;
        requireContactId(contactId, "sendLoginOtpHsm");
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
        if (isDryRun(isOptInDryRun(), "optIn")) return 0L;
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
        if (isDryRun(nudgeDryRun, "sendNudgeHsm")) return;
        requireContactId(contactId, "sendNudgeHsm");
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
        return uploadMediaInternal(publicUrl, escalationCaption, escalationDryRun);
    }

    private String uploadMediaInternal(String publicUrl, String caption, boolean dryRun) {
        if (isDryRun(dryRun, "uploadMedia")) return "dry-run-media-id";
        // Last line of defence behind the startup check: a URL Meta cannot fetch produces a media id
        // and an accepted send, so the failure would otherwise surface only as an attachment the
        // officer cannot open. Refuse before the round-trip instead.
        String reason = PublicUrlValidator.unreachableReason(publicUrl);
        if (reason != null) {
            throw new IllegalStateException(
                    "Refusing to register media URL '" + publicUrl + "' with Glific: " + reason
                    + ". Meta downloads this URL from the public internet — set MINIO_BASE_URL to the"
                    + " public MinIO address.");
        }
        log.debug("[Glific] Uploading media");
        JsonNode response = client.execute(CREATE_MESSAGE_MEDIA_MUTATION, Map.of(
                        "input", Map.of(
                                "url", publicUrl,
                                "source_url", publicUrl,
                                "caption", caption,
                                "thumbnail", escalationThumbnail,
                                "isTemplateMedia", true)));
        checkErrors(response, "createMessageMedia");
        String mediaId = response.path("createMessageMedia").path("messageMedia").path("id").asText();
        log.info("[Glific] Media uploaded, mediaId={}", mediaId);
        return mediaId;
    }

    /**
     * Sends the Daily Water Service Situation Report to an officer, in whichever shape
     * {@code notifications.daily-report.delivery-mode} selects:
     * <ul>
     *   <li>{@link DailyReportDeliveryMode#DOCUMENT} — the PDF as a document HSM. Meta downloads the
     *       MinIO URL itself, which the India-only firewall in front of production MinIO blocks.</li>
     *   <li>{@link DailyReportDeliveryMode#LINK} — a text HSM whose "View Report" button carries the
     *       MinIO path. Meta fetches nothing; the officer's phone opens the PDF when they tap it.</li>
     * </ul>
     * The dry-run guard and the contact-id check are shared, so a suppressed report costs no work and
     * a missing contact id costs no Glific round-trip in either mode.
     *
     * @param contactId       Glific contact id of the officer
     * @param minioUrl        publicly reachable URL of the report PDF
     * @param officerUserType SECTION_OFFICER | SUB_DIVISIONAL_OFFICER
     * @param reportDate      the day the report's data covers (D-1). In DOCUMENT mode it is appended
     *                        to the document name the recipient sees, and null falls back to the bare
     *                        caption; in LINK mode it is template variable {{2}} and is required
     * @param officerName     the officer's name, template variable {{1}} in LINK mode; blank or null
     *                        degrades to "Officer". Unused in DOCUMENT mode
     * @return the Glific message id, template id and mode of the accepted send — the join key that
     *         lets the delivery status Gupshup and Meta report back to Glific later be matched to this
     *         officer. A dry-run returns {@link GlificSendResult#suppressed} with a null message id
     */
    public GlificSendResult sendDailyReportHsm(Long contactId, String minioUrl, String officerUserType,
                                               LocalDate reportDate, String officerName) {
        if (isDryRun(dailyReportDryRun, "sendDailyReportHsm")) {
            // Reported leniently: a suppressed send must not start failing because the mode property
            // has a typo, which is the behaviour before this method returned anything at all.
            return GlificSendResult.suppressed(deliveryModeOrNull());
        }
        // Checked before the media upload so a missing contact id costs no Glific round-trip.
        requireContactId(contactId, "sendDailyReportHsm");

        return switch (deliveryMode()) {
            case DOCUMENT -> sendDailyReportDocumentHsm(contactId, minioUrl, officerUserType, reportDate);
            case LINK -> sendDailyReportLinkHsm(contactId, minioUrl, officerUserType, reportDate, officerName);
        };
    }

    /** The original two-step document send: register the PDF as media, then send it as the header. */
    private GlificSendResult sendDailyReportDocumentHsm(Long contactId, String minioUrl, String officerUserType,
                                                        LocalDate reportDate) {
        String templateId = resolveDailyReportTemplateId(officerUserType);
        String mediaId = uploadMediaInternal(minioUrl, dailyReportDocumentName(reportDate), dailyReportDryRun);

        Map<String, Object> input = new HashMap<>();
        input.put("templateId", Integer.parseInt(templateId));
        input.put("receiverId", contactId.intValue());
        input.put("isHsm", true);
        input.put("params", List.of());
        if (mediaId != null && !mediaId.isBlank()) {
            input.put("mediaId", Integer.parseInt(mediaId));
        }

        JsonNode response = client.execute(CREATE_AND_SEND_MESSAGE_MUTATION, Map.of("input", input));
        checkErrors(response, "createAndSendMessage");
        String messageId = extractMessageId(response, "createAndSendMessage");
        log.debug("[Glific] Daily report HSM sent to contactId={} glificMsgId={}", contactId, messageId);
        return new GlificSendResult(messageId, templateId, DailyReportDeliveryMode.DOCUMENT);
    }

    /**
     * The link send: one {@code sendHsmMessage} and no media step at all. The template's button URL is
     * a fixed prefix plus a variable that Meta appends to it, so the only thing that travels per
     * message is the part after that prefix — {@code escalation-reports/daily_report_….pdf}. Keeping
     * the bucket inside the variable rather than inside the frozen prefix is what makes a future bucket
     * rename a configuration change instead of a new template approval.
     *
     * <p>Parameter order matters and is not ours to choose: Glific forwards the list to Gupshup as a
     * flat {@code params} array filled in order of occurrence, body variables first and the button's
     * URL suffix last.</p>
     */
    private GlificSendResult sendDailyReportLinkHsm(Long contactId, String minioUrl, String officerUserType,
                                                    LocalDate reportDate, String officerName) {
        String templateId = resolveDailyReportLinkTemplateId(officerUserType);
        if (isBlank(templateId)) {
            throw new IllegalStateException(
                    "glific.template.daily-report-so-link-id is not configured — LINK mode cannot send");
        }
        // Required, unlike the document path where a null date only costs the date in the filename:
        // here it is template variable {{2}} and Glific rejects a null parameter outright.
        if (reportDate == null) {
            throw new IllegalArgumentException(
                    "sendDailyReportHsm in LINK mode requires the report date — it is template variable {{2}}");
        }
        String urlSuffix = linkSuffix(minioUrl);
        String name = isBlank(officerName) ? "Officer" : officerName.trim();
        String role = isBlank(officerUserType) ? "UNKNOWN" : officerUserType.trim();

        JsonNode response = client.execute(NUDGE_HSM_MUTATION, Map.of(
                "templateId", templateId,
                "receiverId", contactId,
                "parameters", List.of(name, reportDate.format(DOCUMENT_NAME_DATE), urlSuffix)));
        checkErrors(response, "sendHsmMessage");
        String messageId = extractMessageId(response, "sendHsmMessage");
        log.info("[Glific] Daily report HSM sent mode=LINK role={} glificMsgId={} templateId={}",
                role, messageId, templateId);
        log.debug("[Glific] Daily report link HSM sent to contactId={} suffix={}", contactId, urlSuffix);
        return new GlificSendResult(messageId, templateId, DailyReportDeliveryMode.LINK);
    }

    /**
     * The value of the LINK template's dynamic-URL variable: the MinIO URL with the prefix the template
     * already owns stripped off, e.g.
     * {@code escalation-reports/daily_report_SECTION_OFFICER_16714_2026-08-19.pdf}.
     *
     * <p>Throws rather than guessing when the URL does not sit under the configured prefix. Meta
     * appends this value to the frozen prefix verbatim, so a URL from some other host would silently
     * produce a button pointing at a path that does not exist — and Glific would accept the send.
     * Refusing here turns that into a failed delivery that gets logged and retried.</p>
     */
    String linkSuffix(String minioUrl) {
        String prefix = mediaUrlPrefix();
        if (minioUrl == null || !minioUrl.startsWith(prefix)) {
            throw new IllegalStateException(
                    "Cannot build the daily report link: '" + minioUrl + "' does not start with the"
                    + " template's URL prefix '" + prefix + "' (from minio.base-url). Meta appends the"
                    + " remainder to that prefix verbatim, so the button would point somewhere that does"
                    + " not exist. Check MINIO_BASE_URL against the approved template.");
        }
        String suffix = minioUrl.substring(prefix.length());
        if (suffix.isBlank()) {
            throw new IllegalStateException(
                    "Cannot build the daily report link: '" + minioUrl + "' is the bare prefix '" + prefix
                    + "' with no object path after it");
        }
        return suffix;
    }

    /**
     * {@code minio.base-url} with exactly one trailing slash — the prefix the LINK template owns.
     * Mirrors the trailing-slash trimming in {@code MinioStorageService.publicUrlFor}, because the two
     * have to agree on where the prefix ends for {@link #linkSuffix(String)} to strip it: that value is
     * hand-written per environment and one ending in {@code /minio/} would otherwise leave a leading
     * slash on the suffix.
     */
    private String mediaUrlPrefix() {
        String prefix = mediaBaseUrl == null ? "" : mediaBaseUrl.trim();
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + "/";
    }

    /** The configured delivery mode; parsed on use so an unknown value fails loudly wherever it is read. */
    private DailyReportDeliveryMode deliveryMode() {
        return DailyReportDeliveryMode.from(dailyReportDeliveryMode);
    }

    /**
     * The configured delivery mode, or {@code null} when the property is unparseable.
     *
     * <p>Used only on the dry-run path, which reports the mode for the log line but must not start
     * throwing on a typo it never used to read. A live send still goes through {@link #deliveryMode()}
     * and still fails loudly.</p>
     */
    private DailyReportDeliveryMode deliveryModeOrNull() {
        try {
            return deliveryMode();
        } catch (IllegalStateException e) {
            log.warn("[Glific] Suppressed daily report: delivery-mode '{}' is not DOCUMENT or LINK;"
                    + " reporting mode as unknown", dailyReportDeliveryMode);
            return null;
        }
    }

    /**
     * The document name the officer sees for the report in WhatsApp: the configured caption followed by
     * the date of the data it covers, e.g. {@code "Daily Water Service Situation Report 13-08-2026"}
     * for a report delivered on 14 August. That is the report day (D-1), <em>not</em> the generation
     * day — the recipient files these by the day they describe.
     *
     * <p>Glific surfaces this {@code createMessageMedia} caption as the document's filename, so it is
     * the only place the recipient-visible name is set. Falls back to the bare caption when the date is
     * unknown, so a missing date degrades the name rather than breaking delivery.</p>
     */
    String dailyReportDocumentName(LocalDate reportDate) {
        return reportDate == null
                ? dailyReportCaption
                : dailyReportCaption + " " + reportDate.format(DOCUMENT_NAME_DATE);
    }

    /**
     * Whether daily-report delivery is actually switched on. Lets a caller tell a configuration state
     * (report muted, so a contact id of 0 is expected and harmless) from a genuine failure (report live
     * but the officer has no Glific contact) without duplicating the dry-run properties.
     */
    public boolean isDailyReportDeliveryEnabled() {
        return !dailyReportDryRun;
    }

    /** SUB_DIVISIONAL_OFFICER uses its own template when configured; otherwise falls back to the SO template. */
    private String resolveDailyReportTemplateId(String officerUserType) {
        if (officerUserType != null && officerUserType.trim().equalsIgnoreCase("SUB_DIVISIONAL_OFFICER")
                && !isBlank(dailyReportSdoTemplateId)) {
            return dailyReportSdoTemplateId;
        }
        return dailyReportSoTemplateId;
    }

    /**
     * Same SDO→SO fallback as {@link #resolveDailyReportTemplateId} but over the LINK templates, so a
     * deployment that has approved only one template still delivers to both roles.
     */
    private String resolveDailyReportLinkTemplateId(String officerUserType) {
        if (officerUserType != null && officerUserType.trim().equalsIgnoreCase("SUB_DIVISIONAL_OFFICER")
                && !isBlank(dailyReportSdoLinkTemplateId)) {
            return dailyReportSdoLinkTemplateId;
        }
        return dailyReportSoLinkTemplateId;
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
        if (isDryRun(escalationDryRun, "sendEscalationHsm")) return;
        // Checked before the media upload so a missing contact id costs no Glific round-trip.
        requireContactId(contactId, "sendEscalationHsm");

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
     * @param date         today's date string; passed as {@code defaultResults} key {@code "state"}
     * @throws IllegalStateException if {@code glific.flow.nudge-id} is blank
     * @throws RuntimeException      if Glific returns GraphQL errors or {@code success=false}
     */
    public void startNudgeFlow(Long contactId, String operatorName, String date) {
        if (isDryRun(nudgeDryRun, "startNudgeFlow")) return;
        requireContactId(contactId, "startNudgeFlow");
        if (nudgeFlowId == null || nudgeFlowId.isBlank()) {
            throw new IllegalStateException("glific.flow.nudge-id is not configured");
        }

        String defaultResults;
        try {
            defaultResults = objectMapper.writeValueAsString(
                    Map.of("name", operatorName, "date", date));
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
        if (isDryRun(whatsappDryRun, "startWelcomeFlow")) return;
        requireContactId(contactId, "startWelcomeFlow");
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
        if (isDryRun(whatsappDryRun, "updateContactLanguage")) return;
        JsonNode response = client.execute(UPDATE_CONTACT_MUTATION, Map.of(
                "id", contactId,
                "input", Map.of("language_id", glificLanguageId)));
        checkErrors(response, "updateContact");
        log.debug("[Glific] Contact language updated contactId={} languageId={}", contactId, glificLanguageId);
    }

    /**
     * Throws when a mutation came back with a non-empty {@code errors} array.
     *
     * <p>Throws {@link GlificMutationException} rather than a bare {@link RuntimeException}, with the
     * message unchanged: callers that only catch {@code Exception} behave exactly as before, while
     * those that need to know <em>which</em> mutation failed (to tag a
     * {@link GlificSendStage}) can read it off the exception instead of parsing the text.</p>
     */
    private void checkErrors(JsonNode response, String mutationKey) {
        JsonNode mutationNode = response.path(mutationKey);
        if (mutationNode.isMissingNode() || mutationNode.isNull()) {
            throw new GlificMutationException(mutationKey, null,
                    "Glific GraphQL response missing key: " + mutationKey);
        }
        JsonNode errors = mutationNode.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            String msg = errors.toString();
            log.error("[Glific] GraphQL errors in {}: {}", mutationKey, msg);
            throw new GlificMutationException(mutationKey, errors.path(0).path("key").asText(null),
                    "Glific GraphQL error in " + mutationKey + ": " + msg);
        }
    }

    /**
     * Lifts {@code message.id} out of a send response, refusing one that came back without it.
     *
     * <p>Both send mutations already return it and both used to discard it. It is the only join key
     * between a report we sent and the delivery status Glific later receives from Gupshup, so a send
     * that produced no id is not a success to report: it counted as delivered and dropped out of
     * reconciliation in the same step, invisibly. Throwing turns that into a logged, counted outcome.</p>
     *
     * <p>A suppressed send never reaches here — {@link #sendDailyReportHsm} returns
     * {@link GlificSendResult#suppressed} before any mutation runs, and that path keeps its null
     * message id. So a missing id at this point is always the live anomaly, never the dry-run.</p>
     */
    private static String extractMessageId(JsonNode response, String mutationKey) {
        String id = response.path(mutationKey).path("message").path("id").asText(null);
        if (id == null || id.isBlank()) {
            throw new GlificMissingMessageIdException(mutationKey);
        }
        return id;
    }

    /**
     * Serializes flow defaultResults as JSON for name and state parameters.
     * Both values are null-safe (converted to empty string if null).
     *
     * @param name  operator name (null-safe)
     * @param state context value (e.g., tenant state or date) (null-safe)
     * @return JSON string representation of defaultResults with keys "name" and "state"
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