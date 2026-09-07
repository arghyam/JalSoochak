package org.arghyam.jalsoochak.analytics.service.water;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WaterVolumeUnitsTest {

    @Test
    void cubicMetresToLitres_multipliesByOneThousand() {
        assertThat(WaterVolumeUnits.cubicMetresToLitres(0)).isZero();
        assertThat(WaterVolumeUnits.cubicMetresToLitres(1)).isEqualTo(1_000L);
        assertThat(WaterVolumeUnits.cubicMetresToLitres(150)).isEqualTo(150_000L);
    }

    @Test
    void cubicMetresToLitres_valueThatOverflowsIntStillConvertsExactly() {
        // 3_000_000 m3 x 1000 = 3e9, past Integer.MAX_VALUE. This is the case the BIGINT column and the
        // long return type exist for: it must convert, not wrap.
        assertThat(WaterVolumeUnits.cubicMetresToLitres(3_000_000L)).isEqualTo(3_000_000_000L);
    }

    @Test
    void cubicMetresToLitres_widestPlausibleReadingDeltaDoesNotOverflow() {
        // Meter indices are bounded by their digit count, not by a Java type, but a delta of this size
        // must still convert rather than throw — the ingestion path runs on the Kafka consumer thread,
        // where an ArithmeticException would fail the offset commit and retry forever.
        assertThat(WaterVolumeUnits.cubicMetresToLitres(Integer.MAX_VALUE))
                .isEqualTo(2_147_483_647_000L);
    }

    @Test
    void cubicMetresToLitres_overflowingLongThrowsRatherThanWrapping() {
        assertThatThrownBy(() -> WaterVolumeUnits.cubicMetresToLitres(Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    void cubicMetresToLitres_decimalCubicMetresBecomeExactLitres() {
        // The meters' decimal digit is a tenth of a m3, i.e. exactly 100 L — the precision this
        // conversion exists to carry through.
        assertThat(WaterVolumeUnits.cubicMetresToLitres(new BigDecimal("0.1"))).isEqualTo(100L);
        assertThat(WaterVolumeUnits.cubicMetresToLitres(new BigDecimal("12.3"))).isEqualTo(12_300L);
        assertThat(WaterVolumeUnits.cubicMetresToLitres(new BigDecimal("150.0"))).isEqualTo(150_000L);
    }

    @Test
    void cubicMetresToLitres_roundsToTheNearestLitreHalfUp() {
        // Below a litre there is nothing real left to keep — three orders of magnitude finer than the
        // meters resolve. HALF_UP is not a free choice: Postgres ROUND() in recompute_water_quantity.sql
        // is half-away-from-zero, and these two must agree for the backfill parity test to hold.
        assertThat(WaterVolumeUnits.cubicMetresToLitres(new BigDecimal("0.00049"))).isZero();
        assertThat(WaterVolumeUnits.cubicMetresToLitres(new BigDecimal("0.0005"))).isEqualTo(1L);
        assertThat(WaterVolumeUnits.cubicMetresToLitres(new BigDecimal("0.0015"))).isEqualTo(2L);
    }

    @Test
    void cubicMetresToLitres_trailingZerosDoNotChangeTheResult() {
        // NUMERIC is unconstrained at both ends of the wire, so the same volume can arrive at different
        // scales; the litre value must not depend on which.
        assertThat(WaterVolumeUnits.cubicMetresToLitres(new BigDecimal("42")))
                .isEqualTo(WaterVolumeUnits.cubicMetresToLitres(new BigDecimal("42.000")))
                .isEqualTo(42_000L);
    }

    @Test
    void cubicMetresToLitres_decimalOverflowingLongThrowsRatherThanTruncating() {
        assertThatThrownBy(() -> WaterVolumeUnits.cubicMetresToLitres(
                BigDecimal.valueOf(Long.MAX_VALUE)))
                .isInstanceOf(ArithmeticException.class);
    }
}
