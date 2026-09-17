package org.arghyam.jalsoochak.telemetry.service.water;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ImplausibleSupplyPolicy")
class ImplausibleSupplyPolicyTest {

    private static final BigDecimal LIMIT_150 = new BigDecimal("150");
    private static final BigDecimal FIVE_MEMBERS = new BigDecimal("5");

    /**
     * 100 connections x 5 persons = 500 people; 500 x 150 = 75,000 L/day, which is 75 m&sup3;.
     * The worked example from the plan, reused throughout so the numbers stay recognisable.
     */
    private static SupplyPlausibilityInputs standardScheme(String baseline, String confirmed) {
        return new SupplyPlausibilityInputs(
                new BigDecimal(confirmed), baseline == null ? null : new BigDecimal(baseline),
                100, 0, 0, FIVE_MEMBERS, LIMIT_150);
    }

    @Nested
    @DisplayName("ceiling")
    class Ceiling {

        @Test
        @DisplayName("accepts a day's supply below what the population could consume")
        void acceptsPlausibleSupply() {
            Verdict verdict = ImplausibleSupplyPolicy.evaluate(standardScheme("900", "950"));

            assertThat(verdict).isInstanceOf(Verdict.Accepted.class);
            Verdict.Accepted accepted = (Verdict.Accepted) verdict;
            assertThat(accepted.litres()).isEqualByComparingTo("50000");
            assertThat(accepted.ceiling()).isEqualByComparingTo("75000");
            assertThat(accepted.population()).isEqualByComparingTo("500");
        }

        @Test
        @DisplayName("quarantines a day's supply beyond what the population could consume")
        void quarantinesImplausibleSupply() {
            Verdict verdict = ImplausibleSupplyPolicy.evaluate(standardScheme("900", "1100"));

            assertThat(verdict).isInstanceOf(Verdict.Quarantined.class);
            Verdict.Quarantined quarantined = (Verdict.Quarantined) verdict;
            assertThat(quarantined.litres()).isEqualByComparingTo("200000");
            assertThat(quarantined.ceiling()).isEqualByComparingTo("75000");
            assertThat(quarantined.population()).isEqualByComparingTo("500");
        }

        @Test
        @DisplayName("accepts a reading landing exactly on the ceiling")
        void acceptsExactlyOnTheCeiling() {
            // 75,000 L = 75 m3 above the baseline.
            Verdict verdict = ImplausibleSupplyPolicy.evaluate(standardScheme("900", "975"));

            assertThat(verdict).isInstanceOf(Verdict.Accepted.class);
            assertThat(((Verdict.Accepted) verdict).litres()).isEqualByComparingTo("75000");
        }

        @Test
        @DisplayName("quarantines one litre past the ceiling")
        void quarantinesOneLitrePastTheCeiling() {
            // 0.001 m3 = 1 L, the finest step the conversion resolves.
            Verdict verdict = ImplausibleSupplyPolicy.evaluate(standardScheme("900", "975.001"));

            assertThat(verdict).isInstanceOf(Verdict.Quarantined.class);
            assertThat(((Verdict.Quarantined) verdict).litres()).isEqualByComparingTo("75001");
        }

        @Test
        @DisplayName("scales with a fractional household size")
        void scalesWithFractionalHouseholdSize() {
            // 100 x 4.5 = 450 people; 450 x 150 = 67,500 L. The 50 m3 accepted above still passes,
            // but a 70 m3 day that a 5.0 household size would have allowed no longer does.
            SupplyPlausibilityInputs inputs = new SupplyPlausibilityInputs(
                    new BigDecimal("970"), new BigDecimal("900"),
                    100, 0, 0, new BigDecimal("4.5"), LIMIT_150);

            Verdict verdict = ImplausibleSupplyPolicy.evaluate(inputs);

            assertThat(verdict).isInstanceOf(Verdict.Quarantined.class);
            assertThat(((Verdict.Quarantined) verdict).ceiling()).isEqualByComparingTo("67500");
            assertThat(((Verdict.Quarantined) verdict).population()).isEqualByComparingTo("450");
        }

        @Test
        @DisplayName("honours a limit other than the 150 L default")
        void honoursAConfiguredLimit() {
            SupplyPlausibilityInputs inputs = new SupplyPlausibilityInputs(
                    new BigDecimal("950"), new BigDecimal("900"),
                    100, 0, 0, FIVE_MEMBERS, new BigDecimal("55"));

            Verdict verdict = ImplausibleSupplyPolicy.evaluate(inputs);

            // 500 people x 55 LPCD = 27,500 L, well under the 50,000 L submitted.
            assertThat(verdict).isInstanceOf(Verdict.Quarantined.class);
            assertThat(((Verdict.Quarantined) verdict).ceiling()).isEqualByComparingTo("27500");
        }
    }

    @Nested
    @DisplayName("connection-count fallback")
    class ConnectionFallback {

        @Test
        @DisplayName("prefers achieved connections over planned and households")
        void prefersAchieved() {
            assertThat(ImplausibleSupplyPolicy.resolveConnections(100, 400, 900)).isEqualTo(100);
        }

        @Test
        @DisplayName("falls back to planned connections when none are achieved")
        void fallsBackToPlanned() {
            assertThat(ImplausibleSupplyPolicy.resolveConnections(0, 400, 900)).isEqualTo(400);
        }

        @Test
        @DisplayName("falls back to households when neither connection count is recorded")
        void fallsBackToHouseholds() {
            assertThat(ImplausibleSupplyPolicy.resolveConnections(0, 0, 900)).isEqualTo(900);
        }

        @Test
        @DisplayName("reports no connections when the scheme records none")
        void reportsNoConnections() {
            assertThat(ImplausibleSupplyPolicy.resolveConnections(0, 0, 0)).isZero();
        }

        @Test
        @DisplayName("treats a negative count as absent rather than shrinking the ceiling")
        void treatsNegativeAsAbsent() {
            // The columns are NOT NULL DEFAULT 0, so this cannot arrive from the database - but a
            // negative population would invert the check into rejecting every reading, so it is
            // clamped rather than trusted.
            assertThat(ImplausibleSupplyPolicy.resolveConnections(-5, 0, 0)).isZero();
            assertThat(ImplausibleSupplyPolicy.resolveConnections(0, 0, -5)).isZero();
        }

        @Test
        @DisplayName("the fallback chain drives the ceiling, not just the count")
        void fallbackDrivesTheCeiling() {
            // No achieved connections; 400 planned x 5 = 2,000 people, ceiling 300,000 L. The 200
            // m3 day that the 100-connection scheme quarantined is plausible for this one.
            SupplyPlausibilityInputs inputs = new SupplyPlausibilityInputs(
                    new BigDecimal("1100"), new BigDecimal("900"),
                    0, 400, 900, FIVE_MEMBERS, LIMIT_150);

            Verdict verdict = ImplausibleSupplyPolicy.evaluate(inputs);

            assertThat(verdict).isInstanceOf(Verdict.Accepted.class);
            assertThat(((Verdict.Accepted) verdict).ceiling()).isEqualByComparingTo("300000");
        }
    }

    @Nested
    @DisplayName("population")
    class Population {

        @Test
        @DisplayName("multiplies connections by household size without rounding")
        void multipliesWithoutRounding() {
            Optional<BigDecimal> population =
                    ImplausibleSupplyPolicy.resolvePopulation(37, 0, 0, new BigDecimal("4.5"));

            assertThat(population).hasValueSatisfying(
                    value -> assertThat(value).isEqualByComparingTo("166.5"));
        }

        @Test
        @DisplayName("is absent when the scheme records no connections")
        void absentWithoutConnections() {
            assertThat(ImplausibleSupplyPolicy.resolvePopulation(0, 0, 0, FIVE_MEMBERS)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-1"})
        @DisplayName("is absent when household size is missing or non-positive")
        void absentWithoutHouseholdSize(String avgMembers) {
            assertThat(ImplausibleSupplyPolicy.resolvePopulation(100, 0, 0, new BigDecimal(avgMembers)))
                    .isEmpty();
            assertThat(ImplausibleSupplyPolicy.resolvePopulation(100, 0, 0, null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("skip conditions")
    class SkipConditions {

        @Test
        @DisplayName("skips when the scheme has no earlier reading to subtract")
        void skipsWithoutBaseline() {
            assertThat(ImplausibleSupplyPolicy.evaluate(standardScheme(null, "950")))
                    .isEqualTo(new Verdict.Skipped(Verdict.SkipReason.NO_BASELINE));
        }

        @Test
        @DisplayName("skips when the meter did not advance")
        void skipsOnZeroDelta() {
            assertThat(ImplausibleSupplyPolicy.evaluate(standardScheme("900", "900")))
                    .isEqualTo(new Verdict.Skipped(Verdict.SkipReason.NON_POSITIVE_DELTA));
        }

        @Test
        @DisplayName("skips when the meter went backwards")
        void skipsOnNegativeDelta() {
            assertThat(ImplausibleSupplyPolicy.evaluate(standardScheme("900", "850")))
                    .isEqualTo(new Verdict.Skipped(Verdict.SkipReason.NON_POSITIVE_DELTA));
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "-150"})
        @DisplayName("skips when the per-person limit is non-positive")
        void skipsOnNonPositiveLimit(String limit) {
            SupplyPlausibilityInputs inputs = new SupplyPlausibilityInputs(
                    new BigDecimal("1100"), new BigDecimal("900"),
                    100, 0, 0, FIVE_MEMBERS, new BigDecimal(limit));

            assertThat(ImplausibleSupplyPolicy.evaluate(inputs))
                    .isEqualTo(new Verdict.Skipped(Verdict.SkipReason.NO_LIMIT));
        }

        @Test
        @DisplayName("skips when no per-person limit resolved")
        void skipsOnAbsentLimit() {
            SupplyPlausibilityInputs inputs = new SupplyPlausibilityInputs(
                    new BigDecimal("1100"), new BigDecimal("900"), 100, 0, 0, FIVE_MEMBERS, null);

            assertThat(ImplausibleSupplyPolicy.evaluate(inputs))
                    .isEqualTo(new Verdict.Skipped(Verdict.SkipReason.NO_LIMIT));
        }

        @Test
        @DisplayName("skips when neither the tenant config nor a default gave a household size")
        void skipsOnAbsentHouseholdSize() {
            SupplyPlausibilityInputs inputs = new SupplyPlausibilityInputs(
                    new BigDecimal("1100"), new BigDecimal("900"), 100, 0, 0, null, LIMIT_150);

            assertThat(ImplausibleSupplyPolicy.evaluate(inputs))
                    .isEqualTo(new Verdict.Skipped(Verdict.SkipReason.NO_MEMBERS_PER_HOUSEHOLD));
        }

        @Test
        @DisplayName("skips when all three connection counts are zero")
        void skipsWithoutPopulation() {
            SupplyPlausibilityInputs inputs = new SupplyPlausibilityInputs(
                    new BigDecimal("1100"), new BigDecimal("900"), 0, 0, 0, FIVE_MEMBERS, LIMIT_150);

            assertThat(ImplausibleSupplyPolicy.evaluate(inputs))
                    .isEqualTo(new Verdict.Skipped(Verdict.SkipReason.NO_POPULATION));
        }

        @Test
        @DisplayName("reports the missing master data last, so its counter measures only readings "
                + "that would otherwise have been checked")
        void noPopulationIsEvaluatedLast() {
            // Missing counts AND a non-positive delta: the reading was a non-event anyway, so
            // counting it as a master-data gap would inflate the figure the rollout gates on.
            SupplyPlausibilityInputs inputs = new SupplyPlausibilityInputs(
                    new BigDecimal("900"), new BigDecimal("900"), 0, 0, 0, FIVE_MEMBERS, LIMIT_150);

            assertThat(ImplausibleSupplyPolicy.evaluate(inputs))
                    .isEqualTo(new Verdict.Skipped(Verdict.SkipReason.NON_POSITIVE_DELTA));
        }

        @Test
        @DisplayName("every skip reason carries a distinct metric tag")
        void skipReasonsHaveDistinctTags() {
            assertThat(Verdict.SkipReason.values())
                    .extracting(Verdict.SkipReason::metricTag)
                    .doesNotContainNull()
                    .doesNotHaveDuplicates()
                    .allSatisfy(tag -> assertThat(tag).matches("[a-z_]+"));
        }

        @Test
        @DisplayName("a missing reading is a caller bug, not a skip")
        void missingReadingThrows() {
            SupplyPlausibilityInputs inputs = new SupplyPlausibilityInputs(
                    null, new BigDecimal("900"), 100, 0, 0, FIVE_MEMBERS, LIMIT_150);

            assertThatThrownBy(() -> ImplausibleSupplyPolicy.evaluate(inputs))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("confirmedReading");
        }
    }

    @Nested
    @DisplayName("litre conversion")
    class LitreConversion {

        @Test
        @DisplayName("clamps a backwards meter to zero rather than a negative volume")
        void clampsAtZero() {
            assertThat(ImplausibleSupplyPolicy.deltaLitres(new BigDecimal("850"), new BigDecimal("900")))
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        @DisplayName("rounds a sub-litre remainder half up, matching the warehouse")
        void roundsHalfUp() {
            // 0.0035 m3 = 3.5 L.
            assertThat(ImplausibleSupplyPolicy.deltaLitres(
                    new BigDecimal("900.0035"), new BigDecimal("900")))
                    .isEqualByComparingTo("4");
        }

        @Test
        @DisplayName("compares a delta too large for a BIGINT column instead of throwing")
        void handlesVolumesPastLongRange() {
            // The analytics conversion narrows to long and raises WaterVolumeOutOfRangeException
            // here. Nothing is stored on this path - the value is only compared - so a garbage
            // reading this large must be quarantined, not turned into a 500 on the submission API.
            SupplyPlausibilityInputs inputs = new SupplyPlausibilityInputs(
                    new BigDecimal("999999999999999999999"), BigDecimal.ZERO,
                    100, 0, 0, FIVE_MEMBERS, LIMIT_150);

            assertThat(ImplausibleSupplyPolicy.evaluate(inputs)).isInstanceOf(Verdict.Quarantined.class);
        }

        /**
         * PARITY GUARD. {@code WaterVolumeUnits.cubicMetresToLitres} in analytics-service is the
         * definition of this conversion; the policy mirrors it because that class is not on
         * telemetry's classpath and a cross-service dependency for two lines is not worth it.
         *
         * <p>{@link #referenceCubicMetresToLitres} below is a verbatim transcription of it. If the
         * two ever disagree the litres this check rejects on and the litres the warehouse would have
         * stored have drifted apart, and the anomaly record stops being reconstructible from
         * {@code (overriddenReading - previousReading) * 1000}.
         */
        @ParameterizedTest(name = "{0} m3 -> {1} m3")
        @CsvSource({
                "900,       950",
                "900,       900.1",
                "900,       900.05",
                "900,       900.0005",
                "900,       900.0004",
                "900,       900.0015",
                "0,         0.001",
                "1234.5,    5678.9",
                "0,         9999999.999"
        })
        @DisplayName("matches WaterVolumeUnits.cubicMetresToLitres exactly")
        void matchesWarehouseConversion(String baseline, String confirmed) {
            BigDecimal from = new BigDecimal(baseline);
            BigDecimal to = new BigDecimal(confirmed);

            BigDecimal policyLitres = ImplausibleSupplyPolicy.deltaLitres(to, from);

            assertThat(policyLitres.longValueExact())
                    .isEqualTo(referenceCubicMetresToLitres(to.subtract(from)));
        }

        /** Verbatim from {@code analytics-service}'s {@code WaterVolumeUnits.cubicMetresToLitres}. */
        private static long referenceCubicMetresToLitres(BigDecimal cubicMetres) {
            return cubicMetres.multiply(BigDecimal.valueOf(1000L))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        }
    }
}
