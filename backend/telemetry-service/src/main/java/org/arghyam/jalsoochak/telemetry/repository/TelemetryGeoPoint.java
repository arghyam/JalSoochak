package org.arghyam.jalsoochak.telemetry.repository;

/**
 * LOCATION-AFFINITY: a coordinate pair read from a tenant schema — either a scheme's recorded
 * position or the position a reading was submitted from.
 *
 * <p>Both halves are nullable, and a row can legitimately carry one without the other: the columns
 * are independently nullable on both {@code scheme_master_table} and {@code flow_reading_table}, and
 * schemes ingested by CSV upload frequently have neither. Callers must therefore treat a present
 * record with a {@code null} half as "no location", which
 * {@link org.arghyam.jalsoochak.telemetry.service.location.LocationAffinityPolicy} does.
 *
 * <p>{@link Double} rather than {@code BigDecimal} because both columns are
 * {@code DOUBLE PRECISION} and the only consumer is a haversine.
 */
public record TelemetryGeoPoint(Double latitude, Double longitude) {

    /** True when both halves are present, i.e. the pair can be fed to a distance calculation. */
    public boolean isComplete() {
        return latitude != null && longitude != null;
    }
}
