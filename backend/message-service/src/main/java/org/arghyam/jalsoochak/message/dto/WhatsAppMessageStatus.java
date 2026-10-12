package org.arghyam.jalsoochak.message.dto;

/**
 * One outbound message as the WhatsApp provider currently sees it, after the provider and Meta have
 * reported back.
 *
 * <p>Everything here is an identifier or a status — no phone number, no name. A provider's raw failure
 * payload may contain the recipient's number, so adapters lift out only an {@code errorCode} and an
 * {@code errorReason} into this record.</p>
 *
 * @param messageId         the provider's id for the message — the join key back to our
 *                          {@code result=SENT} line and the id the delivery ledger stores
 * @param upstreamMessageId the id the message carries further down the chain (the BSP or Meta side),
 *                          when the provider exposes one; the reference to quote when escalating a case
 *                          to the provider. {@code null} when there is no separate id
 * @param bspStatus         the provider's raw status, kept verbatim so an unmapped value is still visible
 * @param templateId        the template used, as the provider identifies it (a number or a name), or
 *                          {@code null}. The only way to tell a daily report apart from a nudge in the
 *                          same window when the provider cannot filter by template
 * @param hsm               whether this was a template message
 * @param flow              {@code OUTBOUND} or {@code INBOUND}. On an inbound message
 *                          {@code receiver} is <em>our own org contact</em>, not an officer, so this
 *                          must be checked before mapping a contact id to a user
 * @param receiverContactId the provider's contact id for the recipient — matches
 *                          {@code user_table.whatsapp_connection_id}
 * @param outcome           the adapter's normalised reading of {@code bspStatus}
 * @param errorCode         the provider's failure code (e.g. {@code 131026}), or {@code null}
 * @param errorReason       the provider's failure text, phone-redacted, or {@code null}
 */
public record WhatsAppMessageStatus(
        String messageId,
        String upstreamMessageId,
        String bspStatus,
        String templateId,
        boolean hsm,
        String flow,
        Long receiverContactId,
        WhatsAppDeliveryOutcome outcome,
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
