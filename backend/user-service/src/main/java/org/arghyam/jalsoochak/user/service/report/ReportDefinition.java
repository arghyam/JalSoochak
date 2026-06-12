package org.arghyam.jalsoochak.user.service.report;

import org.arghyam.jalsoochak.user.enums.ResourceType;

import java.util.List;

/**
 * Per-resource report definition. Bundles everything that is specific to one
 * report type: its identifier, the underlying {@link ResourceType} (drives
 * the {@code data_versions} staleness counter), the column schema, filter
 * normalization, and row fetching.
 *
 * <p>Adding a new report type is exactly one implementation of this interface
 * plus a request DTO and a thin per-resource controller. Formats are shared
 * across every resource via {@link ReportWriter}.
 *
 * @param <T> row type produced by the underlying repository
 * @param <F> filter DTO carried in the API request body
 */
public interface ReportDefinition<T, F> {

    /** Persisted in {@code reports_table.report_type}, e.g. {@code "TENANT_STAFF"}. */
    String type();

    /** Resource backing the data-version counter for cache staleness detection. */
    ResourceType resourceType();

    /** Column schema (header + per-column extractor). */
    ReportSchema<T> schema();

    /**
     * Normalizes filters into a canonical form (trim / case / dedup / sort)
     * so the cache key is stable regardless of caller-side ordering. The
     * result is hashed for {@code params_hash} and persisted in {@code params_json}.
     */
    F normalize(F filters);

    /** Full filtered row set for export — no pagination. */
    List<T> fetch(String tenantSchema, F normalizedFilters);

    /**
     * Prefix used to build the user-facing download filename, e.g.
     * {@code "staff_report"} → {@code staff_report_MP_20260519_1422.csv}.
     */
    String downloadFilenamePrefix();
}
