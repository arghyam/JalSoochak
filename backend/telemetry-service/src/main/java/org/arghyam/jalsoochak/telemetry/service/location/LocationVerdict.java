package org.arghyam.jalsoochak.telemetry.service.location;

/**
 * LOCATION-AFFINITY: the outcome of {@link LocationAffinityPolicy#evaluate}.
 *
 * <p>Sealed so every caller must handle each arm. There are two very different consumers — the
 * {@code /location} webhook, which turns a verdict into a yes/no prompt for the operator, and the
 * reading path, which turns it into an anomaly row — and a fourth outcome added later should not
 * compile until both have decided what it means.
 *
 * <p>{@link Within} and {@link Outside} carry the same two numbers so the log line and the metric
 * read identically either way. Unlike the supply-plausibility verdicts these numbers are
 * <em>not</em> a disclosure hazard: the threshold is an operational radius, not something a caller
 * can solve backwards into another scheme's private data. They are still kept out of the anomaly
 * {@code reason} text, but only so the column stays groupable.
 */
public sealed interface LocationVerdict
        permits LocationVerdict.Skipped, LocationVerdict.Within, LocationVerdict.Outside {

    /**
     * Why a submission could not be assessed. The tag is a metric label value and therefore part of
     * the dashboard contract — {@code location_affinity.skipped{reason="no_scheme_location"}} is the
     * panel that tells an admin their master data is the reason the check is doing nothing. Rename a
     * tag and the panel goes blank.
     *
     * <p>Every one of these means "no opinion", never "in bounds". A skipped submission is
     * indistinguishable in the data from one that was never checked, which is why an empty Location
     * Mismatch section in a report does not prove every reading was in bounds.
     */
    enum SkipReason {

        /**
         * The tenant's {@code LOCATION_CHECK_REQUIRED} is not {@code YES}. This is the feature's off
         * switch, and it governs the state-IT API as well as WhatsApp — a tenant that has not opted
         * in gets no anomalies even if its integrator sends coordinates.
         */
        CHECK_NOT_REQUIRED("check_not_required"),

        /**
         * The submission carried no coordinates. Ordinary on the WhatsApp path when the flow never
         * asked for location, and on the state-IT API when {@code geolocation} was omitted.
         */
        NO_READING_LOCATION("no_reading_location"),

        /**
         * {@code scheme_master_table} has no usable coordinates for this scheme — null, absent, or
         * out of range. A master-data gap rather than a property of the submission, so it is logged
         * at {@code WARN}: it silently disables the check for every reading on that scheme.
         */
        NO_SCHEME_LOCATION("no_scheme_location"),

        /**
         * {@code LOCATION_AFFINITY_THRESHOLD} is unset, unparseable or non-positive, so there is no
         * radius to compare against.
         */
        NO_THRESHOLD("no_threshold");

        private final String metricTag;

        SkipReason(String metricTag) {
            this.metricTag = metricTag;
        }

        public String metricTag() {
            return metricTag;
        }
    }

    /** The check did not run. The submission proceeds exactly as it would have before this feature. */
    record Skipped(SkipReason reason) implements LocationVerdict {
    }

    /**
     * The submission is within the configured radius of the scheme.
     *
     * @param distanceMetres  measured great-circle distance
     * @param thresholdMetres the radius it was compared against
     */
    record Within(double distanceMetres, double thresholdMetres) implements LocationVerdict {
    }

    /**
     * The submission is farther from the scheme than the configured radius. Strictly greater: a
     * submission landing exactly on the threshold is {@link Within}, matching "greater than affinity
     * metres".
     *
     * @param distanceMetres  measured great-circle distance
     * @param thresholdMetres the radius it exceeded
     */
    record Outside(double distanceMetres, double thresholdMetres) implements LocationVerdict {
    }
}
