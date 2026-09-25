package org.arghyam.jalsoochak.message.channel.provider.glific;

import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSendStage;

/**
 * A send mutation Glific <em>accepted</em> — an empty {@code errors} array — that came back with no
 * {@code message.id}.
 *
 * <p>Its own type because the two facts it carries pull in opposite directions. Glific created the
 * message, so the officer may well receive the report and a retry would send a second copy; but we
 * have no id, so nothing can ever match a delivery status back to this send. Neither "succeeded" nor
 * "failed, retry" describes that, which is why it carries {@link WhatsAppSendStage#SEND_NO_MESSAGE_ID}
 * rather than plain {@link WhatsAppSendStage#SEND}.</p>
 *
 * <p>Extends {@link GlificMutationException} so the existing {@code catch (Exception)} in
 * {@code WhatsAppChannel.sendDailyReport} and every caller that only reads {@code getMutationKey()}
 * keep working unchanged. {@code errorKey} is null: Glific reported no error, this is our own
 * conclusion about its response.</p>
 */
public class GlificMissingMessageIdException extends GlificMutationException {

    private static final long serialVersionUID = 1L;

    public GlificMissingMessageIdException(String mutationKey) {
        super(WhatsAppSendStage.SEND_NO_MESSAGE_ID, mutationKey, null, "Glific accepted " + mutationKey
                + " but returned no message.id — the send cannot be reconciled and must not be retried");
    }
}
