package org.arghyam.jalsoochak.tenant.enums;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * The formulas that turn an ELM (electricity meter) reading's kWh into litres. A tenant picks one
 * with its {@code ELM_WATER_QUANTITY_FORMULA} config value.
 *
 * <p>Stored by name, e.g. {@code "F1"}. telemetry-service copies the code into each ELM reading it
 * publishes, and analytics-service holds the formulas themselves.
 */
public enum ElmFormula {

    /** kWh × discharge (L/min) × 60 ÷ units consumed per hour. */
    F1,

    /** kWh × discharge (L/min) × 60 ÷ motor power in kW. */
    F2,

    /** 366.97 × kWh × pump efficiency × motor efficiency ÷ pump head (m), in m³. */
    F3;

    /**
     * Accepts the code in any case, so {@code "f1"} binds as {@link #F1} and is stored as
     * {@code "F1"}. An unknown value throws, which the config endpoint surfaces as 400.
     */
    @JsonCreator
    public static ElmFormula fromCode(String value) {
        if (value != null) {
            String normalised = value.trim().toUpperCase(Locale.ROOT);
            for (ElmFormula formula : values()) {
                if (formula.name().equals(normalised)) {
                    return formula;
                }
            }
        }
        throw new IllegalArgumentException("Unsupported ELM formula '" + value + "'. Supported formulas: "
                + Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", ")));
    }
}
