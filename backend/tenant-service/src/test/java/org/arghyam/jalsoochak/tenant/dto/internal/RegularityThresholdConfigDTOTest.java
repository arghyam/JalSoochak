package org.arghyam.jalsoochak.tenant.dto.internal;

import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RegularityThresholdConfigDTO#validatedThresholdPercent()} — the enforced
 * validation path, since generic/system config values are JsonNode-bound and never trigger bean
 * validation.
 */
class RegularityThresholdConfigDTOTest {

    @ParameterizedTest
    @ValueSource(doubles = {0.01, 50.0, 87.5, 90.0, 100.0})
    void validValues_areAccepted(double value) {
        RegularityThresholdConfigDTO dto = new RegularityThresholdConfigDTO(value);
        assertThat(dto.validatedThresholdPercent()).isEqualTo(value);
    }

    @Test
    void nullValue_isRejected() {
        RegularityThresholdConfigDTO dto = new RegularityThresholdConfigDTO(null);
        assertThatThrownBy(dto::validatedThresholdPercent)
                .isInstanceOf(InvalidConfigValueException.class)
                .hasMessageContaining("REGULARITY_THRESHOLD_PERCENT");
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.0, -1.0, 100.01, 1000.0})
    void outOfRangeValues_areRejected(double value) {
        RegularityThresholdConfigDTO dto = new RegularityThresholdConfigDTO(value);
        assertThatThrownBy(dto::validatedThresholdPercent)
                .isInstanceOf(InvalidConfigValueException.class);
    }

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void nonFiniteValues_areRejected(double value) {
        RegularityThresholdConfigDTO dto = new RegularityThresholdConfigDTO(value);
        assertThatThrownBy(dto::validatedThresholdPercent)
                .isInstanceOf(InvalidConfigValueException.class);
    }
}
