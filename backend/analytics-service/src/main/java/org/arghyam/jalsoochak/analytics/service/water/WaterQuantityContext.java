package org.arghyam.jalsoochak.analytics.service.water;

import lombok.Builder;
import org.arghyam.jalsoochak.analytics.dto.event.CalculationParameters;
import org.arghyam.jalsoochak.analytics.enums.ReadingChannel;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Inputs to a {@link WaterQuantityCalculator}: an amount of the channel's own quantity, and what is
 * needed to turn it into litres.
 *
 * <p>The amount has already been worked out by the channel's {@link ReadingChannel#kind() kind}, in
 * the channel's standard unit, so a calculator never sees raw readings:
 * <ul>
 *   <li>{@link org.arghyam.jalsoochak.analytics.enums.ReadingKind#METER_INDEX METER_INDEX}: the day's
 *       increase over the latest reading before it, never negative, and 0 when there is no earlier
 *       reading (BFM: m&sup3;, ELM: kWh).</li>
 *   <li>{@link org.arghyam.jalsoochak.analytics.enums.ReadingKind#PERIOD_AMOUNT PERIOD_AMOUNT}: one
 *       submission's amount (PDU: minutes). The day's litres are the sum over its submissions.</li>
 * </ul>
 *
 * <p>The amount is {@code BigDecimal} because it is decimal at the source: the meters carry a decimal
 * digit, and kWh and minutes are not whole counts either.
 *
 * @param channel    the channel whose calculator this is for
 * @param amount     the amount in the channel's standard unit, never null
 * @param parameters the snapshot of the reading the amount belongs to (for {@code METER_INDEX}, the
 *                   day's latest reading); null for BFM
 */
@Builder
public record WaterQuantityContext(
        Integer tenantId,
        Integer schemeId,
        LocalDate readingDate,
        ReadingChannel channel,
        BigDecimal amount,
        CalculationParameters parameters
) {
}
