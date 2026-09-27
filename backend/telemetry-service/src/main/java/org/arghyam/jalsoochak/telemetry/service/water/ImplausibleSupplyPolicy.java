package org.arghyam.jalsoochak.telemetry.service.water;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.Optional;

/**
 * SUPPLY-PLAUSIBILITY: decides whether the daily volume implied by a meter reading is physically
 * possible for the scheme that produced it.
 *
 * <pre>
 * litres     = max(0, confirmedReading − baseline) × 1000
 * population = firstNonZero(fhtcCount, plannedFhtc, houseHoldCount) × avgMembersPerHousehold
 * ceiling    = population × limitPerPersonLitres
 * quarantine iff litres &gt; ceiling
 * </pre>
 *
 * <p>Pure arithmetic over {@link SupplyPlausibilityInputs}: no Spring, no repository, no clock. The
 * decision is therefore reproducible from the numbers alone, which is what lets an operator argue
 * with it — and what lets {@code ImplausibleSupplyPolicyTest} cover every branch without a context.
 *
 * <p><strong>The rollout mode is deliberately not an input.</strong> {@code AUDIT} has to report what
 * it <em>would</em> have quarantined, so it must run the full evaluation and act differently on the
 * result; folding the mode in here would have to return {@link Verdict.Skipped} and destroy the
 * numbers the audit exists to collect. {@code OFF} short-circuits at the caller, before this class is
 * reached.
 */
public final class ImplausibleSupplyPolicy {

    /**
     * MIRROR of {@code analytics-service}'s {@code WaterVolumeUnits.LITRES_PER_CUBIC_METRE}. Bulk
     * flow meters read in cubic metres; the warehouse stores litres.
     */
    private static final BigDecimal LITRES_PER_CUBIC_METRE = BigDecimal.valueOf(1000L);

    private ImplausibleSupplyPolicy() {
    }

    /**
     * Assesses one reading.
     *
     * <p>Skip conditions are ordered cheapest-and-most-specific first so that each counter measures
     * something distinct. In particular {@link Verdict.SkipReason#NO_POPULATION} is evaluated last,
     * so it counts only readings that would otherwise have been assessed — the figure the rollout
     * runbook asks for before switching to {@code ENFORCE}.
     *
     * @param inputs the gathered reading, baseline, scheme counts and limits
     * @return {@link Verdict.Skipped}, {@link Verdict.Accepted} or {@link Verdict.Quarantined}
     */
    public static Verdict evaluate(SupplyPlausibilityInputs inputs) {
        Objects.requireNonNull(inputs, "inputs");
        // A missing reading is a caller bug rather than a data condition: there is no reading to let
        // through, so failing loudly beats a Skipped that would look like a normal outcome.
        BigDecimal confirmedReading = Objects.requireNonNull(
                inputs.confirmedReading(), "confirmedReading");

        BigDecimal baseline = inputs.baselineReading();
        if (baseline == null) {
            return new Verdict.Skipped(Verdict.SkipReason.NO_BASELINE);
        }
        if (confirmedReading.compareTo(baseline) <= 0) {
            return new Verdict.Skipped(Verdict.SkipReason.NON_POSITIVE_DELTA);
        }

        BigDecimal limitPerPerson = inputs.limitPerPersonLitres();
        if (limitPerPerson == null || limitPerPerson.signum() <= 0) {
            return new Verdict.Skipped(Verdict.SkipReason.NO_LIMIT);
        }
        BigDecimal avgMembers = inputs.avgMembersPerHousehold();
        if (avgMembers == null || avgMembers.signum() <= 0) {
            return new Verdict.Skipped(Verdict.SkipReason.NO_MEMBERS_PER_HOUSEHOLD);
        }

        Optional<BigDecimal> populationOpt = resolvePopulation(
                inputs.fhtcCount(), inputs.plannedFhtc(), inputs.houseHoldCount(), avgMembers);
        if (populationOpt.isEmpty()) {
            return new Verdict.Skipped(Verdict.SkipReason.NO_POPULATION);
        }

        BigDecimal population = populationOpt.get();
        BigDecimal litres = deltaLitres(confirmedReading, baseline);
        BigDecimal ceiling = population.multiply(limitPerPerson);

        // Strictly greater: a reading landing exactly on the ceiling is plausible, not implausible.
        return litres.compareTo(ceiling) > 0
                ? new Verdict.Quarantined(litres, ceiling, population)
                : new Verdict.Accepted(litres, ceiling, population);
    }

    /**
     * Converts the rise in a cumulative m&sup3; index into whole litres, clamped at zero.
     *
     * <p><strong>MIRROR of {@code WaterVolumeUnits.cubicMetresToLitres(BigDecimal)} in
     * analytics-service</strong> — that class is the definition of this conversion and is not on
     * telemetry's classpath. The arithmetic is copied verbatim ({@code multiply(1000)} then
     * {@code setScale(0, HALF_UP)}) so the litres computed here and the litres the warehouse would
     * have stored for the same reading are the same number. Adding a cross-service dependency for
     * two lines is not worth it; {@code ImplausibleSupplyPolicyTest} asserts the parity instead, and
     * that test is what stops the two drifting.
     *
     * <p>The one deliberate difference: the analytics version narrows to {@code long} because its
     * destination is a {@code BIGINT} column, and throws when the value does not fit. This one stays
     * a {@link BigDecimal}. Nothing here is stored — the value is only compared against a ceiling —
     * so a garbage reading large enough to overflow {@code long} should be quarantined, which is
     * what comparing exactly does, rather than raising an exception on the submission path.
     *
     * <p>Clamped at zero for the same reason {@code BfmWaterQuantityCalculator} clamps: a meter that
     * went backwards was replaced or misread, and a negative volume is not a supply.
     *
     * @param confirmedReading the current cumulative index in m&sup3;
     * @param baselineReading  the preceding cumulative index in m&sup3;
     * @return whole litres supplied since the baseline, never negative
     */
    public static BigDecimal deltaLitres(BigDecimal confirmedReading, BigDecimal baselineReading) {
        Objects.requireNonNull(confirmedReading, "confirmedReading");
        Objects.requireNonNull(baselineReading, "baselineReading");

        BigDecimal delta = confirmedReading.subtract(baselineReading);
        if (delta.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return delta.multiply(LITRES_PER_CUBIC_METRE).setScale(0, RoundingMode.HALF_UP);
    }

    /**
     * Picks the first connection count a scheme actually reports.
     *
     * <p>Achieved connections describe the scheme as it is today, planned connections what it was
     * built for, and households the settlement it sits in — decreasing accuracy, increasing
     * availability. Falling through to a larger count only ever raises the ceiling, so the fallback
     * can make the check lenient but never spuriously strict.
     *
     * @return the first non-zero count, or {@code 0} when the scheme reports none
     */
    public static int resolveConnections(int fhtcCount, int plannedFhtc, int houseHoldCount) {
        if (fhtcCount > 0) {
            return fhtcCount;
        }
        if (plannedFhtc > 0) {
            return plannedFhtc;
        }
        return Math.max(houseHoldCount, 0);
    }

    /**
     * The persons a scheme serves: its connection count times the tenant's average household size.
     *
     * <p>Kept fractional — {@code AVERAGE_MEMBERS_PER_HOUSEHOLD} is commonly a decimal such as 4.5,
     * and rounding persons before multiplying by the litre limit would move the ceiling by hundreds
     * of litres.
     *
     * <p>Public and separately tested rather than folded into {@link #evaluate}: it is the one input
     * an operator disputing a rejection will challenge, so it has to be checkable on its own. The
     * {@code OVER_WATER_SUPPLY} check on the WhatsApp path wants the same number — that correction is
     * deferred, and when it lands it should call this rather than recompute, so the two checks cannot
     * disagree about how many people a scheme serves.
     *
     * @return the served population, or empty when no count is recorded or the household size is
     *         missing or non-positive
     */
    public static Optional<BigDecimal> resolvePopulation(int fhtcCount,
                                                         int plannedFhtc,
                                                         int houseHoldCount,
                                                         BigDecimal avgMembersPerHousehold) {
        if (avgMembersPerHousehold == null || avgMembersPerHousehold.signum() <= 0) {
            return Optional.empty();
        }
        int connections = resolveConnections(fhtcCount, plannedFhtc, houseHoldCount);
        if (connections <= 0) {
            return Optional.empty();
        }
        return Optional.of(BigDecimal.valueOf(connections).multiply(avgMembersPerHousehold));
    }
}
