package org.arghyam.jalsoochak.tenant.dto.internal;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.arghyam.jalsoochak.tenant.enums.ElmFormula;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;

/**
 * DTO for the {@code ELM_WATER_QUANTITY_FORMULA} config key: which formula turns this tenant's ELM
 * readings into litres. Shape: {@code {"formula":"F1"}}. The code is accepted in any case and stored
 * in upper case.
 *
 * <p>The bean-validation annotation documents intent, but the enforced path is
 * {@link #validatedFormula()}: the config write path binds with {@code ObjectMapper.treeToValue}, which
 * does not trigger bean validation, so {@code {}} would otherwise be stored. Mirrors
 * {@code RegularityThresholdConfigDTO.validatedThresholdPercent()}.</p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public final class ElmFormulaConfigDTO implements ConfigValueDTO {

    @NotNull(message = "ELM formula cannot be null")
    private ElmFormula formula;

    /**
     * @throws InvalidConfigValueException if no formula is set. There is no default to fall back on.
     */
    public ElmFormula validatedFormula() {
        if (formula == null) {
            throw new InvalidConfigValueException(
                    "ELM_WATER_QUANTITY_FORMULA must set formula to one of F1, F2 or F3");
        }
        return formula;
    }
}
