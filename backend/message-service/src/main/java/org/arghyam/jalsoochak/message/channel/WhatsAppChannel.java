package org.arghyam.jalsoochak.message.channel;

import org.arghyam.jalsoochak.message.dto.NotificationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * WhatsApp channel powered by <strong>Glific</strong> GraphQL HSM API.
 *
 * <p>Nudges use a text HSM template with {@code {{1}}} = operator name and {@code {{2}}} = today's date.</p>
 * <p>Escalations use a document HSM template with {@code {{1}}} = MinIO URL
 * and {@code {{2}}} = localized body text.</p>
 *
 * <p>Configure Glific credentials and template IDs via environment variables:
 * {@code GLIFIC_API_URL}, {@code GLIFIC_USERNAME}, {@code GLIFIC_PASSWORD},
 * {@code GLIFIC_NUDGE_TEMPLATE_ID}, {@code GLIFIC_ESCALATION_TEMPLATE_ID}.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WhatsAppChannel implements NotificationChannel {

    private final GlificWhatsAppService glificWhatsAppService;

    @Override
    public String channelType() {
        return "WHATSAPP";
    }

    @Override
    public boolean send(NotificationRequest request) {
        try {
            Long contactId = glificWhatsAppService.optIn(request.getRecipient());
            glificWhatsAppService.sendNudgeHsm(contactId, request.getBody(),
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
     * Sends the nudge HSM template with two variables.
     *
     * @param phone        recipient WhatsApp phone number (E.164 format)
     * @param operatorName operator name for template {@code {{1}}}
     * @param date         today's date string for template {@code {{2}}}
     * @return {@code true} if the message was accepted by Glific
     */
    public boolean sendNudge(String phone, String operatorName, String date) {
        try {
            Long contactId = glificWhatsAppService.optIn(phone);
            glificWhatsAppService.sendNudgeHsm(contactId, operatorName, date);
            log.info("[WHATSAPP] Nudge HSM sent");
            log.debug("[WHATSAPP] Nudge HSM sent to {}", phone);
            return true;
        } catch (Exception ex) {
            log.error("[WHATSAPP] Failed nudge delivery: {}", ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * Initiates a Glific nudge flow using an already-resolved Glific contact ID.
     * No {@code optIn} call is made — use this path when {@code whatsapp_connection_id}
     * is already stored in {@code user_table}.
     *
     * @param contactId    Glific contact ID
     * @param operatorName operator name passed as flow variable
     * @param date         today's date passed as flow variable
     * @return {@code true} if the flow was successfully initiated
     */
    public boolean sendNudgeViaFlow(long contactId, String operatorName, String date) {
        try {
            glificWhatsAppService.startNudgeFlow(contactId, operatorName, date);
            log.info("[WHATSAPP] Nudge flow initiated");
            log.debug("[WHATSAPP] Nudge flow initiated for contactId={}", contactId);
            return true;
        } catch (Exception ex) {
            log.error("[WHATSAPP] Failed to initiate nudge flow: {}", ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * Opts a pump operator into Glific, sets their preferred language, and starts the welcome flow.
     * Called during staff-sync onboarding.
     *
     * @param phone            operator phone number (E.164 format)
     * @param glificLanguageId Glific-side language ID
     * @return Glific contact ID assigned to this operator
     */
    public long onboardOperator(String phone, int glificLanguageId) {
        long contactId = glificWhatsAppService.optIn(phone);
        glificWhatsAppService.updateContactLanguage(contactId, glificLanguageId);
        glificWhatsAppService.startWelcomeFlow(contactId, null, null);
        log.info("[WHATSAPP] Operator onboarded to Glific");
        String phoneSuffix = phone != null && phone.length() >= 4
                ? phone.substring(phone.length() - 4)
                : "unknown";
        log.debug("[WHATSAPP] Operator onboarded phoneSuffix={} languageId={}", phoneSuffix, glificLanguageId);
        return contactId;
    }

    /**
     * Sends the login OTP HSM to an officer using an already-resolved Glific contact ID.
     * Template {{1}} = OTP.
     *
     * @param contactId Glific contact ID of the officer
     * @param otp       one-time password for template variable
     * @return {@code true} if the message was accepted by Glific
     */
    public boolean sendLoginOtp(long contactId, String otp) {
        try {
            glificWhatsAppService.sendLoginOtpHsm(contactId, otp);
            log.info("[WHATSAPP] Login OTP HSM sent");
            log.debug("[WHATSAPP] Login OTP HSM sent to contactId={}", contactId);
            return true;
        } catch (Exception ex) {
            log.error("[WHATSAPP] Failed login OTP delivery: {}", ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * Sends the escalation PDF (document HSM) to the officer via Glific using an
     * already-resolved Glific contact ID.
     *
     * @param contactId   Glific contact ID of the officer
     * @param documentUrl publicly reachable MinIO URL of the escalation PDF
     * @return {@code true} if the message was accepted by Glific
     */
    public boolean sendDocument(long contactId, String documentUrl) {
        try {
            glificWhatsAppService.sendEscalationHsm(contactId, documentUrl);
            log.info("[WHATSAPP] Escalation HSM sent");
            log.debug("[WHATSAPP] Escalation HSM sent to contactId={}", contactId);
            return true;
        } catch (Exception ex) {
            log.error("[WHATSAPP] Failed escalation delivery: {}", ex.getMessage(), ex);
            return false;
        }
    }

    /**
     * Sends the Daily Water Service Situation Report PDF (document HSM) to an officer via Glific
     * using an already-resolved Glific contact ID. The template is chosen by officer role.
     *
     * @param contactId       Glific contact ID of the officer
     * @param documentUrl     publicly reachable MinIO URL of the report PDF
     * @param officerUserType SECTION_OFFICER | SUB_DIVISIONAL_OFFICER
     * @return {@code true} if the message was accepted by Glific
     */
    public boolean sendDailyReport(long contactId, String documentUrl, String officerUserType) {
        String role = (officerUserType == null || officerUserType.isBlank()) ? "UNKNOWN" : officerUserType.trim();
        try {
            // Send with the same token that is logged, so the template picked matches the counted role.
            glificWhatsAppService.sendDailyReportHsm(contactId, documentUrl, role);
            log.info("[WHATSAPP] Daily report HSM sent role={}", role);
            log.debug("[WHATSAPP] Daily report HSM sent role={} contactId={}", role, contactId);
            return true;
        } catch (Exception ex) {
            log.error("[WHATSAPP] Failed daily report delivery role={}: {}", role, ex.getMessage(), ex);
            return false;
        }
    }
}
