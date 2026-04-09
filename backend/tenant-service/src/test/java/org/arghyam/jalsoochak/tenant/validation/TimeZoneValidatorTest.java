package org.arghyam.jalsoochak.tenant.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TimeZoneValidator}.
 *
 * <p>Verifies that valid IANA timezone identifiers pass, invalid strings fail,
 * and that null/blank values are accepted (delegated to {@code @NotBlank}).</p>
 */
@ExtendWith(MockitoExtension.class)
class TimeZoneValidatorTest {

    private final TimeZoneValidator validator = new TimeZoneValidator();

    @Mock
    private ConstraintValidatorContext context;

    @Mock
    private ConstraintValidatorContext.ConstraintViolationBuilder violationBuilder;

    // ── valid timezones ──────────────────────────────────────────────────────────

    @Test
    void isValid_returnsTrue_forAsiaKolkata() {
        assertThat(validator.isValid("Asia/Kolkata", context)).isTrue();
        verifyNoInteractions(context);
    }

    @Test
    void isValid_returnsTrue_forUtc() {
        assertThat(validator.isValid("UTC", context)).isTrue();
    }

    @Test
    void isValid_returnsTrue_forAmericaNewYork() {
        assertThat(validator.isValid("America/New_York", context)).isTrue();
    }

    // ── null / blank (validated by @NotBlank, not this validator) ────────────────

    @Test
    void isValid_returnsTrue_forNull() {
        assertThat(validator.isValid(null, context)).isTrue();
        verifyNoInteractions(context);
    }

    @Test
    void isValid_returnsTrue_forBlankString() {
        assertThat(validator.isValid("   ", context)).isTrue();
        verifyNoInteractions(context);
    }

    // ── invalid timezones ────────────────────────────────────────────────────────

    @Test
    void isValid_returnsFalse_forGibberishString() {
        when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(violationBuilder);

        assertThat(validator.isValid("NotATimezone", context)).isFalse();

        verify(context).disableDefaultConstraintViolation();
        verify(violationBuilder).addConstraintViolation();
    }

    @Test
    void isValid_returnsFalse_forPartiallyCorrectZone() {
        when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(violationBuilder);

        assertThat(validator.isValid("Asia/", context)).isFalse();

        verify(context).disableDefaultConstraintViolation();
        verify(violationBuilder).addConstraintViolation();
    }

    @Test
    void isValid_returnsFalse_forOffsetOnlyString() {
        // "+05:30" is valid in Java ZoneId, so test a clearly invalid offset
        when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(violationBuilder);

        assertThat(validator.isValid("GMT+25:00", context)).isFalse();

        verify(context).disableDefaultConstraintViolation();
        verify(violationBuilder).addConstraintViolation();
    }

    @Test
    void isValid_acceptsValidOffset() {
        // Document that valid offset strings like "+05:30" are accepted by ZoneId.of()
        assertThat(validator.isValid("+05:30", context)).isTrue();
        verifyNoInteractions(context);
    }
}