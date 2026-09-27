package org.arghyam.jalsoochak.analytics.service.water;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one place the meter's native volume unit is converted to the unit the warehouse stores.
 *
 * <p>Bulk flow meters read in cubic metres (m&sup3;, identical to kilolitres), while
 * {@code analytics_schema.fact_water_quantity_table.water_quantity} is denominated in <strong>litres</strong>
 * — that is what every consumer already assumes: the {@code total_water_supplied_liters} CSV column and
 * JSON fields, {@code avgKld = litres / 1000} and {@code avgLpcd = litres / population} in the officer
 * daily report, and the litre-denominated supply-days-in-efficient-range and performance-score SQL.
 *
 * <p>Both writers into that column — the per-channel calculator on the reading path and
 * {@code FactServiceImpl.ingestWaterQuantity} on the telemetry-correction path — convert through this
 * class so the two cannot drift apart. There is no per-meter or per-scheme unit configuration anywhere
 * in the system, so a single global factor is correct.
 */
public final class WaterVolumeUnits {

    /** 1 m&sup3; = 1 KL = 1000 L. */
    public static final long LITRES_PER_CUBIC_METRE = 1000L;

    private static final BigDecimal LITRES_PER_CUBIC_METRE_DECIMAL =
            BigDecimal.valueOf(LITRES_PER_CUBIC_METRE);

    private WaterVolumeUnits() {
    }

    /**
     * Converts a decimal volume in cubic metres to whole litres.
     *
     * <p>Meter readings carry a decimal digit, so a derived volume is decimal too; the stored column is
     * {@code BIGINT} litres. A litre is three orders of magnitude finer than the meters resolve, so
     * rounding here discards nothing real — and it keeps every consumer (the {@code Long}/{@code BIGINT}
     * DTOs, the {@code total_water_supplied_liters} CSV column, the frontend) unchanged.
     *
     * <p><strong>{@link RoundingMode#HALF_UP} is required, not preferred.</strong> The history recompute
     * in {@code db/scripts/recompute_water_quantity.sql} expresses the same conversion as Postgres
     * {@code ROUND(... * 1000)}, whose numeric rounding is half-away-from-zero. Volumes here are never
     * negative, where half-up and half-away-from-zero coincide — which is what lets
     * {@code WaterQuantityBackfillParityIntegrationTest} assert the two land on the same value.
     *
     * <p><strong>Out of range is signalled, never clamped.</strong> Readings are unbounded
     * {@code NUMERIC} end to end and the submission API bounds them only from below, so a mis-read
     * reading really can produce a delta past {@code long}. Saturating at {@code Long.MAX_VALUE} would
     * invent a number and bury the bad reading behind it — the same reason
     * {@code FactServiceImpl.warnIfImplausible} reports rather than clamps. The ingestion boundary
     * catches {@link WaterVolumeOutOfRangeException}, reports it, and declines to write a volume for
     * that day; the reading itself still lands.
     *
     * @param cubicMetres volume in m&sup3;
     * @return the same volume in litres, rounded to the nearest whole litre
     * @throws WaterVolumeOutOfRangeException if the result does not fit the {@code BIGINT} column
     */
    public static long cubicMetresToLitres(BigDecimal cubicMetres) {
        BigDecimal litres = cubicMetres.multiply(LITRES_PER_CUBIC_METRE_DECIMAL)
                .setScale(0, RoundingMode.HALF_UP);
        try {
            return litres.longValueExact();
        } catch (ArithmeticException e) {
            throw new WaterVolumeOutOfRangeException(cubicMetres);
        }
    }

    /**
     * Converts a whole number of cubic metres to litres.
     *
     * <p>Kept alongside the {@link #cubicMetresToLitres(BigDecimal)} overload for the configured
     * implausibility threshold, which is a whole-m&sup3; setting rather than a derived volume.
     *
     * @param cubicMetres volume in m&sup3;
     * @return the same volume in litres
     * @throws ArithmeticException if the result overflows {@code long}
     */
    public static long cubicMetresToLitres(long cubicMetres) {
        return Math.multiplyExact(cubicMetres, LITRES_PER_CUBIC_METRE);
    }
}
