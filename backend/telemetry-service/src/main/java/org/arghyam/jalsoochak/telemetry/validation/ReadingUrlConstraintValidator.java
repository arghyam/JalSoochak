package org.arghyam.jalsoochak.telemetry.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import org.arghyam.jalsoochak.telemetry.security.MediaUrlNotAllowedException;
import org.arghyam.jalsoochak.telemetry.security.MediaUrlValidator;
import org.springframework.beans.factory.annotation.Value;

import java.util.regex.Pattern;

/**
 * Vets {@code readingUrl} before it is stored and handed to FlowVision as {@code imageURL}.
 *
 * <p>This service never fetches the URL itself — FlowVision does — so the exposure being closed here
 * is a request forgery aimed at that service, plus the plain hygiene of not persisting a value that
 * later renders as an image somewhere. Rejecting at the DTO keeps it a 400 on the existing readings
 * error shape rather than a failure discovered halfway through processing.
 *
 * <p>It shares the one {@link MediaUrlValidator} with the Glific media fetch: both vet a
 * caller-supplied meter-image URL under the same rules, so a second identically-configured policy
 * would only be somewhere for the two to drift apart. The kill switch is the exception and is
 * deliberately its own: this rule is the one with a live, high-volume integration behind it, and
 * turning it off under pressure must not also disarm the guard on the unauthenticated webhook.
 */
public class ReadingUrlConstraintValidator implements ConstraintValidator<ValidReadingUrl, String> {

    /** A scheme as RFC 3986 defines one, which is what separates a URL from a relative reference. */
    private static final Pattern HAS_SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.\\-]*:");

    private final MediaUrlValidator readingUrlValidator;
    private final boolean enabled;

    public ReadingUrlConstraintValidator(
            MediaUrlValidator readingUrlValidator,
            @Value("${telemetry.reading-url.validation.enabled:true}") boolean enabled) {
        this.readingUrlValidator = readingUrlValidator;
        this.enabled = enabled;
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (!enabled) {
            return true;
        }
        // Absent is not invalid — a manual reading carries no URL at all, and that is most of the
        // traffic here. The rejection reason is already logged by the policy.
        if (value == null || value.isBlank()) {
            return true;
        }
        String candidate = value.trim();
        if (!HAS_SCHEME.matcher(candidate).find()) {
            return isSafeRelativeReference(candidate);
        }
        try {
            readingUrlValidator.validate(candidate);
            return true;
        } catch (MediaUrlNotAllowedException e) {
            return false;
        }
    }

    /**
     * A value with no scheme is a bare object key, not a URL — it names no host and so cannot steer
     * a request anywhere. Roughly 0.08% of historical submissions look like this and they are
     * resolved downstream against a fixed base, so refusing them would break a live shape while
     * closing nothing.
     *
     * <p>The two relative forms that <em>can</em> leave that base are still refused: a
     * protocol-relative reference carries its own authority, and a traversal segment climbs out of
     * the base path.
     */
    private boolean isSafeRelativeReference(String candidate) {
        return !candidate.startsWith("//") && !candidate.contains("..");
    }
}
