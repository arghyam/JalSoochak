package org.arghyam.jalsoochak.telemetry.dto.requests;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssamReadingRequestValidationTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    @Test
    void validationPassesWhenOnlyStateSchemeIdProvided() {
        AssamReadingRequest request = baseRequestBuilder()
                .stateSchemeId("30178236")
                .centreSchemeId(null)
                .build();

        Set<ConstraintViolation<AssamReadingRequest>> violations = VALIDATOR.validate(request);
        assertEquals(0, violations.size());
    }

    @Test
    void validationPassesWhenOnlyCentreSchemeIdProvided() {
        AssamReadingRequest request = baseRequestBuilder()
                .stateSchemeId(null)
                .centreSchemeId("30244993")
                .build();

        Set<ConstraintViolation<AssamReadingRequest>> violations = VALIDATOR.validate(request);
        assertEquals(0, violations.size());
    }

    @Test
    void validationFailsWhenBothSchemeIdsMissing() {
        AssamReadingRequest request = baseRequestBuilder()
                .stateSchemeId(null)
                .centreSchemeId(null)
                .build();

        Set<ConstraintViolation<AssamReadingRequest>> violations = VALIDATOR.validate(request);
        assertEquals(1, violations.size());
        assertTrue(violations.iterator().next().getMessage().contains("Either stateSchemeId or centreSchemeId must be provided"));
    }

    @Test
    void validationPassesWhenReadingDateTimeMissing() {
        AssamReadingRequest request = baseRequestBuilder()
                .readingDateTime(null)
                .stateSchemeId("30178236")
                .centreSchemeId(null)
                .build();

        Set<ConstraintViolation<AssamReadingRequest>> violations = VALIDATOR.validate(request);
        assertEquals(0, violations.size());
    }

    private AssamReadingRequest.AssamReadingRequestBuilder baseRequestBuilder() {
        return AssamReadingRequest.builder()
                .readingUrl("https://example.com/meter.jpg")
                .phoneNumber("919999999999")
                .readingDateTime(OffsetDateTime.parse("2026-04-23T07:38:22.031Z"));
    }
}
