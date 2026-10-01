package org.arghyam.jalsoochak.tenant.exception;

/**
 * MESSAGING-PROVIDER-SETTINGS: a settings DTO rejected its own input while the request body was
 * being bound.
 *
 * <p>Jackson wraps whatever a {@code @JsonCreator} or {@code @JsonAnySetter} throws in a
 * {@code JsonMappingException}, so by the time {@code GlobalExceptionHandler} sees it the only
 * thing left to match on is the cause chain. That handler echoes this exception's message back to
 * the caller, because naming the rejected property or provider is the whole point of rejecting it.
 *
 * <p>It exists so that the echo can match <em>this</em> type rather than
 * {@link IllegalArgumentException} at large. A JDK or third-party
 * {@code IllegalArgumentException} raised anywhere inside binding — a {@code java.time} or
 * {@code URI} coercion, a {@code @JsonCreator} on an unrelated DTO — routinely quotes the input
 * that produced it, and on these endpoints that input sits next to a credential. Those stay
 * collapsed into the deliberately opaque generic message.
 *
 * <p>Extends {@link IllegalArgumentException} so that a settings object rejected outside body
 * binding still answers 400 through the handler that already covers that family.
 */
public class SettingsRejectedException extends IllegalArgumentException {

    public SettingsRejectedException(String message) {
        super(message);
    }
}
