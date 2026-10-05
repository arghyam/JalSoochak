package org.arghyam.jalsoochak.tenant.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Set;

/**
 * Validator for communication channel codes.
 * Validates that channels are within the allowed set: BFM, ELM, PDU, IOT, MAN.
 */
public class ChannelValidator implements ConstraintValidator<ValidChannelList, java.util.List<String>> {

    public static final Set<String> VALID_CHANNELS = Set.of("BFM", "ELM", "PDU", "IOT", "MAN");

    @Override
    public void initialize(ValidChannelList constraintAnnotation) {
        ConstraintValidator.super.initialize(constraintAnnotation);
    }

    @Override
    public boolean isValid(java.util.List<String> value, ConstraintValidatorContext context) {
        if (value == null || value.isEmpty()) {
            return true; // Null/empty is validated by @NotEmpty
        }

        boolean isValid = value.stream().allMatch(VALID_CHANNELS::contains);

        if (!isValid) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                    "Invalid channel codes. Allowed channels are: BFM, ELM, PDU, IOT, MAN"
            ).addConstraintViolation();
        }

        return isValid;
    }
}
