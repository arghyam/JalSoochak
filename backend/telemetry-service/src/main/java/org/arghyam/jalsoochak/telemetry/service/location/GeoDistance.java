package org.arghyam.jalsoochak.telemetry.service.location;

/**
 * LOCATION-AFFINITY: great-circle distance between two WGS-84 points, in metres.
 *
 * <p>Haversine on a sphere. Worst case it disagrees with a full ellipsoidal (Vincenty) solution by
 * about 0.5%; at the hundreds-of-metres thresholds this is configured with, that is centimetres, and
 * the decision it feeds is a single {@code >} comparison. Buying more precision would only make the
 * class harder to reason about.
 *
 * <p>{@code double} throughout on purpose: both coordinate columns are {@code DOUBLE PRECISION}, and
 * the formula is transcendental, so {@code BigDecimal} would carry false precision through
 * {@code sin}/{@code cos} anyway.
 */
public final class GeoDistance {

    /**
     * IUGG mean Earth radius, metres. The sphere the haversine below is computed on.
     */
    private static final double EARTH_RADIUS_METRES = 6_371_008.8d;

    private GeoDistance() {
    }

    /**
     * Distance along the Earth's surface between two points.
     *
     * <p><strong>The {@code min(1.0, …)} clamp is load-bearing, not defensive noise.</strong> For
     * near-antipodal points {@code a} can round just above 1, and {@code Math.asin} of anything
     * greater than 1 is {@code NaN}. A {@code NaN} distance makes {@code distance > threshold}
     * evaluate {@code false}, so the failure would not be a wrong distance — it would be every
     * mismatch silently disappearing. {@code asin} with the clamp is used in preference to the
     * {@code atan2} formulation for exactly that reason: the degenerate case is capped rather than
     * propagated.
     *
     * <p>No antimeridian or polar special-casing is needed, and none should be added.
     * {@code sin(dLon / 2)} is periodic, so a span crossing 180° gives the same answer as the
     * equivalent span that does not; {@code GeoDistanceTest} pins this so the "fix" cannot be
     * introduced later. (Every scheme is in India in any case.)
     *
     * @return metres, always finite and non-negative
     */
    public static double metresBetween(double latitude1, double longitude1,
                                       double latitude2, double longitude2) {
        double deltaLatitude = Math.toRadians(latitude2 - latitude1);
        double deltaLongitude = Math.toRadians(longitude2 - longitude1);

        double a = Math.sin(deltaLatitude / 2) * Math.sin(deltaLatitude / 2)
                + Math.cos(Math.toRadians(latitude1)) * Math.cos(Math.toRadians(latitude2))
                * Math.sin(deltaLongitude / 2) * Math.sin(deltaLongitude / 2);

        return 2 * EARTH_RADIUS_METRES * Math.asin(Math.min(1.0d, Math.sqrt(a)));
    }
}
