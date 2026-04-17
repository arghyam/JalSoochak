package org.arghyam.jalsoochak.user.validation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PasswordValidator")
class PasswordValidatorTest {

    private PasswordValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PasswordValidator();
    }

    @Nested
    @DisplayName("valid passwords")
    class ValidPasswords {

        @ParameterizedTest(name = "[{index}] \"{0}\" is valid")
        @ValueSource(strings = {
                "Passw0rd!",
                "MyStr0ng#Pass",
                "Ab1!abcd",
                "UPPER1lower@",
                "A1!bbbbb",
                "ValidPas$1234"
        })
        @DisplayName("accepts passwords meeting the policy")
        void acceptsValidPasswords(String password) {
            assertThat(validator.isValid(password, null)).isTrue();
        }
    }

    @Nested
    @DisplayName("null input")
    class NullInput {

        @Test
        @DisplayName("null is treated as valid (handled by @NotBlank)")
        void nullIsValid() {
            assertThat(validator.isValid(null, null)).isTrue();
        }
    }

    @Nested
    @DisplayName("invalid passwords")
    class InvalidPasswords {

        @Test
        @DisplayName("rejects password shorter than 8 characters")
        void tooShort() {
            assertThat(validator.isValid("Abc1!", null)).isFalse();
        }

        @Test
        @DisplayName("rejects password longer than 64 characters")
        void tooLong() {
            String longPwd = "Aa1!" + "a".repeat(62);
            assertThat(validator.isValid(longPwd, null)).isFalse();
        }

        @Test
        @DisplayName("rejects password with no uppercase letter")
        void noUppercase() {
            assertThat(validator.isValid("passw0rd!", null)).isFalse();
        }

        @Test
        @DisplayName("rejects password with no lowercase letter")
        void noLowercase() {
            assertThat(validator.isValid("PASSW0RD!", null)).isFalse();
        }

        @Test
        @DisplayName("rejects password with no digit")
        void noDigit() {
            assertThat(validator.isValid("Password!", null)).isFalse();
        }

        @Test
        @DisplayName("rejects password with no special character")
        void noSpecialChar() {
            assertThat(validator.isValid("Passw0rd", null)).isFalse();
        }
    }
}
