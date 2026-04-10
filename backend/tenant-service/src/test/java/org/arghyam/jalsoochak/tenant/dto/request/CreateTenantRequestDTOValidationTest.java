package org.arghyam.jalsoochak.tenant.dto.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for Bean Validation constraints on {@link CreateTenantRequestDTO}.
 * Uses the Jakarta Validator directly — no Spring context required.
 */
@DisplayName("CreateTenantRequestDTO validation")
class CreateTenantRequestDTOValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setup() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    private CreateTenantRequestDTO validRequest() {
        return CreateTenantRequestDTO.builder()
                .stateCode("KA")
                .lgdCode(29)
                .name("Karnataka")
                .build();
    }

    private Set<ConstraintViolation<CreateTenantRequestDTO>> validate(CreateTenantRequestDTO dto) {
        return validator.validate(dto);
    }

    @Test
    @DisplayName("valid request passes all constraints")
    void validRequest_passesValidation() {
        assertThat(validate(validRequest())).isEmpty();
    }

    // ── stateCode ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("stateCode constraints")
    class StateCodeConstraints {

        @Test
        @DisplayName("null stateCode fails @NotBlank")
        void nullStateCode_failsValidation() {
            CreateTenantRequestDTO dto = validRequest();
            dto.setStateCode(null);

            Set<ConstraintViolation<CreateTenantRequestDTO>> violations = validate(dto);

            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("stateCode"));
        }

        @Test
        @DisplayName("blank stateCode fails @NotBlank")
        void blankStateCode_failsValidation() {
            CreateTenantRequestDTO dto = validRequest();
            dto.setStateCode("   ");

            Set<ConstraintViolation<CreateTenantRequestDTO>> violations = validate(dto);

            assertThat(violations).isNotEmpty();
            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("stateCode"));
        }

        @Test
        @DisplayName("stateCode shorter than 2 chars fails @Size")
        void stateCodeTooShort_failsValidation() {
            CreateTenantRequestDTO dto = validRequest();
            dto.setStateCode("K");

            Set<ConstraintViolation<CreateTenantRequestDTO>> violations = validate(dto);

            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("stateCode"));
        }

        @Test
        @DisplayName("stateCode longer than 10 chars fails @Size")
        void stateCodeTooLong_failsValidation() {
            CreateTenantRequestDTO dto = validRequest();
            dto.setStateCode("ABCDEFGHIJK"); // 11 chars

            Set<ConstraintViolation<CreateTenantRequestDTO>> violations = validate(dto);

            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("stateCode"));
        }

        @ParameterizedTest(name = "\"{0}\" contains non-alphabetic characters")
        @ValueSource(strings = {"K1", "KA1", "KA_", "K-A", "12", "K A"})
        @DisplayName("stateCode with non-alphabetic characters fails @Pattern")
        void stateCodeWithNonAlphaChars_failsValidation(String stateCode) {
            CreateTenantRequestDTO dto = validRequest();
            dto.setStateCode(stateCode);

            Set<ConstraintViolation<CreateTenantRequestDTO>> violations = validate(dto);

            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("stateCode"));
        }

        @ParameterizedTest(name = "\"{0}\" is a valid stateCode")
        @ValueSource(strings = {"KA", "MH", "TN", "UP", "ABCDEF", "Maharashtra"})
        @DisplayName("stateCode with 2-10 alphabetic chars passes all constraints")
        void validStateCode_passesValidation(String stateCode) {
            CreateTenantRequestDTO dto = validRequest();
            dto.setStateCode(stateCode);

            Set<ConstraintViolation<CreateTenantRequestDTO>> violations = validate(dto);

            assertThat(violations).filteredOn(v -> v.getPropertyPath().toString().equals("stateCode"))
                    .isEmpty();
        }
    }

    // ── lgdCode ──────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("lgdCode constraints")
    class LgdCodeConstraints {

        @Test
        @DisplayName("null lgdCode fails @NotNull")
        void nullLgdCode_failsValidation() {
            CreateTenantRequestDTO dto = validRequest();
            dto.setLgdCode(null);

            Set<ConstraintViolation<CreateTenantRequestDTO>> violations = validate(dto);

            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("lgdCode"));
        }

        @Test
        @DisplayName("lgdCode of 0 fails @Positive")
        void zeroLgdCode_failsValidation() {
            CreateTenantRequestDTO dto = validRequest();
            dto.setLgdCode(0);

            Set<ConstraintViolation<CreateTenantRequestDTO>> violations = validate(dto);

            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("lgdCode"));
        }

        @Test
        @DisplayName("negative lgdCode fails @Positive")
        void negativeLgdCode_failsValidation() {
            CreateTenantRequestDTO dto = validRequest();
            dto.setLgdCode(-1);

            Set<ConstraintViolation<CreateTenantRequestDTO>> violations = validate(dto);

            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("lgdCode"));
        }

        @Test
        @DisplayName("positive lgdCode passes validation")
        void positiveLgdCode_passesValidation() {
            CreateTenantRequestDTO dto = validRequest();
            dto.setLgdCode(1);

            assertThat(validate(dto)).filteredOn(v -> v.getPropertyPath().toString().equals("lgdCode"))
                    .isEmpty();
        }
    }

    // ── name ─────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("name constraints")
    class NameConstraints {

        @Test
        @DisplayName("null name fails @NotBlank")
        void nullName_failsValidation() {
            CreateTenantRequestDTO dto = validRequest();
            dto.setName(null);

            Set<ConstraintViolation<CreateTenantRequestDTO>> violations = validate(dto);

            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
        }

        @Test
        @DisplayName("blank name fails @NotBlank")
        void blankName_failsValidation() {
            CreateTenantRequestDTO dto = validRequest();
            dto.setName("   ");

            Set<ConstraintViolation<CreateTenantRequestDTO>> violations = validate(dto);

            assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
        }

        @Test
        @DisplayName("non-blank name passes @NotBlank")
        void nonBlankName_passesValidation() {
            CreateTenantRequestDTO dto = validRequest();
            dto.setName("Karnataka");

            assertThat(validate(dto)).filteredOn(v -> v.getPropertyPath().toString().equals("name"))
                    .isEmpty();
        }
    }
}
