package org.arghyam.jalsoochak.message.channel.provider;

import java.util.Objects;

/**
 * A failure the WhatsApp provider reported, tagged with the {@link WhatsAppSendStage} it happened at.
 *
 * <p>Callers classify a failed send by reading {@link #getStage()} instead of inspecting provider
 * types. Adapters throw subclasses that carry their own detail — which GraphQL mutation failed, say —
 * and fix the stage when they create the exception, so the knowledge of what each provider call
 * means stays beside the provider contract.</p>
 *
 * <p>Two kinds of failure are deliberately not this type. Our own configuration and input errors stay
 * {@link IllegalArgumentException} / {@link IllegalStateException}, which callers read as
 * {@link WhatsAppSendStage#CONFIG}. A transport timeout surfaces as whatever the HTTP client throws,
 * because it happens before the provider has answered and so before any stage could be assigned.</p>
 */
public class WhatsAppSendException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final WhatsAppSendStage stage;

    /** The provider's own error key, or {@code null} when it gave none. */
    private final String errorKey;

    public WhatsAppSendException(WhatsAppSendStage stage, String errorKey, String message) {
        super(message);
        this.stage = Objects.requireNonNull(stage, "stage");
        this.errorKey = errorKey;
    }

    public WhatsAppSendStage getStage() {
        return stage;
    }

    public String getErrorKey() {
        return errorKey;
    }
}
