package org.arghyam.jalsoochak.telemetry.service.location;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("LocationAffinityPolicy")
class LocationAffinityPolicyTest {

    private static final double SCHEME_LAT = 26.1445d;
    private static final double SCHEME_LNG = 91.7362d;
    /** ~1201 m due north of the scheme. */
    private static final double FAR_LAT = 26.1553d;
    /** ~111 m due north of the scheme. */
    private static final double NEAR_LAT = 26.1455d;

    private static LocationAffinityInputs inputs(Double readingLat, Double readingLng,
                                                 Double schemeLat, Double schemeLng,
                                                 Double threshold) {
        return new LocationAffinityInputs(true, readingLat, readingLng, schemeLat, schemeLng, threshold);
    }

    private static LocationAffinityInputs at(double readingLat, double threshold) {
        return inputs(readingLat, SCHEME_LNG, SCHEME_LAT, SCHEME_LNG, threshold);
    }

    @Nested
    @DisplayName("verdicts")
    class Verdicts {

        @Test
        @DisplayName("a submission nearer than the threshold is within")
        void nearerIsWithin() {
            LocationVerdict verdict = LocationAffinityPolicy.evaluate(at(NEAR_LAT, 500.0d));

            assertThat(verdict).isInstanceOf(LocationVerdict.Within.class);
            LocationVerdict.Within within = (LocationVerdict.Within) verdict;
            assertThat(within.distanceMetres()).isCloseTo(111.0d, within(3.0d));
            assertThat(within.thresholdMetres()).isEqualTo(500.0d);
        }

        @Test
        @DisplayName("a submission farther than the threshold is outside")
        void fartherIsOutside() {
            LocationVerdict verdict = LocationAffinityPolicy.evaluate(at(FAR_LAT, 500.0d));

            assertThat(verdict).isInstanceOf(LocationVerdict.Outside.class);
            LocationVerdict.Outside outside = (LocationVerdict.Outside) verdict;
            assertThat(outside.distanceMetres()).isCloseTo(1201.0d, within(12.0d));
            assertThat(outside.thresholdMetres()).isEqualTo(500.0d);
        }

        @Test
        @DisplayName("a submission exactly on the threshold is within, not outside")
        void exactlyOnTheThresholdIsWithin() {
            // The requirement is "greater than affinity metres", so the comparison is strict. Pinned
            // because flipping it to >= would start accusing operators standing on the boundary.
            double exactDistance = GeoDistance.metresBetween(
                    FAR_LAT, SCHEME_LNG, SCHEME_LAT, SCHEME_LNG);

            assertThat(LocationAffinityPolicy.evaluate(at(FAR_LAT, exactDistance)))
                    .isInstanceOf(LocationVerdict.Within.class);
        }
    }

    @Nested
    @DisplayName("skips")
    class Skips {

        private static LocationVerdict.SkipReason reasonFor(LocationAffinityInputs in) {
            LocationVerdict verdict = LocationAffinityPolicy.evaluate(in);
            assertThat(verdict).isInstanceOf(LocationVerdict.Skipped.class);
            return ((LocationVerdict.Skipped) verdict).reason();
        }

        @Test
        @DisplayName("the tenant has not enabled the location check")
        void checkNotRequired() {
            LocationAffinityInputs off = new LocationAffinityInputs(
                    false, FAR_LAT, SCHEME_LNG, SCHEME_LAT, SCHEME_LNG, 100.0d);

            // Checked first, and nothing else is examined: this is the feature's off switch and its
            // counter should not be diluted by tenants who also happen to lack coordinates.
            assertThat(reasonFor(off)).isEqualTo(LocationVerdict.SkipReason.CHECK_NOT_REQUIRED);
        }

        @Test
        @DisplayName("the submission carried no coordinates")
        void noReadingLocation() {
            assertThat(reasonFor(inputs(null, null, SCHEME_LAT, SCHEME_LNG, 100.0d)))
                    .isEqualTo(LocationVerdict.SkipReason.NO_READING_LOCATION);
            assertThat(reasonFor(inputs(FAR_LAT, null, SCHEME_LAT, SCHEME_LNG, 100.0d)))
                    .isEqualTo(LocationVerdict.SkipReason.NO_READING_LOCATION);
        }

        @Test
        @DisplayName("the scheme has no recorded coordinates")
        void noSchemeLocation() {
            assertThat(reasonFor(inputs(FAR_LAT, SCHEME_LNG, null, null, 100.0d)))
                    .isEqualTo(LocationVerdict.SkipReason.NO_SCHEME_LOCATION);
            assertThat(reasonFor(inputs(FAR_LAT, SCHEME_LNG, SCHEME_LAT, null, 100.0d)))
                    .isEqualTo(LocationVerdict.SkipReason.NO_SCHEME_LOCATION);
        }

        @Test
        @DisplayName("scheme coordinates out of range are corrupt data, not a distant submission")
        void outOfRangeSchemeCoordinates() {
            assertThat(reasonFor(inputs(FAR_LAT, SCHEME_LNG, 91.0d, SCHEME_LNG, 100.0d)))
                    .isEqualTo(LocationVerdict.SkipReason.NO_SCHEME_LOCATION);
            assertThat(reasonFor(inputs(FAR_LAT, SCHEME_LNG, SCHEME_LAT, 181.0d, 100.0d)))
                    .isEqualTo(LocationVerdict.SkipReason.NO_SCHEME_LOCATION);
        }

        @Test
        @DisplayName("reading coordinates out of range skip rather than compute a confident lie")
        void outOfRangeReadingCoordinates() {
            assertThat(reasonFor(inputs(-91.0d, SCHEME_LNG, SCHEME_LAT, SCHEME_LNG, 100.0d)))
                    .isEqualTo(LocationVerdict.SkipReason.NO_READING_LOCATION);
        }

        @ParameterizedTest
        @ValueSource(doubles = {0.0d, -1.0d, Double.NaN, Double.POSITIVE_INFINITY})
        @DisplayName("the threshold is absent, non-positive or not a finite number")
        void noThreshold(double threshold) {
            assertThat(reasonFor(at(FAR_LAT, threshold)))
                    .isEqualTo(LocationVerdict.SkipReason.NO_THRESHOLD);
        }

        @Test
        @DisplayName("the threshold did not resolve at all")
        void nullThreshold() {
            assertThat(reasonFor(inputs(FAR_LAT, SCHEME_LNG, SCHEME_LAT, SCHEME_LNG, null)))
                    .isEqualTo(LocationVerdict.SkipReason.NO_THRESHOLD);
        }

        @Test
        @DisplayName("a missing reading location is reported before a missing scheme location")
        void readingLocationIsCheckedFirst() {
            // So NO_SCHEME_LOCATION counts only submissions that would otherwise have been assessed
            // — which is the figure that says how much master data is worth fixing.
            assertThat(reasonFor(inputs(null, null, null, null, 100.0d)))
                    .isEqualTo(LocationVerdict.SkipReason.NO_READING_LOCATION);
        }
    }

    @Nested
    @DisplayName("metric tags")
    class MetricTags {

        @Test
        @DisplayName("every skip reason carries a distinct tag, since each is a dashboard panel")
        void tagsAreDistinct() {
            assertThat(java.util.Arrays.stream(LocationVerdict.SkipReason.values())
                    .map(LocationVerdict.SkipReason::metricTag).toList())
                    .doesNotHaveDuplicates()
                    .allSatisfy(tag -> assertThat(tag).isNotBlank());
        }
    }
}
