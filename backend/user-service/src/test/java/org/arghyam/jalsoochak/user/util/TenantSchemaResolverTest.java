package org.arghyam.jalsoochak.user.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TenantSchemaResolver")
class TenantSchemaResolverTest {

    @Nested
    @DisplayName("requireSchemaNameFromTenantCode")
    class RequireSchemaNameFromTenantCode {

        @Test
        @DisplayName("returns 'tenant_<lowercased>' for a valid uppercase code")
        void validUpperCase() {
            assertThat(TenantSchemaResolver.requireSchemaNameFromTenantCode("MP"))
                    .isEqualTo("tenant_mp");
        }

        @Test
        @DisplayName("returns 'tenant_<lowercased>' for a valid lowercase code")
        void validLowerCase() {
            assertThat(TenantSchemaResolver.requireSchemaNameFromTenantCode("mp"))
                    .isEqualTo("tenant_mp");
        }

        @Test
        @DisplayName("returns 'tenant_<lowercased>' for a mixed-case alphanumeric code")
        void validMixedCase() {
            assertThat(TenantSchemaResolver.requireSchemaNameFromTenantCode("Tr1"))
                    .isEqualTo("tenant_tr1");
        }

        @Test
        @DisplayName("accepts underscore in tenant code")
        void validWithUnderscore() {
            assertThat(TenantSchemaResolver.requireSchemaNameFromTenantCode("state_1"))
                    .isEqualTo("tenant_state_1");
        }

        @Test
        @DisplayName("trims surrounding whitespace before processing")
        void trimsWhitespace() {
            assertThat(TenantSchemaResolver.requireSchemaNameFromTenantCode("  MP  "))
                    .isEqualTo("tenant_mp");
        }

        @ParameterizedTest(name = "blank input ''{0}'' throws BAD_REQUEST")
        @ValueSource(strings = {"", "   "})
        @DisplayName("throws BAD_REQUEST for blank input")
        void blankInput(String input) {
            assertThatThrownBy(() -> TenantSchemaResolver.requireSchemaNameFromTenantCode(input))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
        }

        @Test
        @DisplayName("throws BAD_REQUEST for null input")
        void nullInput() {
            assertThatThrownBy(() -> TenantSchemaResolver.requireSchemaNameFromTenantCode(null))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
        }

        @ParameterizedTest(name = "invalid format ''{0}'' throws BAD_REQUEST")
        @ValueSource(strings = {"mp state", "mp-state", "mp.state", "mp@state", "../mp", "mp;"})
        @DisplayName("throws BAD_REQUEST for tenant codes with disallowed characters")
        void invalidFormat(String code) {
            assertThatThrownBy(() -> TenantSchemaResolver.requireSchemaNameFromTenantCode(code))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
        }

        @Test
        @DisplayName("throws BAD_REQUEST when tenant code exceeds 32 characters")
        void tooLong() {
            String code = "a".repeat(33);
            assertThatThrownBy(() -> TenantSchemaResolver.requireSchemaNameFromTenantCode(code))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(ex -> assertThat(((ResponseStatusException) ex).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
        }

        @Test
        @DisplayName("accepts tenant code of exactly 32 characters")
        void maxLength() {
            String code = "a".repeat(32);
            assertThat(TenantSchemaResolver.requireSchemaNameFromTenantCode(code))
                    .isEqualTo("tenant_" + code);
        }
    }
}
