package org.arghyam.jalsoochak.tenant.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ChannelValidator}.
 *
 * <p>Verifies that only the allowed channel codes (BFM, ELM, PDU, IOT, MAN)
 * pass validation, and that null/empty lists are treated as valid.</p>
 */
@ExtendWith(MockitoExtension.class)
class ChannelValidatorTest {

    private final ChannelValidator validator = new ChannelValidator();

    @Mock
    private ConstraintValidatorContext context;

    @Mock
    private ConstraintValidatorContext.ConstraintViolationBuilder violationBuilder;

    // ── valid inputs ─────────────────────────────────────────────────────────────

    @Test
    void isValid_returnsTrue_forAllAllowedChannels() {
        assertThat(validator.isValid(List.of("BFM", "ELM", "PDU", "IOT", "MAN"), context)).isTrue();
        verifyNoInteractions(context);
    }

    @Test
    void isValid_returnsTrue_forSingleValidChannel() {
        assertThat(validator.isValid(List.of("BFM"), context)).isTrue();
        assertThat(validator.isValid(List.of("IOT"), context)).isTrue();
        verifyNoInteractions(context);
    }

    @Test
    void isValid_returnsTrue_forNullList() {
        assertThat(validator.isValid(null, context)).isTrue();
        verifyNoInteractions(context);
    }

    @Test
    void isValid_returnsTrue_forEmptyList() {
        assertThat(validator.isValid(List.of(), context)).isTrue();
        verifyNoInteractions(context);
    }

    // ── invalid inputs ───────────────────────────────────────────────────────────

    @Test
    void isValid_returnsFalse_forUnknownChannelCode() {
        when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(violationBuilder);

        assertThat(validator.isValid(List.of("INVALID"), context)).isFalse();

        verify(context).disableDefaultConstraintViolation();
        verify(context).buildConstraintViolationWithTemplate(contains("BFM"));
        verify(violationBuilder).addConstraintViolation();
    }

    @Test
    void isValid_returnsFalse_whenMixedValidAndInvalidChannels() {
        when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(violationBuilder);

        assertThat(validator.isValid(List.of("BFM", "UNKNOWN"), context)).isFalse();

        verify(context).disableDefaultConstraintViolation();
        verify(context).buildConstraintViolationWithTemplate(anyString());
        verify(violationBuilder).addConstraintViolation();
    }

    @Test
    void isValid_returnsFalse_forLowercaseChannelCode() {
        when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(violationBuilder);

        // Validator is case-sensitive — "bfm" is not the same as "BFM"
        assertThat(validator.isValid(List.of("bfm"), context)).isFalse();

        verify(context).disableDefaultConstraintViolation();
        verify(context).buildConstraintViolationWithTemplate(anyString());
        verify(violationBuilder).addConstraintViolation();
    }
}