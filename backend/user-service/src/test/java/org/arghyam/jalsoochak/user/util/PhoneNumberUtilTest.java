package org.arghyam.jalsoochak.user.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PhoneNumberUtil")
class PhoneNumberUtilTest {

    // ─── isValidIndianMobile ──────────────────────────────────────────────────

    @Nested
    @DisplayName("isValidIndianMobile")
    class IsValidIndianMobile {

        @ParameterizedTest(name = "valid mobile ''{0}''")
        @ValueSource(strings = {"9876543210", "8000000000", "7999999999", "6000000000"})
        @DisplayName("returns true for valid 10-digit Indian mobiles starting with 6-9")
        void validNumbers(String phone) {
            assertThat(PhoneNumberUtil.isValidIndianMobile(phone)).isTrue();
        }

        @Test
        @DisplayName("returns false for null")
        void nullInput() {
            assertThat(PhoneNumberUtil.isValidIndianMobile(null)).isFalse();
        }

        @ParameterizedTest(name = "invalid ''{0}''")
        @ValueSource(strings = {
                "",            // empty
                "987654321",   // 9 digits
                "98765432101", // 11 digits
                "5876543210",  // starts with 5
                "4876543210",  // starts with 4
                "1234567890",  // starts with 1
                "0876543210",  // starts with 0
                "9876 543210", // contains space
                "+919876543210", // has country code with +
                "abcdefghij",  // letters
        })
        @DisplayName("returns false for invalid inputs")
        void invalidNumbers(String phone) {
            assertThat(PhoneNumberUtil.isValidIndianMobile(phone)).isFalse();
        }

        @Test
        @DisplayName("trims surrounding whitespace before validation")
        void trimsWhitespace() {
            assertThat(PhoneNumberUtil.isValidIndianMobile("  9876543210  ")).isTrue();
        }
    }

    // ─── normalizeIndianMobileForDb ───────────────────────────────────────────

    @Nested
    @DisplayName("normalizeIndianMobileForDb")
    class NormalizeIndianMobileForDb {

        @Test
        @DisplayName("returns null for null input")
        void nullInput() {
            assertThat(PhoneNumberUtil.normalizeIndianMobileForDb(null)).isNull();
        }

        @Test
        @DisplayName("returns empty string unchanged")
        void emptyInput() {
            assertThat(PhoneNumberUtil.normalizeIndianMobileForDb("")).isEmpty();
        }

        @Test
        @DisplayName("prepends '91' to a 10-digit number")
        void prependsCountryCode() {
            assertThat(PhoneNumberUtil.normalizeIndianMobileForDb("9876543210"))
                    .isEqualTo("919876543210");
        }

        @Test
        @DisplayName("keeps a 12-digit number starting with '91' unchanged")
        void alreadyNormalized() {
            assertThat(PhoneNumberUtil.normalizeIndianMobileForDb("919876543210"))
                    .isEqualTo("919876543210");
        }

        @Test
        @DisplayName("prepends '91' to a number that starts with '91' but is not 12 digits")
        void shortNumberStartingWith91() {
            // Only 11 chars starting with "91" — should still get "91" prepended
            assertThat(PhoneNumberUtil.normalizeIndianMobileForDb("91987654321"))
                    .isEqualTo("9191987654321");
        }

        @Test
        @DisplayName("trims whitespace before normalizing")
        void trimsBeforeNormalizing() {
            assertThat(PhoneNumberUtil.normalizeIndianMobileForDb("  9876543210  "))
                    .isEqualTo("919876543210");
        }
    }
}
