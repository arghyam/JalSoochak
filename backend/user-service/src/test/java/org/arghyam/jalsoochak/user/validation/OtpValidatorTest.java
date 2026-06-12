package org.arghyam.jalsoochak.user.validation;

import jakarta.validation.ConstraintValidatorContext;
import org.arghyam.jalsoochak.user.config.properties.OtpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OtpValidator")
class OtpValidatorTest {

    private static final int OTP_LENGTH = 6;

    private OtpProperties otpProperties;
    private OtpValidator validator;

    @Mock
    private ConstraintValidatorContext context;

    @BeforeEach
    void setUp() {
        otpProperties = new OtpProperties(5, 3, 60, OTP_LENGTH, "WHATSAPP", null);
        validator = new OtpValidator(otpProperties);
    }

    @Nested
    @DisplayName("valid OTP")
    class ValidOtp {

        @Test
        @DisplayName("accepts a 6-digit numeric OTP")
        void acceptsValidOtp() {
            assertThat(validator.isValid("123456", context)).isTrue();
        }

        @Test
        @DisplayName("null is treated as valid (@NotBlank handles it)")
        void nullIsValid() {
            assertThat(validator.isValid(null, context)).isTrue();
        }
    }

    @Nested
    @DisplayName("invalid OTP")
    class InvalidOtp {

        @Test
        @DisplayName("rejects OTP that is too short")
        void tooShort() {
            ConstraintValidatorContext.ConstraintViolationBuilder builder =
                    mock(ConstraintValidatorContext.ConstraintViolationBuilder.class);
            when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(builder);
            when(builder.addConstraintViolation()).thenReturn(context);
            assertThat(validator.isValid("12345", context)).isFalse();
        }

        @Test
        @DisplayName("rejects OTP that is too long")
        void tooLong() {
            ConstraintValidatorContext.ConstraintViolationBuilder builder =
                    mock(ConstraintValidatorContext.ConstraintViolationBuilder.class);
            when(context.buildConstraintViolationWithTemplate(anyString())).thenReturn(builder);
            when(builder.addConstraintViolation()).thenReturn(context);
            assertThat(validator.isValid("1234567", context)).isFalse();
        }

        @Test
        @DisplayName("rejects OTP containing non-digit characters")
        void nonDigit() {
            assertThat(validator.isValid("12345a", context)).isFalse();
        }

        @Test
        @DisplayName("rejects OTP with spaces")
        void withSpaces() {
            assertThat(validator.isValid("1234 6", context)).isFalse();
        }
    }
}
