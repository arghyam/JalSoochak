package org.arghyam.jalsoochak.message.channel.provider.glific;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.message.channel.provider.ReportDeliveryMode;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSendException;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSendResult;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSendStage;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSender;
import org.arghyam.jalsoochak.message.util.PublicUrlValidator;
import org.springframework.stereotype.Component;

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
 *   <li>Upload the stored PDF's public URL via {@code createMessageMedia} → receive {@code mediaId}.</li>
 *   <li>Send via {@code createAndSendMessage} with {@code mediaId} (document header)
 *       and {@code parameters[0]} = localized body text.</li>
 * </ol>
 * </p>
 * <p>Daily report: either shape, chosen by {@code notifications.daily-report.delivery-mode} — see
 * {@link ReportDeliveryMode}. {@code LINK} mode is a single {@code sendHsmMessage} with
 * {{1}} = officer name, {{2}} = report date and the button's URL suffix last.</p>
 *
 * <p>Configured entirely through {@link GlificWhatsAppSettings}; only the GraphQL client reads its
 * own connection properties. A mutation Glific rejects surfaces as a {@link GlificMutationException},
 * which is the port's {@link WhatsAppSendException} with the stage already set, so no caller needs
 * to know a mutation name.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GlificWhatsAppSender implements WhatsAppSender {

    private static final String OPTIN_MUTATION = """
            mutation optinContact($phone: String!) {
              optinContact(phone: $phone) {
                contact { id }
                errors { key message }
              }
            }""";

    /**
     * The plain HSM send. Shared by every template whose variables are all text — the nudge, the
     * login OTP and the daily report in {@link ReportDeliveryMode#LINK} mode. Only the document
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
    private final GlificWhatsAppSettings settings;

    @PostConstruct
    void validateTemplates() {
        if (isAllDryRun()) {
            log.warn("[WhatsApp] DRY-RUN mode active — all Glific API calls will be suppressed."
                    + " Set NOTIFICATIONS_WHATSAPP_DRY_RUN=false for production.");
            return;
        }
        GlificWhatsAppSettings.DryRun dryRun = settings.dryRun();
        if (dryRun.nudge() || dryRun.escalation() || dryRun.dailyReport() || dryRun.weeklyReport()
                || dryRun.whatsapp()) {
            log.warn("[WhatsApp] Partial DRY-RUN — nudge={}, escalation={}, daily-report={},"
                            + " weekly-report={}, account-ops(OTP/welcome/language)={}. Contact opt-in stays"
                            + " live because at least one delivery purpose is enabled.",
                    dryRun.nudge(), dryRun.escalation(), dryRun.dailyReport(), dryRun.weeklyReport(),
                    dryRun.whatsapp());
        }
        // Validate only the templates whose delivery is enabled.
        if (!dryRun.nudge() && isBlank(settings.flows().nudge())) {
            throw new IllegalStateException(
                    "whatsapp.flow.nudge-id (WHATSAPP_NUDGE_FLOW_ID) must be configured when nudge delivery is"
                    + " enabled (set NOTIFICATIONS_NUDGE_DRY_RUN=true to suppress nudges)");
        }
        if (!dryRun.escalation() && isBlank(settings.templates().escalation())) {
            throw new IllegalStateException(
                    "whatsapp.template.escalation-id (WHATSAPP_ESCALATION_TEMPLATE_ID) must be configured when"
                    + " escalation delivery is enabled"
                    + " (set NOTIFICATIONS_ESCALATION_DRY_RUN=true to suppress escalations)");
        }
        if (!dryRun.whatsapp() && isBlank(settings.flows().welcome())) {
            throw new IllegalStateException(
                    "whatsapp.flow.welcome-id (WHATSAPP_WELCOME_FLOW_ID) must be configured");
        }
        validateAccountAndReportTemplates();
    }

    private void validateAccountAndReportTemplates() {
        if (!settings.dryRun().whatsapp() && isBlank(settings.templates().loginOtp())) {
            throw new IllegalStateException(
                    "whatsapp.template.login-otp-id (WHATSAPP_LOGIN_OTP_TEMPLATE_ID) must be configured —"
                    + " SEND_LOGIN_OTP events cannot be delivered without it");
        }
        if (!settings.dryRun().dailyReport()) {
            // Only the templates the configured mode actually sends are required. A LINK deployment
            // never reads the document ids and vice versa, so demanding both would force every
            // environment to carry configuration it does not use.
            switch (deliveryMode()) {
                case DOCUMENT -> validateDailyReportDocumentTemplates();
                case LINK -> validateDailyReportLinkTemplates();
            }
        }
        if (!settings.dryRun().weeklyReport()) {
            validateWeeklyReportTemplates();
        }
        validateMediaBaseUrl();
    }

    /**
     * Refuses to start when weekly delivery is live without an approved template.
     *
     * <p>Without this the job would run on each tenant's weekly schedule, generate and upload a PDF, and then fail per
     * message at send time — a failure discovered from the logs rather than at deploy, with officers
     * silently receiving nothing meanwhile.</p>
     */
    private void validateWeeklyReportTemplates() {
        if (isBlank(settings.templates().weeklyReportSoLink())) {
            throw new IllegalStateException(
                    "whatsapp.template.weekly-report-so-link-id (WHATSAPP_WEEKLY_REPORT_SO_LINK_TEMPLATE_ID)"
                    + " must be configured when weekly-report delivery"
                    + " is enabled (set NOTIFICATIONS_WEEKLY_REPORT_DRY_RUN=true to generate and upload the"
                    + " reports without sending them, until the Meta template is approved)");
        }
        validateLinkButtonBaseUrl();
    }

    private void validateDailyReportDocumentTemplates() {
        if (isBlank(settings.templates().dailyReportSo())) {
            throw new IllegalStateException(
                    "whatsapp.template.daily-report-so-id (WHATSAPP_DAILY_REPORT_SO_TEMPLATE_ID) must be"
                    + " configured when daily-report delivery is enabled"
                    + " (set NOTIFICATIONS_DAILY_REPORT_DRY_RUN=true to suppress daily reports)");
        }
        // sendDailyReportDocumentHsm does Integer.parseInt on the resolved template id, so fail fast at
        // startup on a non-numeric id rather than per-message (retry → DLT) at delivery time.
        requireNumericTemplateId(settings.templates().dailyReportSo(),
                "whatsapp.template.daily-report-so-id (WHATSAPP_DAILY_REPORT_SO_TEMPLATE_ID)");
        // The SDO id is optional (resolveDailyReportTemplateId falls back to the SO template),
        // so validate it only when it has been configured.
        if (!isBlank(settings.templates().dailyReportSdo())) {
            requireNumericTemplateId(settings.templates().dailyReportSdo(),
                    "whatsapp.template.daily-report-sdo-id (WHATSAPP_DAILY_REPORT_SDO_TEMPLATE_ID)");
        }
    }

    /**
     * LINK mode passes the template id to {@code sendHsmMessage} as a GraphQL {@code ID!}, so unlike
     * the document path it is never parsed as an int and needs no numeric check — an id that does not
     * exist comes back as a Glific error rather than a {@link NumberFormatException}.
     */
    private void validateDailyReportLinkTemplates() {
        if (isBlank(settings.templates().dailyReportSoLink())) {
            throw new IllegalStateException(
                    "whatsapp.template.daily-report-so-link-id (WHATSAPP_DAILY_REPORT_SO_LINK_TEMPLATE_ID)"
                    + " must be configured when daily-report delivery is"
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
        if (isBlank(settings.linkButtonBaseUrl())) {
            log.warn("[WhatsApp] daily-report.link.button-base-url is not set. Button links will be built as"
                            + " '{}<bucket>/<file>.pdf' — confirm that prefix is exactly the one frozen into"
                            + " the approved LINK template, because a mismatch is only visible to the officer"
                            + " tapping the button. Set DAILY_REPORT_LINK_BUTTON_BASE_URL to have this"
                            + " checked at startup.", expected);
            return;
        }
        if (!expected.equals(settings.linkButtonBaseUrl().trim())) {
            throw new IllegalStateException(
                    "daily-report.link.button-base-url is '" + settings.linkButtonBaseUrl().trim()
                    + "' but storage.public-base-url yields the prefix '" + expected + "'. These must be"
                    + " identical: the first is the prefix frozen into the approved WhatsApp template, the"
                    + " second is what this service strips off the report URL to build the button's variable."
                    + " A mismatch delivers a button pointing at the wrong host or path — most likely one"
                    + " environment was deployed with another environment's template id or"
                    + " STORAGE_PUBLIC_BASE_URL.");
        }
    }

    /**
     * Refuses to start when a purpose that hands out a report URL is live but
     * {@code storage.public-base-url} is an address the recipient cannot reach. The escalation and the
     * daily report attach a stored PDF, and a wrong prefix here is invisible on our side: the upload succeeds,
     * {@code createMessageMedia} returns a media id, the send is accepted, and only the recipient
     * discovers the document will not open. Failing at startup keeps that from reaching officers at
     * all.
     *
     * <p>The weekly report counts too, even though it is LINK-only and Meta never downloads the
     * file: the same prefix is what the officer's phone opens and what is frozen into the approved
     * template. Leaving it out meant a deployment that sends only weekly reports — daily and
     * escalations muted — started happily with an internal prefix and delivered buttons that lead
     * nowhere.</p>
     */
    private void validateMediaBaseUrl() {
        GlificWhatsAppSettings.DryRun dryRun = settings.dryRun();
        boolean handsOutReportUrls = !dryRun.dailyReport() || !dryRun.escalation() || !dryRun.weeklyReport();
        if (!handsOutReportUrls) {
            return;
        }
        String reason = PublicUrlValidator.unreachableReason(settings.media().baseUrl());
        if (reason != null) {
            throw new IllegalStateException(
                    "storage.public-base-url must be a publicly reachable URL when WhatsApp document delivery is"
                    + " enabled, but '" + settings.media().baseUrl() + "' is unusable: " + reason
                    + ". Glific hands this URL to Meta, which downloads it from the public internet and"
                    + " rejects internal addresses with '(#131053) … blocked by a destination filter'."
                    + " Set STORAGE_PUBLIC_BASE_URL to the public URL (e.g. https://jalsoochak.jjmbrain.in/minio)"
                    + " — note storage.endpoint stays internal, it is only the upload address."
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
            log.info("[WhatsApp] DRY-RUN: suppressing {} — no message sent", operation);
            return true;
        }
        return false;
    }

    /**
     * True only when every WhatsApp purpose is muted — no Glific call of any kind may be made.
     *
     * <p>Every purpose must be listed. Contact opt-in is gated on this, and opt-in is what yields the
     * {@code receiverId} each delivery needs: omitting a live purpose here would suppress opt-in while
     * that purpose still tried to send, producing {@code receiverId=0} and a Glific
     * "Receiver does not exist" for every message.</p>
     */
    private boolean isAllDryRun() {
        return settings.dryRun().allPurposes();
    }

    /**
     * Dry-run guard for {@link #optIn}. Opt-in registers the contact with Glific and sends the
     * recipient nothing, but it is the prerequisite for <em>every</em> delivery: without a real
     * contact id, {@code receiverId} is 0 and Glific rejects the send with
     * {@code "Receiver does not exist"}. It therefore follows {@link #isAllDryRun()} rather than the
     * master {@link GlificWhatsAppSettings.DryRun#whatsapp() whatsapp} flag — muting account operations
     * (OTP / welcome / language) must not break a purpose that is explicitly switched live, e.g.
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

    /** Date suffix of the recipient-visible document name, e.g. "13-08-2026". */
    private static final DateTimeFormatter DOCUMENT_NAME_DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    /**
     * Sends the login OTP HSM template to an officer.
     * Template variable {{1}} = OTP.
     *
     * @param contactId Glific contact ID of the officer
     * @param otp       one-time password for template {@code {{1}}}
     */
    @Override
    public void sendLoginOtpHsm(Long contactId, String otp) {
        if (isDryRun(settings.dryRun().whatsapp(), "sendLoginOtpHsm")) return;
        requireContactId(contactId, "sendLoginOtpHsm");
        if (isBlank(settings.templates().loginOtp())) {
            throw new IllegalStateException("whatsapp.template.login-otp-id is not configured");
        }
        JsonNode response = client.execute(NUDGE_HSM_MUTATION, Map.of(
                "templateId", settings.templates().loginOtp(),
                "receiverId", contactId,
                "parameters", List.of(otp)));
        checkErrors(response, "sendHsmMessage");
        log.debug("[WhatsApp] Login OTP HSM sent to contactId={}", contactId);
    }

    /**
     * Opts in the contact by phone number and returns the Glific contact ID.
     * Phone must be in E.164 format (e.g., 919876543210).
     */
    @Override
    public Long optIn(String phone) {
        if (isDryRun(isOptInDryRun(), "optIn")) return 0L;
        log.debug("[WhatsApp] Opting in contact");
        JsonNode response = client.execute(OPTIN_MUTATION, Map.of("phone", phone));
        checkErrors(response, "optinContact");
        return response.path("optinContact").path("contact").path("id").asLong();
    }

    /**
     * Sends the nudge HSM template to the contact.
     * Template variable {{1}} = operator name, {{2}} = today's date.
     */
    @Override
    public void sendNudgeHsm(Long contactId, String operatorName, String date) {
        if (isDryRun(settings.dryRun().nudge(), "sendNudgeHsm")) return;
        requireContactId(contactId, "sendNudgeHsm");
        JsonNode response = client.execute(NUDGE_HSM_MUTATION, Map.of(
                "templateId", settings.templates().nudge(),
                "receiverId", contactId,
                "parameters", List.of(operatorName, date)));
        checkErrors(response, "sendHsmMessage");
        log.debug("[WhatsApp] Nudge HSM sent to contactId={}", contactId);
    }

    /**
     * Registers a document with Glific by its publicly reachable URL, the first step of every document
     * template send. Not dry-run guarded: both callers return before reaching it when their purpose is
     * suppressed.
     *
     * @param publicUrl publicly reachable URL of the file
     * @param caption   the name the recipient sees for the document
     * @return Glific {@code messageMedia.id}, sent as the template's document header
     */
    private String uploadMedia(String publicUrl, String caption) {
        // Last line of defence behind the startup check: a URL Meta cannot fetch produces a media id
        // and an accepted send, so the failure would otherwise surface only as an attachment the
        // officer cannot open. Refuse before the round-trip instead.
        String reason = PublicUrlValidator.unreachableReason(publicUrl);
        if (reason != null) {
            throw new IllegalStateException(
                    "Refusing to register media URL '" + publicUrl + "' with Glific: " + reason
                    + ". Meta downloads this URL from the public internet — set STORAGE_PUBLIC_BASE_URL to"
                    + " the public address of the object store.");
        }
        log.debug("[WhatsApp] Uploading media");
        JsonNode response = client.execute(CREATE_MESSAGE_MEDIA_MUTATION, Map.of(
                        "input", Map.of(
                                "url", publicUrl,
                                "source_url", publicUrl,
                                "caption", caption,
                                "thumbnail", settings.media().escalationThumbnail(),
                                "isTemplateMedia", true)));
        checkErrors(response, "createMessageMedia");
        String mediaId = response.path("createMessageMedia").path("messageMedia").path("id").asText();
        log.info("[WhatsApp] Media uploaded, mediaId={}", mediaId);
        return mediaId;
    }

    /**
     * Sends the Daily Water Service Situation Report to an officer, in whichever shape
     * {@code notifications.daily-report.delivery-mode} selects:
     * <ul>
     *   <li>{@link ReportDeliveryMode#DOCUMENT} — the PDF as a document HSM. Meta downloads the
     *       report URL itself, which the India-only firewall in front of the production object store
     *       blocks.</li>
     *   <li>{@link ReportDeliveryMode#LINK} — a text HSM whose "View Report" button carries the
     *       report's path. Meta fetches nothing; the officer's phone opens the PDF when they tap it.</li>
     * </ul>
     * The dry-run guard and the contact-id check are shared, so a suppressed report costs no work and
     * a missing contact id costs no Glific round-trip in either mode.
     *
     * @param contactId       Glific contact id of the officer
     * @param documentUrl     publicly reachable URL of the report PDF
     * @param officerUserType SECTION_OFFICER | SUB_DIVISIONAL_OFFICER
     * @param reportDate      the day the report's data covers (D-1). In DOCUMENT mode it is appended
     *                        to the document name the recipient sees, and null falls back to the bare
     *                        caption; in LINK mode it is template variable {{2}} and is required
     * @param officerName     the officer's name, template variable {{1}} in LINK mode; blank or null
     *                        degrades to "Officer". Unused in DOCUMENT mode
     * @return the Glific message id, template id and mode of the accepted send — the join key that
     *         lets the delivery status Gupshup and Meta report back to Glific later be matched to this
     *         officer. A dry-run returns {@link WhatsAppSendResult#suppressed} with a null message id
     */
    @Override
    public WhatsAppSendResult sendDailyReportHsm(Long contactId, String documentUrl, String officerUserType,
                                                 LocalDate reportDate, String officerName) {
        if (isDryRun(settings.dryRun().dailyReport(), "sendDailyReportHsm")) {
            // Reported leniently: a suppressed send must not start failing because the mode property
            // has a typo, which is the behaviour before this method returned anything at all.
            return WhatsAppSendResult.suppressed(deliveryModeOrNull());
        }
        // Checked before the media upload so a missing contact id costs no Glific round-trip.
        requireContactId(contactId, "sendDailyReportHsm");

        return switch (deliveryMode()) {
            case DOCUMENT -> sendDailyReportDocumentHsm(contactId, documentUrl, officerUserType, reportDate);
            case LINK -> sendDailyReportLinkHsm(contactId, documentUrl, officerUserType, reportDate, officerName);
        };
    }

    /** The original two-step document send: register the PDF as media, then send it as the header. */
    private WhatsAppSendResult sendDailyReportDocumentHsm(Long contactId, String documentUrl, String officerUserType,
                                                          LocalDate reportDate) {
        String templateId = resolveDailyReportTemplateId(officerUserType);
        String mediaId = uploadMedia(documentUrl, dailyReportDocumentName(reportDate));

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
        log.debug("[WhatsApp] Daily report HSM sent to contactId={} providerMsgId={}", contactId, messageId);
        return new WhatsAppSendResult(messageId, templateId, ReportDeliveryMode.DOCUMENT);
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
    private WhatsAppSendResult sendDailyReportLinkHsm(Long contactId, String documentUrl, String officerUserType,
                                                      LocalDate reportDate, String officerName) {
        String templateId = resolveDailyReportLinkTemplateId(officerUserType);
        if (isBlank(templateId)) {
            throw new IllegalStateException(
                    "whatsapp.template.daily-report-so-link-id is not configured — LINK mode cannot send");
        }
        // Required, unlike the document path where a null date only costs the date in the filename:
        // here it is template variable {{2}} and Glific rejects a null parameter outright.
        if (reportDate == null) {
            throw new IllegalArgumentException(
                    "sendDailyReportHsm in LINK mode requires the report date — it is template variable {{2}}");
        }
        String urlSuffix = linkSuffix(documentUrl);
        String name = isBlank(officerName) ? "Officer" : officerName.trim();
        String role = isBlank(officerUserType) ? "UNKNOWN" : officerUserType.trim();

        JsonNode response = client.execute(NUDGE_HSM_MUTATION, Map.of(
                "templateId", templateId,
                "receiverId", contactId,
                "parameters", List.of(name, reportDate.format(DOCUMENT_NAME_DATE), urlSuffix)));
        checkErrors(response, "sendHsmMessage");
        String messageId = extractMessageId(response, "sendHsmMessage");
        log.info("[WhatsApp] Daily report HSM sent mode=LINK role={} providerMsgId={} templateId={}",
                role, messageId, templateId);
        log.debug("[WhatsApp] Daily report link HSM sent to contactId={} suffix={}", contactId, urlSuffix);
        return new WhatsAppSendResult(messageId, templateId, ReportDeliveryMode.LINK);
    }

    /**
     * The value of the LINK template's dynamic-URL variable: the report URL with the prefix the template
     * already owns stripped off, e.g.
     * {@code escalation-reports/daily_report_SECTION_OFFICER_16714_2026-08-19.pdf}.
     *
     * <p>Throws rather than guessing when the URL does not sit under the configured prefix. Meta
     * appends this value to the frozen prefix verbatim, so a URL from some other host would silently
     * produce a button pointing at a path that does not exist — and Glific would accept the send.
     * Refusing here turns that into a failed delivery that gets logged and retried.</p>
     */
    String linkSuffix(String documentUrl) {
        String prefix = mediaUrlPrefix();
        if (documentUrl == null || !documentUrl.startsWith(prefix)) {
            throw new IllegalStateException(
                    "Cannot build the daily report link: '" + documentUrl + "' does not start with the"
                    + " template's URL prefix '" + prefix + "' (from storage.public-base-url). Meta appends the"
                    + " remainder to that prefix verbatim, so the button would point somewhere that does"
                    + " not exist. Check STORAGE_PUBLIC_BASE_URL against the approved template.");
        }
        String suffix = documentUrl.substring(prefix.length());
        if (suffix.isBlank()) {
            throw new IllegalStateException(
                    "Cannot build the daily report link: '" + documentUrl + "' is the bare prefix '" + prefix
                    + "' with no object path after it");
        }
        return suffix;
    }

    /**
     * {@code storage.public-base-url} with exactly one trailing slash — the prefix the LINK template
     * owns. Mirrors the trailing-slash trimming in {@code ObjectStorageService.publicUrl}, because the
     * two have to agree on where the prefix ends for {@link #linkSuffix(String)} to strip it: that value
     * is hand-written per environment and one ending in {@code /} would otherwise leave a leading slash
     * on the suffix.
     */
    private String mediaUrlPrefix() {
        String prefix = settings.media().baseUrl() == null ? "" : settings.media().baseUrl().trim();
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix + "/";
    }

    /** The configured delivery mode; parsed on use so an unknown value fails loudly wherever it is read. */
    private ReportDeliveryMode deliveryMode() {
        return ReportDeliveryMode.from(settings.dailyReportDeliveryMode());
    }

    /**
     * The configured delivery mode, or {@code null} when the property is unparseable.
     *
     * <p>Used only on the dry-run path, which reports the mode for the log line but must not start
     * throwing on a typo it never used to read. A live send still goes through {@link #deliveryMode()}
     * and still fails loudly.</p>
     */
    private ReportDeliveryMode deliveryModeOrNull() {
        try {
            return deliveryMode();
        } catch (IllegalStateException e) {
            log.warn("[WhatsApp] Suppressed daily report: delivery-mode '{}' is not DOCUMENT or LINK;"
                    + " reporting mode as unknown", settings.dailyReportDeliveryMode());
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
                ? settings.media().dailyReportCaption()
                : settings.media().dailyReportCaption() + " " + reportDate.format(DOCUMENT_NAME_DATE);
    }

    /**
     * Whether daily-report delivery is actually switched on. Lets a caller tell a configuration state
     * (report muted, so a contact id of 0 is expected and harmless) from a genuine failure (report live
     * but the officer has no Glific contact) without duplicating the dry-run properties.
     */
    @Override
    public boolean isDailyReportDeliveryEnabled() {
        return !settings.dryRun().dailyReport();
    }

    /** SUB_DIVISIONAL_OFFICER uses its own template when configured; otherwise falls back to the SO template. */
    private String resolveDailyReportTemplateId(String officerUserType) {
        if (officerUserType != null && officerUserType.trim().equalsIgnoreCase("SUB_DIVISIONAL_OFFICER")
                && !isBlank(settings.templates().dailyReportSdo())) {
            return settings.templates().dailyReportSdo();
        }
        return settings.templates().dailyReportSo();
    }

    /**
     * Same SDO→SO fallback as {@link #resolveDailyReportTemplateId} but over the LINK templates, so a
     * deployment that has approved only one template still delivers to both roles.
     */
    private String resolveDailyReportLinkTemplateId(String officerUserType) {
        if (officerUserType != null && officerUserType.trim().equalsIgnoreCase("SUB_DIVISIONAL_OFFICER")
                && !isBlank(settings.templates().dailyReportSdoLink())) {
            return settings.templates().dailyReportSdoLink();
        }
        return settings.templates().dailyReportSoLink();
    }

    /**
     * Sends the Weekly Water Service Situation Report as a dynamic-URL button HSM.
     *
     * <p>LINK only, with no DOCUMENT counterpart: the attachment path requires Meta to fetch the PDF
     * from our object store, which the India-only firewall in front of production blocks
     * ({@code (#131053)}). The daily report keeps DOCUMENT mode for environments where that works;
     * there is no reason to introduce the same trap for a new report.</p>
     *
     * <p>Parameter order is Glific's, not ours: body variables first, the button's URL suffix last.</p>
     *
     * @param weekStart the first day of the reported week — template variable {{2}}
     * @return the Glific message id, template id and mode of the accepted send; a dry run returns
     *         {@link WhatsAppSendResult#suppressed}
     */
    @Override
    public WhatsAppSendResult sendWeeklyReportHsm(Long contactId, String documentUrl, String officerUserType,
                                                  LocalDate weekStart, String officerName) {
        if (isDryRun(settings.dryRun().weeklyReport(), "sendWeeklyReportHsm")) {
            return WhatsAppSendResult.suppressed(ReportDeliveryMode.LINK);
        }
        requireContactId(contactId, "sendWeeklyReportHsm");

        String templateId = resolveWeeklyReportLinkTemplateId(officerUserType);
        if (isBlank(templateId)) {
            throw new IllegalStateException(
                    "whatsapp.template.weekly-report-so-link-id is not configured — the weekly report cannot"
                    + " be sent (set NOTIFICATIONS_WEEKLY_REPORT_DRY_RUN=true to suppress it until the"
                    + " template is approved)");
        }
        if (weekStart == null) {
            throw new IllegalArgumentException(
                    "sendWeeklyReportHsm requires the week start — it is template variable {{2}}");
        }
        String urlSuffix = linkSuffix(documentUrl);
        String name = isBlank(officerName) ? "Officer" : officerName.trim();
        String role = isBlank(officerUserType) ? "UNKNOWN" : officerUserType.trim();

        JsonNode response = client.execute(NUDGE_HSM_MUTATION, Map.of(
                "templateId", templateId,
                "receiverId", contactId,
                "parameters", List.of(name, weekStart.format(DOCUMENT_NAME_DATE), urlSuffix)));
        checkErrors(response, "sendHsmMessage");
        String messageId = extractMessageId(response, "sendHsmMessage");
        log.info("[WhatsApp] Weekly report HSM sent mode=LINK role={} providerMsgId={} templateId={}",
                role, messageId, templateId);
        log.debug("[WhatsApp] Weekly report link HSM sent to contactId={} suffix={}", contactId, urlSuffix);
        return new WhatsAppSendResult(messageId, templateId, ReportDeliveryMode.LINK);
    }

    /** SDO→SO fallback, so a deployment with only one approved weekly template still serves both roles. */
    private String resolveWeeklyReportLinkTemplateId(String officerUserType) {
        if (officerUserType != null && officerUserType.trim().equalsIgnoreCase("SUB_DIVISIONAL_OFFICER")
                && !isBlank(settings.templates().weeklyReportSdoLink())) {
            return settings.templates().weeklyReportSdoLink();
        }
        return settings.templates().weeklyReportSoLink();
    }

    /** Whether weekly reports are actually delivered (as opposed to generated and suppressed). */
    @Override
    public boolean isWeeklyReportDeliveryEnabled() {
        return !settings.dryRun().weeklyReport();
    }

    /**
     * Sends the escalation document HSM to the officer.
     *
     * <p>Two-step process:
     * <ol>
     *   <li>Upload {@code documentUrl} via {@code createMessageMedia} → {@code mediaId}</li>
     *   <li>Send {@code createAndSendMessage} with the {@code mediaId} as the document
     *       header attachment and {@code bodyText} as the body template parameter.</li>
     * </ol>
     *
     * @param contactId   Glific contact ID of the officer
     * @param documentUrl publicly reachable URL of the escalation PDF
     */
    @Override
    public void sendEscalationHsm(Long contactId, String documentUrl) {
        if (isDryRun(settings.dryRun().escalation(), "sendEscalationHsm")) return;
        // Checked before the media upload so a missing contact id costs no Glific round-trip.
        requireContactId(contactId, "sendEscalationHsm");

        String mediaId = uploadMedia(documentUrl, settings.media().escalationCaption());

        Map<String, Object> input = new HashMap<>();
        input.put("templateId", Integer.parseInt(settings.templates().escalation()));
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

        log.debug("[WhatsApp] Escalation HSM sent to contactId={}", contactId);
    }

    /**
     * Initiates a Glific flow for the nudge contact via the {@code startContactFlow} mutation.
     *
     * <p>Instead of sending a plain HSM message, this triggers the interactive nudge flow
     * configured in Glific (identified by {@code whatsapp.flow.nudge-id}). The flow sends
     * an HSM template with clickable buttons and continues the conversation based on
     * the operator's button response.</p>
     *
     * <p>Operator name and date are passed as {@code defaultResults} using the keys
     * {@code "name"} and {@code "date"} respectively, matching the HSM template parameter names.</p>
     *
     * <p>{@code whatsapp.flow.nudge-id} is a required configuration — startup fails fast
     * if it is absent (see {@code @PostConstruct} validation).</p>
     *
     * @param contactId    Glific contact ID obtained from {@link #optIn}
     * @param operatorName operator name; passed as {@code defaultResults} key {@code "name"}
     * @param date         today's date string; passed as {@code defaultResults} key {@code "state"}
     * @throws IllegalStateException if {@code whatsapp.flow.nudge-id} is blank
     * @throws RuntimeException      if Glific returns GraphQL errors or {@code success=false}
     */
    @Override
    public void startNudgeFlow(Long contactId, String operatorName, String date) {
        if (isDryRun(settings.dryRun().nudge(), "startNudgeFlow")) return;
        requireContactId(contactId, "startNudgeFlow");
        if (isBlank(settings.flows().nudge())) {
            throw new IllegalStateException("whatsapp.flow.nudge-id is not configured");
        }

        String defaultResults;
        try {
            defaultResults = objectMapper.writeValueAsString(
                    Map.of("name", operatorName, "date", date));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize flow defaultResults", e);
        }

        JsonNode response = client.execute(START_CONTACT_FLOW_MUTATION, Map.of(
                "flowId", settings.flows().nudge(),
                "contactId", contactId,
                "defaultResults", defaultResults));

        checkErrors(response, "startContactFlow");
        JsonNode flowNode = response.path("startContactFlow");

        boolean success = flowNode.path("success").asBoolean(false);
        if (!success) {
            throw new RuntimeException("Glific startContactFlow returned success=false for contactId=" + contactId);
        }
        log.debug("[WhatsApp] Nudge flow started for contactId={}", contactId);
    }

    /**
     * Initiates the Glific welcome flow for a newly onboarded operator.
     *
     * @param contactId Glific contact ID obtained from {@link #optIn}
     * @param name      operator display name passed as {@code @results.name} in the flow
     * @param state     tenant state name passed as {@code @results.state} in the flow
     * @throws RuntimeException if Glific returns GraphQL errors or {@code success=false}
     */
    @Override
    public void startWelcomeFlow(Long contactId, String name, String state) {
        startWelcomeFlow(contactId, settings.flows().welcome(), name, state);
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
    @Override
    public void startWelcomeFlow(Long contactId, String flowId, String name, String state) {
        if (isDryRun(settings.dryRun().whatsapp(), "startWelcomeFlow")) return;
        requireContactId(contactId, "startWelcomeFlow");
        if (flowId == null || flowId.isBlank()) {
            throw new IllegalStateException("whatsapp.flow.welcome-id is not configured");
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
        log.debug("[WhatsApp] Welcome flow started for contactId={}", contactId);
    }

    /**
     * Updates the language of a Glific contact.
     *
     * @param contactId        Glific contact ID
     * @param glificLanguageId Glific-side language ID
     */
    @Override
    public void updateContactLanguage(Long contactId, int glificLanguageId) {
        if (isDryRun(settings.dryRun().whatsapp(), "updateContactLanguage")) return;
        JsonNode response = client.execute(UPDATE_CONTACT_MUTATION, Map.of(
                "id", contactId,
                "input", Map.of("language_id", glificLanguageId)));
        checkErrors(response, "updateContact");
        log.debug("[WhatsApp] Contact language updated contactId={} languageId={}", contactId, glificLanguageId);
    }

    /**
     * Throws when a mutation came back with a non-empty {@code errors} array.
     *
     * <p>Throws {@link GlificMutationException} rather than a bare {@link RuntimeException}, with the
     * message unchanged: callers that only catch {@code Exception} behave exactly as before, while
     * those that need to know where the send broke read its {@link WhatsAppSendStage} off the port's
     * {@code WhatsAppSendException} instead of parsing the text.</p>
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
            log.error("[WhatsApp] GraphQL errors in {}: {}", mutationKey, msg);
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
     * {@link WhatsAppSendResult#suppressed} before any mutation runs, and that path keeps its null
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