package org.arghyam.jalsoochak.telemetry.service.water;

import java.math.BigDecimal;

/**
 * SUPPLY-PLAUSIBILITY: the outcome of {@link ImplausibleSupplyPolicy#evaluate}.
 *
 * <p>Sealed so the wiring in {@code BfmReadingService} must handle every arm: a fourth outcome added
 * later will not compile until the reading path has decided what to do with it, which is the point —
 * a silently unhandled verdict would mean a reading either quarantined or published by accident.
 *
 * <p>{@link Accepted} and {@link Quarantined} carry the same three numbers so an {@code AUDIT}-mode
 * caller can report what it <em>would</em> have done with the identical figures it would have
 * recorded under {@code ENFORCE}. They are for the anomaly record, the Kafka event and the server
 * log — <strong>never for the API response</strong>: echoing the ceiling back would let a caller
 * solve for the scheme's connection count and the per-person limit in two submissions
 * (THRESHOLD-DISCLOSURE, as documented at {@code GlificMeterWorkflowService}).
 */
public sealed interface Verdict permits Verdict.Skipped, Verdict.Accepted, Verdict.Quarantined {

    /**
     * Why a reading could not be assessed. The tag is a metric label value, so it is part of the
     * dashboard contract: {@code implausible_supply.skipped{reason="no_population"}} is named in the
     * rollout runbook. Rename a tag and the panel goes blank.
     *
     * <p>Only conditions derivable from {@link SupplyPlausibilityInputs} appear here. The caller's
     * own short circuits — mode {@code OFF}, a pre-V40 tenant schema, a replaced meter, a non-BFM
     * channel — never reach the policy and so are not enumerated.
     */
    enum SkipReason {

        /** Nothing to subtract: a cumulative index with no predecessor implies no volume. */
        NO_BASELINE("no_baseline"),

        /** The meter did not advance. There is no supply to call implausible. */
        NON_POSITIVE_DELTA("non_positive_delta"),

        /** {@code limit-per-person-litres} is absent or non-positive, so there is no ceiling. */
        NO_LIMIT("no_limit"),

        /** {@code AVERAGE_MEMBERS_PER_HOUSEHOLD} was unparseable and no default was configured. */
        NO_MEMBERS_PER_HOUSEHOLD("no_members_per_household"),

        /**
         * All three connection counts are zero, so the scheme has no derivable population. This is a
         * master-data gap rather than a property of the reading, and is deliberately evaluated
         * <em>last</em>: the counter then measures readings that would have been checked but for the
         * missing counts, which is the number the rollout needs before switching to {@code ENFORCE}.
         */
        NO_POPULATION("no_population");

        private final String metricTag;

        SkipReason(String metricTag) {
            this.metricTag = metricTag;
        }

        public String metricTag() {
            return metricTag;
        }
    }

    /** The check did not run. The reading proceeds exactly as it would have before this feature. */
    record Skipped(SkipReason reason) implements Verdict {
    }

    /**
     * The implied daily supply is at or below the ceiling.
     *
     * @param litres     whole litres implied by this reading
     * @param ceiling    litres this scheme's population could consume in a day
     * @param population persons served, derived from the connection counts
     */
    record Accepted(BigDecimal litres, BigDecimal ceiling, BigDecimal population) implements Verdict {
    }

    /**
     * The implied daily supply exceeds the ceiling. Strictly greater: a reading landing exactly on
     * the ceiling is accepted.
     *
     * @param litres     whole litres implied by this reading
     * @param ceiling    litres this scheme's population could consume in a day
     * @param population persons served, derived from the connection counts
     */
    record Quarantined(BigDecimal litres, BigDecimal ceiling, BigDecimal population) implements Verdict {
    }
}
