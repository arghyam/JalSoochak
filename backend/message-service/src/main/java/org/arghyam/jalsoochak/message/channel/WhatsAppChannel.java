package org.arghyam.jalsoochak.message.channel;

import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import org.arghyam.jalsoochak.message.channel.provider.ReportSendOutcome;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSendException;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSendResult;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSendStage;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSender;
import org.arghyam.jalsoochak.message.dto.NotificationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * WhatsApp channel, sending HSM templates through the {@link WhatsAppSender} port.
 *
 * <p>Scheduled nudges go through {@link #sendNudgeViaFlow} (a provider flow start). The plain nudge HSM
 * ({@code {{1}}} = operator name, {@code {{2}}} = date) is only used by the REST notification API.</p>
 * <p>Escalations use a document HSM template with {@code {{1}}} = the PDF's public URL
 * and {@code {{2}}} = localized body text.</p>
 *
 * <p>Configure the WhatsApp provider's credentials and template IDs via environment variables:
 * {@code WHATSAPP_API_URL}, {@code WHATSAPP_USERNAME}, {@code WHATSAPP_PASSWORD},
 * {@code WHATSAPP_NUDGE_TEMPLATE_ID}, {@code WHATSAPP_ESCALATION_TEMPLATE_ID}.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WhatsAppChannel implements NotificationChannel {

    private final WhatsAppSender whatsAppSender;

    @Override
    public String channelType() {
        return "WHATSAPP";
    }

    @Override
    public boolean send(NotificationRequest request) {
        try {
            Long contactId = whatsAppSender.optIn(request.getRecipient());
            whatsAppSender.sendNudgeHsm(contactId, request.getBody(),
                    request.getDate() != null ? request.getDate() : "");
            log.info("[WHATSAPP] Nudge HSM sent");
            log.debug("[WHATSAPP] Nudge HSM sent to {}", request.getRecipient());
            return true;
        } catch (Exception ex) {
            log.error("[WHATSAPP] Failed nudge delivery: {}", ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * Sends the nudge HSM template with two variables. REST notification API only — the scheduled
     * nudge starts the provider flow instead ({@link #sendNudgeViaFlow}).
     *
     * @param phone        recipient WhatsApp phone number (E.164 format)
     * @param operatorName operator name for template {@code {{1}}}
     * @param date         today's date string for template {@code {{2}}}
     * @return {@code true} if the message was accepted by the WhatsApp provider
     */
    public boolean sendNudge(String phone, String operatorName, String date) {
        try {
            Long contactId = whatsAppSender.optIn(phone);
            whatsAppSender.sendNudgeHsm(contactId, operatorName, date);
            log.info("[WHATSAPP] Nudge HSM sent");
            log.debug("[WHATSAPP] Nudge HSM sent to {}", phone);
            return true;
        } catch (Exception ex) {
            log.error("[WHATSAPP] Failed nudge delivery: {}", ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * Initiates a nudge flow using an already-resolved WhatsApp contact ID.
     * No {@code optIn} call is made — use this path when {@code whatsapp_connection_id}
     * is already stored in {@code user_table}.
     *
     * @param contactId    WhatsApp contact ID
     * @param operatorName operator name passed as flow variable
     * @param date         the nudge date passed as flow variable
     * @return whether the flow started, certainly did not, or may have — see {@link NudgeSendOutcome}
     */
    public NudgeSendOutcome sendNudgeViaFlow(long contactId, String operatorName, String date) {
        try {
            whatsAppSender.startNudgeFlow(contactId, operatorName, date);
            log.info("[WHATSAPP] Nudge flow initiated");
            log.debug("[WHATSAPP] Nudge flow initiated for contactId={}", contactId);
            return NudgeSendOutcome.SENT;
        } catch (Exception ex) {
            NudgeSendOutcome outcome = classifyNudgeFailure(ex);
            log.error("[WHATSAPP] Failed to initiate nudge flow ({}): {}", outcome, ex.getMessage(), ex);
            return outcome;
        }
    }

    /**
     * {@link NudgeSendOutcome#NOT_SENT} only when the failure proves the flow never started: the
     * request was refused before sending, the connection never opened, or the provider answered 429/502/503.
     * Everything else — a response timeout above all — is {@link NudgeSendOutcome#UNKNOWN}.
     */
    static NudgeSendOutcome classifyNudgeFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof TimeoutException || t instanceof SocketTimeoutException
                    || t.getClass().getSimpleName().equals("ReadTimeoutException")
                    || (t instanceof IllegalStateException && String.valueOf(t.getMessage()).contains("Timeout"))) {
                return NudgeSendOutcome.UNKNOWN;
            }
            // ConnectTimeoutException extends ConnectException: the connection never opened.
            if (t instanceof ConnectException || t instanceof UnknownHostException
                    || t instanceof IllegalArgumentException) {
                return NudgeSendOutcome.NOT_SENT;
            }
            if (t instanceof WebClientResponseException wcre) {
                int status = wcre.getStatusCode().value();
                return status == 429 || status == 502 || status == 503
                        ? NudgeSendOutcome.NOT_SENT : NudgeSendOutcome.UNKNOWN;
            }
            if (t.getCause() == t) break;
        }
        return NudgeSendOutcome.UNKNOWN;
    }

    /**
     * Opts a pump operator into the WhatsApp provider, sets their preferred language, and starts the
     * welcome flow.
     * Called during staff-sync onboarding.
     *
     * @param phone              operator phone number (E.164 format)
     * @param providerLanguageId the WhatsApp provider's language ID
     * @return WhatsApp contact ID assigned to this operator
     */
    public long onboardOperator(String phone, int providerLanguageId) {
        long contactId = whatsAppSender.optIn(phone);
        whatsAppSender.updateContactLanguage(contactId, providerLanguageId);
        whatsAppSender.startWelcomeFlow(contactId, null, null);
        log.info("[WHATSAPP] Operator onboarded");
        String phoneSuffix = phone != null && phone.length() >= 4
                ? phone.substring(phone.length() - 4)
                : "unknown";
        log.debug("[WHATSAPP] Operator onboarded phoneSuffix={} languageId={}", phoneSuffix, providerLanguageId);
        return contactId;
    }

    /**
     * Sends the login OTP HSM to an officer using an already-resolved WhatsApp contact ID.
     * Template {{1}} = OTP.
     *
     * @param contactId WhatsApp contact ID of the officer
     * @param otp       one-time password for template variable
     * @return {@code true} if the message was accepted by the WhatsApp provider
     */
    public boolean sendLoginOtp(long contactId, String otp) {
        return sendLoginOtpForOutcome(contactId, otp).accepted();
    }

    /**
     * As {@link #sendLoginOtp}, keeping what the provider said: its message id on acceptance, the
     * {@link WhatsAppSendStage} on a failure. Never throws.
     */
    public ReportSendOutcome sendLoginOtpForOutcome(long contactId, String otp) {
        try {
            WhatsAppSendResult result = whatsAppSender.sendLoginOtpHsm(contactId, otp);
            log.info("[WHATSAPP] Login OTP HSM sent");
            log.debug("[WHATSAPP] Login OTP HSM sent to contactId={}", contactId);
            return ReportSendOutcome.accepted(result);
        } catch (Exception ex) {
            log.error("[WHATSAPP] Failed login OTP delivery: {}", ex.getMessage(), ex);
            return failedOutcome(ex);
        }
    }

    /**
     * Sends the escalation PDF (document HSM) to the officer using an already-resolved
     * WhatsApp contact ID.
     *
     * @param contactId   WhatsApp contact ID of the officer
     * @param documentUrl publicly reachable URL of the stored escalation PDF
     * @return {@code true} if the message was accepted by the WhatsApp provider
     */
    public boolean sendDocument(long contactId, String documentUrl) {
        return sendDocumentForOutcome(contactId, documentUrl).accepted();
    }

    /** As {@link #sendDocument}, keeping what the provider said. Never throws. */
    public ReportSendOutcome sendDocumentForOutcome(long contactId, String documentUrl) {
        try {
            WhatsAppSendResult result = whatsAppSender.sendEscalationHsm(contactId, documentUrl);
            log.info("[WHATSAPP] Escalation HSM sent");
            log.debug("[WHATSAPP] Escalation HSM sent to contactId={}", contactId);
            return ReportSendOutcome.accepted(result);
        } catch (Exception ex) {
            log.error("[WHATSAPP] Failed escalation delivery: {}", ex.getMessage(), ex);
            return failedOutcome(ex);
        }
    }

    private static ReportSendOutcome failedOutcome(Exception ex) {
        String errorKey = (ex instanceof WhatsAppSendException wse) ? wse.getErrorKey() : null;
        return ReportSendOutcome.failed(stageOf(ex), errorKey, ex.getMessage());
    }

    /**
     * Sends the Daily Water Service Situation Report to an officer using an already-resolved
     * WhatsApp contact ID. The template is chosen by officer role, and the shape — PDF attachment or a
     * "View Report" link button — by {@code notifications.daily-report.delivery-mode}.
     *
     * @param contactId       WhatsApp contact ID of the officer
     * @param documentUrl     publicly reachable URL of the stored report PDF
     * @param officerUserType SECTION_OFFICER | SUB_DIVISIONAL_OFFICER
     * @param reportDate      the day the report's data covers (D-1); shown in the document name the
     *                        officer sees in WhatsApp, and template variable {{2}} in link mode
     * @param officerName     the officer's name; template variable {{1}} in link mode, unused for the
     *                        document. Not logged — see the privacy rule in CLAUDE.md
     * @return an accepted outcome carrying the provider's message id, template id and mode, or a failed
     *         outcome naming the {@link WhatsAppSendStage} it broke at. <strong>Acceptance is not
     *         delivery</strong> — it means the provider's send call returned no errors; Gupshup and
     *         Meta act after this call returns and report back only to the provider
     */
    public ReportSendOutcome sendDailyReport(long contactId, String documentUrl, String officerUserType,
                                             LocalDate reportDate, String officerName) {
        String role = (officerUserType == null || officerUserType.isBlank()) ? "UNKNOWN" : officerUserType.trim();
        try {
            // Send with the same token that is logged, so the template picked matches the counted role.
            WhatsAppSendResult result = whatsAppSender.sendDailyReportHsm(
                    contactId, documentUrl, role, reportDate, officerName);
            log.info("[WHATSAPP] Daily report HSM sent role={} providerMsgId={}", role, result.messageIdForLog());
            log.debug("[WHATSAPP] Daily report HSM sent role={} contactId={}", role, contactId);
            return ReportSendOutcome.accepted(result);
        } catch (Exception ex) {
            WhatsAppSendStage stage = stageOf(ex);
            String errorKey = (ex instanceof WhatsAppSendException wse) ? wse.getErrorKey() : null;
            log.error("[WHATSAPP] Failed daily report delivery role={} stage={}: {}",
                    role, stage, ex.getMessage(), ex);
            return ReportSendOutcome.failed(stage, errorKey, ex.getMessage());
        }
    }

    /**
     * Sends the weekly report, mirroring {@link #sendDailyReport} — same outcome type, same failure
     * classification, so the router treats both reports identically.
     *
     * @param weekStart the first day of the reported week, template variable {{2}}
     */
    public ReportSendOutcome sendWeeklyReport(long contactId, String documentUrl, String officerUserType,
                                              LocalDate weekStart, String officerName) {
        String role = (officerUserType == null || officerUserType.isBlank()) ? "UNKNOWN" : officerUserType.trim();
        try {
            WhatsAppSendResult result = whatsAppSender.sendWeeklyReportHsm(
                    contactId, documentUrl, role, weekStart, officerName);
            log.info("[WHATSAPP] Weekly report HSM sent role={} providerMsgId={}", role, result.messageIdForLog());
            log.debug("[WHATSAPP] Weekly report HSM sent role={} contactId={}", role, contactId);
            return ReportSendOutcome.accepted(result);
        } catch (Exception ex) {
            WhatsAppSendStage stage = stageOf(ex);
            String errorKey = (ex instanceof WhatsAppSendException wse) ? wse.getErrorKey() : null;
            log.error("[WHATSAPP] Failed weekly report delivery role={} stage={}: {}",
                    role, stage, ex.getMessage(), ex);
            return ReportSendOutcome.failed(stage, errorKey, ex.getMessage());
        }
    }

    /**
     * Classifies a send failure so the router's terminal line — {@code result=FAILED_DELIVERY}, or
     * {@code result=DELIVERY_UNCONFIRMED} for the stages after which the provider may already hold the
     * message — says which half of the handoff broke.
     *
     * <p>Order matters. A {@code block()} timeout surfaces as an {@link IllegalStateException}, so it
     * must be recognised <em>before</em> the generic configuration branch — it is the one failure a
     * retry makes worse, because the provider may already have sent the message.</p>
     *
     * <p>A failure the provider reported already carries its stage: the adapter assigns it, because
     * only the adapter knows which of its calls registers media and which sends.</p>
     */
    static WhatsAppSendStage stageOf(Throwable ex) {
        if (isBlockTimeout(ex)) {
            return WhatsAppSendStage.TIMEOUT;
        }
        if (ex instanceof WhatsAppSendException wse) {
            return wse.getStage();
        }
        // Thrown by requireContactId, the LINK-mode linkSuffix prefix check, a blank template id and
        // the PublicUrlValidator guard — all of them our own configuration or inputs, none retryable.
        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
            return WhatsAppSendStage.CONFIG;
        }
        return WhatsAppSendStage.SEND;
    }

    /**
     * Reactor's {@code Mono.block(Duration)} reports expiry as an {@link IllegalStateException} whose
     * message begins "Timeout on blocking read". There is no dedicated exception type to match on, so
     * the message is the only signal available.
     */
    private static boolean isBlockTimeout(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains("Timeout on blocking read")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
