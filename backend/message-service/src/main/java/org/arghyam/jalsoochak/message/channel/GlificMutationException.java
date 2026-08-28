package org.arghyam.jalsoochak.message.channel;

/**
 * A Glific GraphQL mutation that returned a non-empty {@code errors} array.
 *
 * <p>Extends {@link RuntimeException} with the same message the previous bare {@code RuntimeException}
 * carried, so every existing {@code catch (Exception)} and every test asserting on the message keeps
 * behaving identically. What it adds is the two fields a caller needs to classify the failure without
 * parsing text: which mutation failed, and Glific's own error {@code key}.</p>
 */
public class GlificMutationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The GraphQL mutation that failed, e.g. {@code createMessageMedia} or {@code sendHsmMessage}. */
    private final transient String mutationKey;

    /** Glific's {@code errors[0].key}, or {@code null} when the array carried no key. */
    private final transient String errorKey;

    public GlificMutationException(String mutationKey, String errorKey, String message) {
        super(message);
        this.mutationKey = mutationKey;
        this.errorKey = errorKey;
    }

    public String getMutationKey() {
        return mutationKey;
    }

    public String getErrorKey() {
        return errorKey;
    }
}
