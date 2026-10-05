package org.arghyam.jalsoochak.tenant.dto.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link ManualReadingMaxValueConfigDTO#validatedMaxValues()} — the enforced validation
 * path, since generic/system config values are JsonNode-bound and never trigger bean validation.
 */
class ManualReadingMaxValueConfigDTOTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static ManualReadingMaxValueConfigDTO dto(Map<String, BigDecimal> maxValues) {
        return new ManualReadingMaxValueConfigDTO(maxValues);
    }

    @Test
    void bindsStringAndNumberValues() throws Exception {
        ManualReadingMaxValueConfigDTO bound = objectMapper.readValue(
                "{\"maxValues\":{\"BFM\":\"99999999\",\"ELM\":1234.5}}", ManualReadingMaxValueConfigDTO.class);

        assertThat(bound.validatedMaxValues())
                .containsEntry("BFM", new BigDecimal("99999999"))
                .containsEntry("ELM", new BigDecimal("1234.5"));
    }

    @Test
    void partialMap_isAccepted() {
        assertThat(dto(Map.of("BFM", new BigDecimal("50000"))).validatedMaxValues())
                .containsOnlyKeys("BFM");
    }

    @Test
    void emptyMap_isAccepted_andMeansNoLimit() {
        assertThat(dto(Map.of()).validatedMaxValues()).isEmpty();
    }

    @Test
    void channelCodes_areUpperCased() {
        assertThat(dto(Map.of("bfm", BigDecimal.TEN, "Pdu", BigDecimal.ONE)).validatedMaxValues())
                .containsOnlyKeys("BFM", "PDU");
    }

    @Test
    void sameChannelInTwoCases_isRejected() {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        values.put("BFM", BigDecimal.TEN);
        values.put("bfm", BigDecimal.ONE);

        assertThatThrownBy(() -> dto(values).validatedMaxValues())
                .isInstanceOf(InvalidConfigValueException.class)
                .hasMessageContaining("BFM");
    }

    @Test
    void nullMap_isRejected() {
        assertThatThrownBy(() -> dto(null).validatedMaxValues())
                .isInstanceOf(InvalidConfigValueException.class)
                .hasMessageContaining("maxValues");
    }

    @Test
    void unknownChannel_isRejected() {
        assertThatThrownBy(() -> dto(Map.of("XYZ", BigDecimal.TEN)).validatedMaxValues())
                .isInstanceOf(InvalidConfigValueException.class)
                .hasMessageContaining("XYZ");
    }

    @Test
    void nullValue_isRejected() {
        Map<String, BigDecimal> values = new HashMap<>();
        values.put("BFM", null);

        assertThatThrownBy(() -> dto(values).validatedMaxValues())
                .isInstanceOf(InvalidConfigValueException.class)
                .hasMessageContaining("BFM");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.001"})
    void nonPositiveValue_isRejected(String value) {
        assertThatThrownBy(() -> dto(Map.of("ELM", new BigDecimal(value))).validatedMaxValues())
                .isInstanceOf(InvalidConfigValueException.class)
                .hasMessageContaining("ELM");
    }

    @Test
    void pduAboveADay_isRejected() {
        assertThatThrownBy(() -> dto(Map.of("PDU", new BigDecimal("1441"))).validatedMaxValues())
                .isInstanceOf(InvalidConfigValueException.class)
                .hasMessageContaining("1440");
    }

    @Test
    void pduOfExactlyADay_isAccepted() {
        assertThat(dto(Map.of("PDU", new BigDecimal("1440"))).validatedMaxValues())
                .containsEntry("PDU", new BigDecimal("1440"));
    }
}
