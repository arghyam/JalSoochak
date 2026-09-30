package org.arghyam.jalsoochak.analytics.service.water;

import org.arghyam.jalsoochak.analytics.dto.event.CalculationParameters.Pump;

import java.math.BigDecimal;

/** Builds a snapshot pump with only the values a test is about; the rest stay null, as in the table. */
final class PumpFixture {

    private final long pumpId;
    private BigDecimal dischargeCapacityLpm;
    private BigDecimal pumpEfficiency;
    private BigDecimal pumpHeadM;
    private BigDecimal motorPower;
    private String motorPowerUnit;
    private BigDecimal motorEfficiency;
    private BigDecimal unitsConsumedPerHour;

    private PumpFixture(long pumpId) {
        this.pumpId = pumpId;
    }

    static PumpFixture pump(long pumpId) {
        return new PumpFixture(pumpId);
    }

    PumpFixture dischargeCapacityLpm(String value) {
        dischargeCapacityLpm = new BigDecimal(value);
        return this;
    }

    PumpFixture pumpEfficiency(String value) {
        pumpEfficiency = new BigDecimal(value);
        return this;
    }

    PumpFixture pumpHeadM(String value) {
        pumpHeadM = new BigDecimal(value);
        return this;
    }

    PumpFixture motorPower(String value, String unit) {
        motorPower = value == null ? null : new BigDecimal(value);
        motorPowerUnit = unit;
        return this;
    }

    PumpFixture motorEfficiency(String value) {
        motorEfficiency = new BigDecimal(value);
        return this;
    }

    PumpFixture unitsConsumedPerHour(String value) {
        unitsConsumedPerHour = new BigDecimal(value);
        return this;
    }

    Pump build() {
        return new Pump(pumpId, dischargeCapacityLpm, pumpEfficiency, pumpHeadM, motorPower, motorPowerUnit,
                motorEfficiency, unitsConsumedPerHour);
    }
}
