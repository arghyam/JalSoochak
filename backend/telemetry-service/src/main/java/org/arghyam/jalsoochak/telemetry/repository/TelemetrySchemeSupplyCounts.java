package org.arghyam.jalsoochak.telemetry.repository;

/**
 * SUPPLY-PLAUSIBILITY: the connection counts a scheme's served population is derived from.
 *
 * <p>All three columns are {@code INTEGER NOT NULL DEFAULT 0} in {@code scheme_master_table}
 * (see {@code V2__create_tenant_schema_function.sql}), so "not recorded" always arrives as {@code 0}
 * and never as {@code null} — the fallback chain that consumes this record tests for zero, not for
 * null.
 *
 * @param fhtcCount      functional household tap connections actually achieved
 * @param plannedFhtc    tap connections the scheme was designed for
 * @param houseHoldCount households in the scheme's service area
 */
public record TelemetrySchemeSupplyCounts(
        int fhtcCount,
        int plannedFhtc,
        int houseHoldCount
) {
}
