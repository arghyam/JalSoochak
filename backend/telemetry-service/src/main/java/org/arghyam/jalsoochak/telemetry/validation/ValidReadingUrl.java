package org.arghyam.jalsoochak.telemetry.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Constrains a submitted meter-image URL to something worth handing to the OCR service.
 *
 * <p>A blank value stays valid: that is how a manual reading arrives, and it is the majority of the
 * traffic on this endpoint. {@code AssamReadingRequest#isReadingPresent} already requires either a
 * URL or a confirmed reading, so blankness is that rule's business, not this one's.
 */
@Documented
@Constraint(validatedBy = ReadingUrlConstraintValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidReadingUrl {

    String message() default "readingUrl must be an absolute http(s) URL on an approved image host";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
