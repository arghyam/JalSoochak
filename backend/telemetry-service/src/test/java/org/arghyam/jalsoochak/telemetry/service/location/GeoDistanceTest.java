package org.arghyam.jalsoochak.telemetry.service.location;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("GeoDistance")
class GeoDistanceTest {

    // Two points on Guwahati's GS Road, ~1.20 km apart by great circle.
    private static final double GUWAHATI_A_LAT = 26.1445d;
    private static final double GUWAHATI_A_LNG = 91.7362d;
    private static final double GUWAHATI_B_LAT = 26.1553d;
    private static final double GUWAHATI_B_LNG = 91.7362d;

    @Test
    @DisplayName("matches a known distance to within one percent")
    void matchesAKnownDistance() {
        // A degree of latitude is ~111.19 km on the IUGG mean sphere, so 0.0108 degrees is ~1201 m.
        double metres = GeoDistance.metresBetween(
                GUWAHATI_A_LAT, GUWAHATI_A_LNG, GUWAHATI_B_LAT, GUWAHATI_B_LNG);

        assertThat(metres).isCloseTo(1201.0d, within(12.0d));
    }

    @Test
    @DisplayName("is zero for a point against itself")
    void isZeroForIdenticalPoints() {
        assertThat(GeoDistance.metresBetween(
                GUWAHATI_A_LAT, GUWAHATI_A_LNG, GUWAHATI_A_LAT, GUWAHATI_A_LNG))
                .isZero();
    }

    @Test
    @DisplayName("is symmetric, so which point is the scheme cannot change the verdict")
    void isSymmetric() {
        double forward = GeoDistance.metresBetween(
                GUWAHATI_A_LAT, GUWAHATI_A_LNG, GUWAHATI_B_LAT, GUWAHATI_B_LNG);
        double backward = GeoDistance.metresBetween(
                GUWAHATI_B_LAT, GUWAHATI_B_LNG, GUWAHATI_A_LAT, GUWAHATI_A_LNG);

        assertThat(forward).isEqualTo(backward);
    }

    @Test
    @DisplayName("antipodal points give a finite distance, not NaN")
    void antipodalPointsAreFinite() {
        // THE regression guard for the min(1.0, sqrt(a)) clamp. Without it `a` rounds above 1 here,
        // Math.asin returns NaN, and `distance > threshold` is then false for EVERY submission —
        // the check would silently stop reporting rather than fail visibly.
        double metres = GeoDistance.metresBetween(26.1445d, 91.7362d, -26.1445d, -88.2638d);

        assertThat(metres).isNotNaN().isFinite();
        // Half the circumference of the mean sphere.
        assertThat(metres).isCloseTo(20_015_115.0d, within(1_000.0d));
    }

    @Test
    @DisplayName("a span crossing the antimeridian equals the same span that does not")
    void antimeridianNeedsNoSpecialCase() {
        // Pinned so nobody "fixes" the wrap later: sin(dLon / 2) is periodic and already handles it.
        double crossing = GeoDistance.metresBetween(20.0d, 170.0d, 20.0d, -160.0d);
        double notCrossing = GeoDistance.metresBetween(20.0d, 10.0d, 20.0d, 40.0d);

        assertThat(crossing).isCloseTo(notCrossing, within(0.001d));
    }

    @Test
    @DisplayName("is never negative")
    void isNeverNegative() {
        assertThat(GeoDistance.metresBetween(-33.8688d, 151.2093d, 51.5074d, -0.1278d))
                .isPositive();
    }
}
