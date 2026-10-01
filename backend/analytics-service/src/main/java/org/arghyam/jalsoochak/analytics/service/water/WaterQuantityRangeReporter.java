package org.arghyam.jalsoochak.analytics.service.water;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Reports daily volumes that are out of range, for both writers of
 * {@code fact_water_quantity_table}: a reading's recalculation and the reason/correction event.
 *
 * <p>Neither case is ever clamped: clamping would fabricate a plausible number and hide the bad
 * reading behind it. The log line and the counter are what make it findable.
 */
@Component
@Slf4j
public class WaterQuantityRangeReporter {

    private final MeterRegistry meterRegistry;
    private final long implausibleDailyLitres;

    /**
     * @param implausibleDailyCubicMetres daily volume (in m&sup3;) above which a derived quantity is
     *        reported as implausible. The default of 100,000 m&sup3;/day is 100 MLD — an order of
     *        magnitude beyond any single rural scheme, so it only fires on genuine garbage such as an
     *        OCR misread or a replaced meter.
     * @throws IllegalStateException for a negative threshold, or one whose litre value overflows
     *        {@code long}. Caught here at startup rather than on the Kafka consumer thread, where an
     *        uncaught {@code ArithmeticException} would fail the offset commit and retry forever.
     */
    public WaterQuantityRangeReporter(
            MeterRegistry meterRegistry,
            @Value("${analytics.water-quantity.implausible-daily-cubic-metres:100000}") long implausibleDailyCubicMetres) {
        if (implausibleDailyCubicMetres < 0
                || implausibleDailyCubicMetres > Long.MAX_VALUE / WaterVolumeUnits.LITRES_PER_CUBIC_METRE) {
            throw new IllegalStateException(
                    "analytics.water-quantity.implausible-daily-cubic-metres must be between 0 and "
                            + (Long.MAX_VALUE / WaterVolumeUnits.LITRES_PER_CUBIC_METRE)
                            + ", got " + implausibleDailyCubicMetres);
        }
        this.meterRegistry = meterRegistry;
        this.implausibleDailyLitres = WaterVolumeUnits.cubicMetresToLitres(implausibleDailyCubicMetres);
    }

    /**
     * Reports a derived daily volume that is too large to be real. The value is still stored as
     * derived — the column is BIGINT and holds it fine.
     *
     * @param litres the derived quantity, in litres
     * @param source which write path produced it ({@code reading} or {@code correction})
     */
    public void reportIfImplausible(long litres, Integer tenantId, Integer schemeId, LocalDate date, String source) {
        if (litres <= implausibleDailyLitres) {
            return;
        }
        log.warn("Implausible daily water quantity {} L (> {} L) stored as-is from {} path "
                        + "(tenantId={}, schemeId={}, date={}); check the underlying meter reading",
                litres, implausibleDailyLitres, source, tenantId, schemeId, date);
        meterRegistry.counter("water_quantity.implausible", "source", source)
                .increment();
    }

    /**
     * Reports a derived volume that cannot be stored at all — its litre value is past the column's
     * {@code BIGINT} range, which takes a reading around {@code 9.2e15} m&sup3;.
     *
     * <p>Distinct from {@link #reportIfImplausible} in outcome, not in kind: that one has a number it
     * can still store and keeps it, while here there is nothing storable to keep, so the day is left
     * as it was. The separate counter exists because these two want different alerts: "a suspicious
     * value went in" versus "a submission was too broken to derive".
     *
     * @param source which write path produced it ({@code reading} or {@code correction})
     */
    public void reportUnstorable(WaterVolumeOutOfRangeException e,
                                 Integer tenantId, Integer schemeId, LocalDate date, String source) {
        log.warn("Water quantity {} {} from the {} path exceeds the storable range "
                        + "(tenantId={}, schemeId={}, date={}); no volume recorded for the day. "
                        + "The meter reading is almost certainly wrong — check it",
                e.getValue(), e.getUnit(), source, tenantId, schemeId, date);
        meterRegistry.counter("water_quantity.unstorable", "source", source)
                .increment();
    }
}
