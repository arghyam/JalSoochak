package org.arghyam.jalsoochak.analytics.service.water;

import java.util.Objects;

/**
 * What a {@link WaterQuantityCalculator} made of an amount: litres, or the reason there are none.
 *
 * <p>{@link NotDerivable} is not an error in the reading. It means the inputs needed to turn it into
 * litres are missing or unusable (no formula configured, no active pump, a pump parameter out of
 * range), so the day gets no total rather than a wrong one.
 */
public sealed interface WaterQuantityOutcome {

    /** @param litres the derived volume, never negative */
    record Derived(long litres) implements WaterQuantityOutcome {
        public Derived {
            if (litres < 0) {
                throw new IllegalArgumentException("litres must not be negative, got " + litres);
            }
        }
    }

    record NotDerivable(Reason reason) implements WaterQuantityOutcome {
        public NotDerivable {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Why an amount could not be turned into litres. Used as a metric tag, so keep it small. */
    enum Reason {
        /** The tenant has no ELM formula configured. */
        MISSING_FORMULA,
        /** The snapshot lists no active pump. */
        NO_ACTIVE_PUMP,
        /** No active pump has a value for a parameter the formula needs. */
        MISSING_PARAMETER,
        /** A parameter is out of range: zero or negative, or an efficiency above 1. */
        INVALID_PARAMETER
    }

    static WaterQuantityOutcome derived(long litres) {
        return new Derived(litres);
    }

    static WaterQuantityOutcome notDerivable(Reason reason) {
        return new NotDerivable(reason);
    }
}
