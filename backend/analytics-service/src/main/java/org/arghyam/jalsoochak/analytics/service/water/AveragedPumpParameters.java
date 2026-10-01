package org.arghyam.jalsoochak.analytics.service.water;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * What {@link PumpParameterAggregator} made of a scheme's active pumps: one value for each parameter
 * a formula asked for, or the reason there are none.
 */
public sealed interface AveragedPumpParameters {

    /** @param values each requested parameter's value, averaged over the pumps that have one */
    record Available(Map<PumpParameter, BigDecimal> values) implements AveragedPumpParameters {

        public Available {
            values = Map.copyOf(values);
        }

        /**
         * @throws IllegalArgumentException if the parameter was not requested, which is a bug in the
         *         formula asking for it
         */
        public BigDecimal get(PumpParameter parameter) {
            BigDecimal value = values.get(parameter);
            if (value == null) {
                throw new IllegalArgumentException(parameter + " was not requested from the aggregator");
            }
            return value;
        }
    }

    record Unavailable(WaterQuantityOutcome.Reason reason) implements AveragedPumpParameters {

        public Unavailable {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
