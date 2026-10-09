package org.arghyam.jalsoochak.analytics.service.water;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.arghyam.jalsoochak.analytics.dto.event.CalculationParameters.Pump;
import org.arghyam.jalsoochak.analytics.service.water.AveragedPumpParameters.Available;
import org.arghyam.jalsoochak.analytics.service.water.AveragedPumpParameters.Unavailable;
import org.arghyam.jalsoochak.analytics.service.water.WaterQuantityOutcome.Reason;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Turns a snapshot's active pumps into one value for each parameter a formula needs.
 *
 * <p>A scheme rarely has more than one active pump; when it does, the pumps run together, and each
 * parameter is averaged over the pumps that have a value for it. Each pump's own value is checked
 * before averaging, so one pump out of range is caught even when the average would look fine.
 *
 * <p>Averages suit running together. F1 and F2 divide the pumps' total discharge rate by their total
 * consumption or power, and the ratio of two averages is the ratio of those totals, with a pump
 * missing a value counted at the others' average. PDU needs the total rate, so it multiplies the
 * average by the number of pumps, counting a missing value the same way. F3 is exact only for pumps
 * with the same efficiencies and head, since the meter's kWh can't be split between them. So is the
 * power factor a kVAh reading is converted with, for the same reason.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PumpParameterAggregator {

    /** 1 HP = 745.7 W. */
    static final BigDecimal KILOWATTS_PER_HP = new BigDecimal("0.7457");

    /**
     * {@code bHP} is converted to HP with 0.9863, the ratio of metric horsepower (735.5 W) to HP, then
     * to kW. Stored values in {@code bHP} are therefore treated as metric horsepower.
     */
    static final BigDecimal KILOWATTS_PER_BHP = new BigDecimal("0.9863").multiply(KILOWATTS_PER_HP);

    private final MeterRegistry meterRegistry;

    /**
     * @param pumps      the snapshot's active pumps; null or empty when the scheme has none
     * @param parameters the parameters the formula needs
     * @return each parameter's average, or {@link Reason#NO_ACTIVE_PUMP} when there are no pumps,
     *         {@link Reason#INVALID_PARAMETER} when a pump's value is 0 or less, or a
     *         {@linkplain PumpParameter#isFraction() fraction} above 1, and
     *         {@link Reason#MISSING_PARAMETER} when no pump has a value for a parameter. The
     *         parameters are checked in {@link PumpParameter} order and the first failure is returned.
     */
    public AveragedPumpParameters average(List<Pump> pumps, Set<PumpParameter> parameters) {
        if (pumps == null || pumps.isEmpty()) {
            return new Unavailable(Reason.NO_ACTIVE_PUMP);
        }
        Map<PumpParameter, BigDecimal> averages = new EnumMap<>(PumpParameter.class);
        for (PumpParameter parameter : PumpParameter.values()) {
            if (!parameters.contains(parameter)) {
                continue;
            }
            List<BigDecimal> values = new ArrayList<>();
            for (Pump pump : pumps) {
                Optional<BigDecimal> value = value(pump, parameter);
                if (value.isEmpty()) {
                    continue;
                }
                if (!inRange(value.get(), parameter)) {
                    log.warn("Pump value out of range: {}={} on pumpId={}; the water quantity cannot be calculated",
                            parameter, value.get(), pump.pumpId());
                    return new Unavailable(Reason.INVALID_PARAMETER);
                }
                values.add(value.get());
            }
            if (values.isEmpty()) {
                return new Unavailable(Reason.MISSING_PARAMETER);
            }
            averages.put(parameter, mean(values));
        }
        return new Available(averages);
    }

    private Optional<BigDecimal> value(Pump pump, PumpParameter parameter) {
        return switch (parameter) {
            case DISCHARGE_CAPACITY_LPM -> Optional.ofNullable(pump.pumpDischargeCapacityLpm());
            case UNITS_CONSUMED_PER_HOUR -> Optional.ofNullable(pump.unitsConsumedPerHour());
            case MOTOR_POWER_KW -> motorPowerKilowatts(pump);
            case PUMP_EFFICIENCY -> Optional.ofNullable(pump.pumpEfficiency());
            case MOTOR_EFFICIENCY -> Optional.ofNullable(pump.motorEfficiency());
            case PUMP_HEAD_M -> Optional.ofNullable(pump.pumpHeadM());
            case POWER_FACTOR -> Optional.ofNullable(pump.powerFactor());
        };
    }

    /**
     * {@code motor_power_unit} is free text, matched ignoring case and surrounding spaces. A motor
     * power in any other unit, or with no unit, is treated as missing and counted, so the pump's data
     * can be fixed.
     */
    private Optional<BigDecimal> motorPowerKilowatts(Pump pump) {
        if (pump.motorPower() == null) {
            return Optional.empty();
        }
        String unit = pump.motorPowerUnit() == null ? "" : pump.motorPowerUnit().trim().toLowerCase(Locale.ROOT);
        BigDecimal kilowattsPerUnit = switch (unit) {
            case "kw" -> BigDecimal.ONE;
            case "hp" -> KILOWATTS_PER_HP;
            case "bhp" -> KILOWATTS_PER_BHP;
            default -> null;
        };
        if (kilowattsPerUnit == null) {
            log.warn("Unknown motor_power_unit '{}' on pumpId={}; its motor power is treated as missing",
                    pump.motorPowerUnit(), pump.pumpId());
            meterRegistry.counter("water_quantity.motor_power_unit.unknown").increment();
            return Optional.empty();
        }
        return Optional.of(pump.motorPower().multiply(kilowattsPerUnit));
    }

    private static boolean inRange(BigDecimal value, PumpParameter parameter) {
        return value.signum() > 0 && (!parameter.isFraction() || value.compareTo(BigDecimal.ONE) <= 0);
    }

    private static BigDecimal mean(List<BigDecimal> values) {
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), MathContext.DECIMAL64);
    }
}
