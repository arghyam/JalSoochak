package org.arghyam.jalsoochak.message.dto;

/**
 * One outbound message as Glific currently sees it, after Gupshup and Meta have reported back.
 *
 * <p>Everything here is an identifier or a status — no phone number, no name. Glific's raw
 * {@code errors} payload does contain the recipient's number in its {@code destination} field, so
 * only the extracted {@code errorCode} and {@code errorReason} survive into this record. See
 * {@code mydocs/GLIFIC_API_CONTRACT.md}.</p>
 *
 * @param messageId         Glific {@code Message.id} — the join key back to our {@code result=SENT} line
 * @param bspMessageId      Gupshup-side id; the reference to quote when escalating a case to Glific
 * @param bspStatus         Glific's raw status, kept verbatim so an unmapped value is still visible
 * @param templateId        the HSM template used, or {@code null}. The only way to tell a daily report
 *                          apart from a nudge in the same window, since {@code MessageFilter} cannot
 *                          filter on it
 * @param hsm               whether this was a template message
 * @param flow              {@code OUTBOUND} or {@code INBOUND}. On an inbound message
 *                          {@code receiver} is <em>our own org contact</em>, not an officer, so this
 *                          must be checked before mapping a contact id to a user
 * @param receiverContactId Glific contact id of the recipient — matches
 *                          {@code user_table.whatsapp_connection_id}
 * @param outcome           our normalised reading of {@code bspStatus}
 * @param errorCode         BSP failure code (e.g. {@code 131026}), or {@code null}
 * @param errorReason       BSP failure text, phone-redacted, or {@code null}
 */
public record GlificMessageStatus(
        String messageId,
        String bspMessageId,
        String bspStatus,
        Integer templateId,
        boolean hsm,
        String flow,
        Long receiverContactId,
        GlificDeliveryOutcome outcome,
        String errorCode,
        String errorReason) {

    /** True for a template message we actually sent — the only kind a daily-report count may include. */
    public boolean isOutboundHsm() {
        return hsm && "OUTBOUND".equalsIgnoreCase(flow);
    }

    /** The failure code for grouping and logging; falls back to the raw status when there is no code. */
    public String failureKey() {
        if (errorCode != null && !errorCode.isBlank()) {
            return errorCode;
        }
        return bspStatus == null || bspStatus.isBlank() ? "UNKNOWN" : bspStatus;
    }
}
