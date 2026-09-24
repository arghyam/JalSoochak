package org.arghyam.jalsoochak.telemetry.dto.requests;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.arghyam.jalsoochak.telemetry.validation.ReadingUrlTestValidation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalReadingRequestValidationTest {

    // The reading-url constraint takes its policy through the constructor, so it needs the same
    // factory wiring the container provides — see ReadingUrlTestValidation.
    private static final Validator VALIDATOR = ReadingUrlTestValidation.validator();

    @Test
    void validationPassesWhenOnlyStateSchemeIdProvided() {
        CanonicalReadingRequest request = baseRequestBuilder()
                .stateSchemeId("30178236")
                .centreSchemeId(null)
                .build();

        Set<ConstraintViolation<CanonicalReadingRequest>> violations = VALIDATOR.validate(request);
        assertEquals(0, violations.size());
    }

    @Test
    void validationPassesWhenOnlyCentreSchemeIdProvided() {
        CanonicalReadingRequest request = baseRequestBuilder()
                .stateSchemeId(null)
                .centreSchemeId("30244993")
                .build();

        Set<ConstraintViolation<CanonicalReadingRequest>> violations = VALIDATOR.validate(request);
        assertEquals(0, violations.size());
    }

    @Test
    void validationFailsWhenBothSchemeIdsMissing() {
        CanonicalReadingRequest request = baseRequestBuilder()
                .stateSchemeId(null)
                .centreSchemeId(null)
                .build();

        Set<ConstraintViolation<CanonicalReadingRequest>> violations = VALIDATOR.validate(request);
        assertEquals(1, violations.size());
        assertTrue(violations.iterator().next().getMessage().contains("Either stateSchemeId or centreSchemeId must be provided"));
    }

    @Test
    void validationPassesWhenReadingDateTimeMissing() {
        CanonicalReadingRequest request = baseRequestBuilder()
                .readingDateTime(null)
                .stateSchemeId("30178236")
                .centreSchemeId(null)
                .build();

        Set<ConstraintViolation<CanonicalReadingRequest>> violations = VALIDATOR.validate(request);
        assertEquals(0, violations.size());
    }

    @Test
    void validationPassesWhenReadingUrlMissingAndConfirmedReadingProvided() {
        CanonicalReadingRequest request = baseRequestBuilder()
                .readingUrl(null)
                .confirmedReading(new BigDecimal("123.4"))
                .stateSchemeId("30178236")
                .centreSchemeId(null)
                .build();

        Set<ConstraintViolation<CanonicalReadingRequest>> violations = VALIDATOR.validate(request);
        assertEquals(0, violations.size());
    }

    @Test
    void validationPassesWhenPhoneNumberMissing() {
        // PHONE-OPTIONAL: submissions may omit the phone; the operator is inferred from the scheme.
        CanonicalReadingRequest request = baseRequestBuilder()
                .phoneNumber(null)
                .stateSchemeId("30178236")
                .build();

        Set<ConstraintViolation<CanonicalReadingRequest>> violations = VALIDATOR.validate(request);
        assertEquals(0, violations.size());
    }

    @Test
    void validationPassesWhenPhoneNumberBlank() {
        // A blank phone is treated exactly like an absent one, not as a validation error.
        CanonicalReadingRequest request = baseRequestBuilder()
                .phoneNumber("   ")
                .stateSchemeId("30178236")
                .build();

        Set<ConstraintViolation<CanonicalReadingRequest>> violations = VALIDATOR.validate(request);
        assertEquals(0, violations.size());
    }

    @Test
    void validationFailsWhenReadingUrlAndConfirmedReadingMissing() {
        CanonicalReadingRequest request = baseRequestBuilder()
                .readingUrl(null)
                .confirmedReading(null)
                .stateSchemeId("30178236")
                .centreSchemeId(null)
                .build();

        Set<ConstraintViolation<CanonicalReadingRequest>> violations = VALIDATOR.validate(request);
        assertEquals(1, violations.size());
        assertTrue(violations.iterator().next().getMessage().contains("Either readingUrl or confirmedReading must be provided"));
    }

    private CanonicalReadingRequest.CanonicalReadingRequestBuilder baseRequestBuilder() {
        return CanonicalReadingRequest.builder()
                .readingUrl("https://example.com/meter.jpg")
                .phoneNumber("919999999999")
                .readingDateTime(OffsetDateTime.parse("2026-04-23T07:38:22.031Z"));
    }
}
