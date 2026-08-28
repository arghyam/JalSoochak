package org.arghyam.jalsoochak.message.channel;

/**
 * What Glific gave back when it accepted a send.
 *
 * <p>The {@code messageId} is the reason this type exists. Glific's {@code sendHsmMessage} and
 * {@code createAndSendMessage} both return {@code message { id }} and the service used to parse it
 * and throw it away — yet it is the <em>only</em> handle that ties a report we sent to the delivery
 * status Gupshup and Meta report back to Glific later. Without it, no reconciliation is possible,
 * manual or automated.</p>
 *
 * <p>Acceptance is not delivery: a populated {@code messageId} means Glific created the message row
 * and queued it to its BSP worker, nothing more.</p>
 *
 * @param messageId  Glific {@code Message.id}, or {@code null} when the send was suppressed by a
 *                   dry-run flag. Never a placeholder — a fake id would be indistinguishable from a
 *                   real one during reconciliation
 * @param templateId the HSM template actually used. Worth carrying because
 *                   {@code resolveDailyReportLinkTemplateId} silently falls back from the SDO
 *                   template to the SO one, and nothing else in the logs reveals which was sent
 * @param mode       the delivery mode the send took, or {@code null} if it could not be determined
 */
public record GlificSendResult(String messageId, String templateId, DailyReportDeliveryMode mode) {

    /** Placeholder shown in logs when there is no message id, so the field is never blank or "null". */
    public static final String NO_MESSAGE_ID = "none";

    /** A send that a dry-run flag suppressed: accepted from the caller's view, but nothing was sent. */
    public static GlificSendResult suppressed(DailyReportDeliveryMode mode) {
        return new GlificSendResult(null, null, mode);
    }

    /** True when Glific returned a usable message id — i.e. a real send that can be reconciled later. */
    public boolean hasMessageId() {
        return messageId != null && !messageId.isBlank();
    }

    /** The message id for logging, or {@value #NO_MESSAGE_ID}. */
    public String messageIdForLog() {
        return hasMessageId() ? messageId : NO_MESSAGE_ID;
    }

    /** The template id for logging, or {@code "-"} when unknown (a suppressed send). */
    public String templateIdForLog() {
        return templateId == null || templateId.isBlank() ? "-" : templateId;
    }

    /** The mode for logging, or {@code "-"} when it could not be determined. */
    public String modeForLog() {
        return mode == null ? "-" : mode.name();
    }
}
