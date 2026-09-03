package org.arghyam.jalsoochak.analytics.service.water;

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

    private WaterVolumeUnits() {
    }

    /**
     * Converts a volume in cubic metres to litres.
     *
     * <p>Callers only ever pass values derived from {@code INT}-typed meter readings, whose widest
     * possible delta ({@code ~2.1e9}) still multiplies well inside {@code long} — so the exact
     * multiplication below cannot actually throw. It is used rather than a plain {@code *} to keep
     * that invariant enforced rather than assumed, should a wider reading type ever be introduced.
     *
     * @param cubicMetres volume in m&sup3;
     * @return the same volume in litres
     * @throws ArithmeticException if the result overflows {@code long}
     */
    public static long cubicMetresToLitres(long cubicMetres) {
        return Math.multiplyExact(cubicMetres, LITRES_PER_CUBIC_METRE);
    }
}
