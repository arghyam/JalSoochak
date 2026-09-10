package org.arghyam.jalsoochak.telemetry.service.water;

import java.math.BigDecimal;

/**
 * SUPPLY-PLAUSIBILITY: everything {@link ImplausibleSupplyPolicy#evaluate} needs, gathered by the
 * caller before the reading is persisted.
 *
 * <p>A plain value carrier with no repository, config or context handles on it: the decision is
 * taken from numbers already in hand, so it can be reproduced in a unit test and re-derived from a
 * log line without a database.
 *
 * <p>The three connection counts are read straight from {@code scheme_master_table}, where all three
 * are {@code INTEGER NOT NULL DEFAULT 0} — "not recorded" arrives as {@code 0}, never as
 * {@code null}, which is why they are primitives and why the fallback chain tests for zero.
 *
 * @param confirmedReading       the value that will actually be stored — post-rollover-resolution,
 *                               not the model's raw pick
 * @param baselineReading        latest non-quarantined confirmed reading strictly before this one;
 *                               {@code null} when the scheme has no earlier reading
 * @param fhtcCount              functional household tap connections achieved
 * @param plannedFhtc            tap connections the scheme was designed for
 * @param houseHoldCount         households in the service area
 * @param avgMembersPerHousehold persons per connection, from the tenant's
 *                               {@code AVERAGE_MEMBERS_PER_HOUSEHOLD} config or the configured
 *                               default; {@code null} when neither resolved
 * @param limitPerPersonLitres   litres per person per day the scheme is allowed to supply
 */
public record SupplyPlausibilityInputs(
        BigDecimal confirmedReading,
        BigDecimal baselineReading,
        int fhtcCount,
        int plannedFhtc,
        int houseHoldCount,
        BigDecimal avgMembersPerHousehold,
        BigDecimal limitPerPersonLitres
) {
}
