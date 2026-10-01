package org.arghyam.jalsoochak.message.channel.provider.glific;

import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSendException;
import org.arghyam.jalsoochak.message.channel.provider.WhatsAppSendStage;

/**
 * A Glific GraphQL mutation that returned a non-empty {@code errors} array.
 *
 * <p>Unchecked, with the same message the previous bare {@code RuntimeException} carried, so every
 * existing {@code catch (Exception)} and every test asserting on the message keeps behaving
 * identically. What it adds is the two fields a caller needs to classify the failure without parsing
 * text: which mutation failed, and Glific's own error {@code key}.</p>
 *
 * <p>The stage is derived from the mutation here, where the Glific contract is known, so callers of
 * the port read {@link #getStage()} and never see a mutation name.</p>
 */
public class GlificMutationException extends WhatsAppSendException {

    private static final long serialVersionUID = 1L;

    /** The GraphQL mutation that failed, e.g. {@code createMessageMedia} or {@code sendHsmMessage}. */
    private final transient String mutationKey;

    /**
     * @param errorKey Glific's {@code errors[0].key}, or {@code null} when the array carried no key
     */
    public GlificMutationException(String mutationKey, String errorKey, String message) {
        this(stageOf(mutationKey), mutationKey, errorKey, message);
    }

    /** For subclasses whose stage does not follow from the mutation alone. */
    protected GlificMutationException(WhatsAppSendStage stage, String mutationKey, String errorKey,
                                      String message) {
        super(stage, errorKey, message);
        this.mutationKey = mutationKey;
    }

    public String getMutationKey() {
        return mutationKey;
    }

    /**
     * {@code createMessageMedia} is the DOCUMENT-mode media step — the 20 Aug (#131053) failure — and
     * needs a completely different fix from a rejected send. Every other mutation is the send itself,
     * or an account operation that no caller classifies.
     */
    private static WhatsAppSendStage stageOf(String mutationKey) {
        return "createMessageMedia".equals(mutationKey)
                ? WhatsAppSendStage.MEDIA_REGISTER
                : WhatsAppSendStage.SEND;
    }
}
