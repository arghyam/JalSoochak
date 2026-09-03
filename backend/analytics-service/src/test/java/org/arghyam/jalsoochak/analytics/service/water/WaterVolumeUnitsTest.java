package org.arghyam.jalsoochak.analytics.service.water;

import org.junit.jupiter.api.Test;

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
    void cubicMetresToLitres_widestPossibleReadingDeltaDoesNotOverflow() {
        // Readings are INT, so the widest conceivable delta is Integer.MAX_VALUE m3. Multiplying it
        // must stay inside long — otherwise the ingestion path could throw and stall a Kafka partition.
        assertThat(WaterVolumeUnits.cubicMetresToLitres(Integer.MAX_VALUE))
                .isEqualTo(2_147_483_647_000L);
    }

    @Test
    void cubicMetresToLitres_overflowingLongThrowsRatherThanWrapping() {
        assertThatThrownBy(() -> WaterVolumeUnits.cubicMetresToLitres(Long.MAX_VALUE))
                .isInstanceOf(ArithmeticException.class);
    }
}
