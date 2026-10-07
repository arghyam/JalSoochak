package org.arghyam.jalsoochak.tenant.dto.internal;

import org.arghyam.jalsoochak.tenant.enums.ElmFormula;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ElmFormulaConfigDTO}: how the stored {@code {"formula": ...}} value binds, and
 * {@link ElmFormulaConfigDTO#validatedFormula()}, the enforced path for a value that binds to no
 * formula.
 */
class ElmFormulaConfigDTOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"F1, F1", "f2, F2", "' f3 ', F3"})
    void anyCase_bindsAndIsStoredInUpperCase(String submitted, ElmFormula expected) throws Exception {
        ElmFormulaConfigDTO dto = objectMapper.readValue(
                "{\"formula\":\"" + submitted + "\"}", ElmFormulaConfigDTO.class);

        assertThat(dto.validatedFormula()).isEqualTo(expected);
        assertThat(objectMapper.writeValueAsString(dto)).isEqualTo("{\"formula\":\"" + expected + "\"}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"F4", "F", "kWh", ""})
    void unknownOrEmptyFormula_doesNotBind(String submitted) {
        assertThatThrownBy(() -> objectMapper.readValue(
                "{\"formula\":\"" + submitted + "\"}", ElmFormulaConfigDTO.class))
                .isInstanceOf(JsonProcessingException.class)
                .hasMessageContaining("Unsupported ELM formula");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"formula\":null}"})
    void noFormula_bindsButIsRejected(String json) throws Exception {
        ElmFormulaConfigDTO dto = objectMapper.readValue(json, ElmFormulaConfigDTO.class);

        assertThatThrownBy(dto::validatedFormula)
                .isInstanceOf(InvalidConfigValueException.class)
                .hasMessageContaining("ELM_WATER_QUANTITY_FORMULA");
    }

    @Test
    void setFormula_isReturned() {
        assertThat(new ElmFormulaConfigDTO(ElmFormula.F2).validatedFormula()).isEqualTo(ElmFormula.F2);
    }
}
