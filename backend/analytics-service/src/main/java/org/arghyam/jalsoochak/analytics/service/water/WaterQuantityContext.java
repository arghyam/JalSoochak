package org.arghyam.jalsoochak.analytics.service.water;

import lombok.Builder;

import java.time.LocalDate;

/**
 * Inputs available to a {@link WaterQuantityCalculator} when deriving a day's water quantity.
 *
 * <p><strong>The physical meaning of {@link #currentReading()} / {@link #previousReading()} is
 * channel-specific</strong> and is the responsibility of the resolved per-channel calculator —
 * the same numeric field carries a different quantity per channel:
 * <ul>
 *   <li>{@link org.arghyam.jalsoochak.analytics.enums.ReadingChannel#BFM BFM}: the cumulative
 *       bulk-flow-meter index in cubic metres (m&sup3; = KL); the day's quantity is the delta over the
 *       previous reading, converted to litres by the calculator.</li>
 *   <li>Future channels interpret the value differently (e.g. ELM: energy consumed in kWh;
 *       PDU: pump running duration in minutes) and additionally read per-scheme parameters
 *       (efficiency / head / discharge rate) keyed by {@link #tenantId()} / {@link #schemeId()}.</li>
 * </ul>
 * Because interpretation is delegated to the channel's calculator, a reading is only ever
 * processed by the calculator that understands its units; see
 * {@link WaterQuantityCalculatorRegistry#resolve(Integer)}.
 */
@Builder
public record WaterQuantityContext(
        Integer tenantId,
        Integer schemeId,
        LocalDate readingDate,
        Integer currentReading,
        Integer previousReading,
        Integer channel
) {
}
