package org.arghyam.jalsoochak.telemetry.service.location;

import java.util.Objects;

/**
 * LOCATION-AFFINITY: decides whether a meter reading was submitted from far enough away from its
 * scheme to be worth recording.
 *
 * <pre>
 * distance = haversine(readingLat, readingLng, schemeLat, schemeLng)
 * outside iff distance &gt; thresholdMetres
 * </pre>
 *
 * <p>Pure arithmetic over {@link LocationAffinityInputs}: no Spring, no repository, no clock. The
 * decision is reproducible from the numbers alone, which is what lets an operator argue with it and
 * what lets {@code LocationAffinityPolicyTest} cover every branch without a context.
 *
 * <p><strong>Every ambiguity resolves to {@link LocationVerdict.Skipped}, never to a mismatch.</strong>
 * This asymmetry is deliberate: a false mismatch accuses a named operator of submitting a reading
 * from somewhere they were not, on a report their officer reads. Missing the detection costs a data
 * point; inventing one costs someone's credibility. So a row of type
 * {@code LOCATION_MISMATCH} always means a real measured overshoot.
 */
public final class LocationAffinityPolicy {

    private static final double MAX_ABSOLUTE_LATITUDE = 90.0d;
    private static final double MAX_ABSOLUTE_LONGITUDE = 180.0d;

    private LocationAffinityPolicy() {
    }

    /**
     * Assesses one submission.
     *
     * <p>Skip conditions are ordered so each counter measures something distinct and actionable.
     * {@link LocationVerdict.SkipReason#CHECK_NOT_REQUIRED} comes first because it is the feature's
     * off switch and should not be diluted by tenants who also lack coordinates;
     * {@link LocationVerdict.SkipReason#NO_SCHEME_LOCATION} comes after
     * {@link LocationVerdict.SkipReason#NO_READING_LOCATION} so that it counts only submissions that
     * <em>would</em> have been assessed — that is the figure that says how much master data is worth
     * fixing.
     *
     * @param inputs gathered flag, coordinates and threshold
     * @return {@link LocationVerdict.Skipped}, {@link LocationVerdict.Within} or
     *         {@link LocationVerdict.Outside}
     */
    public static LocationVerdict evaluate(LocationAffinityInputs inputs) {
        Objects.requireNonNull(inputs, "inputs");

        if (!inputs.checkRequired()) {
            return new LocationVerdict.Skipped(LocationVerdict.SkipReason.CHECK_NOT_REQUIRED);
        }

        if (!isUsableCoordinate(inputs.readingLatitude(), inputs.readingLongitude())) {
            return new LocationVerdict.Skipped(LocationVerdict.SkipReason.NO_READING_LOCATION);
        }

        if (!isUsableCoordinate(inputs.schemeLatitude(), inputs.schemeLongitude())) {
            return new LocationVerdict.Skipped(LocationVerdict.SkipReason.NO_SCHEME_LOCATION);
        }

        Double threshold = inputs.thresholdMetres();
        if (threshold == null || !Double.isFinite(threshold) || threshold <= 0.0d) {
            return new LocationVerdict.Skipped(LocationVerdict.SkipReason.NO_THRESHOLD);
        }

        double distance = GeoDistance.metresBetween(
                inputs.readingLatitude(), inputs.readingLongitude(),
                inputs.schemeLatitude(), inputs.schemeLongitude());

        // Strictly greater, so a submission landing exactly on the threshold is in bounds. This
        // mirrors the supply-plausibility ceiling and matches the requirement's "greater than
        // affinity metres".
        return distance > threshold
                ? new LocationVerdict.Outside(distance, threshold)
                : new LocationVerdict.Within(distance, threshold);
    }

    /**
     * A coordinate pair is usable only when both halves are present, finite and in range. An
     * out-of-range value means corrupt data rather than a distant submission, and feeding it to the
     * haversine would produce a confident, meaningless number.
     */
    private static boolean isUsableCoordinate(Double latitude, Double longitude) {
        return latitude != null && longitude != null
                && Double.isFinite(latitude) && Double.isFinite(longitude)
                && Math.abs(latitude) <= MAX_ABSOLUTE_LATITUDE
                && Math.abs(longitude) <= MAX_ABSOLUTE_LONGITUDE;
    }
}
