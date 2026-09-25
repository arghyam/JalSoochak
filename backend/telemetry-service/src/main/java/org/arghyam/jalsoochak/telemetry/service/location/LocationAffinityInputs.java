package org.arghyam.jalsoochak.telemetry.service.location;

/**
 * LOCATION-AFFINITY: everything {@link LocationAffinityPolicy#evaluate} needs, gathered by
 * {@link LocationAffinityService} before the policy runs.
 *
 * <p>A plain value carrier with no repository, config or context handles on it: the decision is
 * taken from four numbers and a flag, so it can be reproduced in a unit test and re-derived from a
 * log line without a database.
 *
 * <p>All four coordinates are boxed {@link Double} rather than primitives because all four sources
 * are genuinely nullable — {@code flow_reading_table.latitude/longitude} are only populated once an
 * operator shares a location or an integrator sends {@code geolocation}, and
 * {@code scheme_master_table.latitude/longitude} are optional master data that CSV upload often
 * leaves blank.
 *
 * @param checkRequired    the tenant's {@code LOCATION_CHECK_REQUIRED} resolved to a boolean; when
 *                         false nothing else is examined
 * @param readingLatitude  where the operator says the submission was made
 * @param readingLongitude where the operator says the submission was made
 * @param schemeLatitude   the scheme's recorded position
 * @param schemeLongitude  the scheme's recorded position
 * @param thresholdMetres  {@code LOCATION_AFFINITY_THRESHOLD}, already parsed and validated
 *                         positive; {@code null} when it did not resolve
 */
public record LocationAffinityInputs(
        boolean checkRequired,
        Double readingLatitude,
        Double readingLongitude,
        Double schemeLatitude,
        Double schemeLongitude,
        Double thresholdMetres
) {
}
