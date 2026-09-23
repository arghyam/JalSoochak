package org.arghyam.jalsoochak.telemetry.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a controller as part of the WhatsApp webhook surface: the family authenticated by the
 * {@code X-Webhook-Token} in {@link GlificWebhookAuthFilter}, not by the {@code X-Api-Key} in
 * {@link TelemetryApiKeyAuthFilter}.
 *
 * <p>The auth family is declared on the controller rather than inferred from a class name or a
 * package, so the webhook surface can span several controllers without one of them silently falling
 * out of scope. Two things key on this annotation, and must keep keying on the same one:
 * <ul>
 *   <li>{@code GlificWebhookRouteCoverageTest} asserts that the {@code @PostMapping}s across every
 *       annotated controller are exactly {@link GlificWebhookRoutes}. A webhook route missing from
 *       that allowlist is exempt from the API-key gate and unknown to the webhook gate — fully
 *       public.</li>
 *   <li>{@code WebhookValidationExceptionHandler} binds on it, so every annotated controller
 *       answers a validation failure in the envelope the chatbot flow parses.</li>
 * </ul>
 *
 * <p>Every mapping on an annotated controller must be a {@code POST}; the allowlist matches no other
 * method.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface WebhookRoute {
}
