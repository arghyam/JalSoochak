package org.arghyam.jalsoochak.analytics.helper;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Single source of truth for the {@code work_status} restriction applied to dashboard calculations.
 *
 * <p>Only schemes whose {@code dim_scheme_table.work_status} is in the configured list are counted
 * in dashboard aggregates (water quantity, outage/non-submission reasons, submission status, scheme
 * status, critical/continuous schemes). {@code 4} = handed-over. The list is driven by
 * {@code analytics.dashboard.included-work-statuses} so the set can change (e.g. to include more
 * statuses in future) via config/env only, without a code change. An empty list disables the filter
 * (all schemes included), preserving the pre-filter behaviour as an escape hatch.</p>
 *
 * <p>Values originate from trusted configuration and are parsed to {@code int}s, so the resulting
 * {@code IN (...)} fragment is safe to inline into SQL. Inlining (rather than binding a parameter)
 * keeps every existing positional {@code ?} placeholder in the repository undisturbed, mirroring the
 * repository's existing {@code {{SWS}}}/{@code {{LWQ}}} token-substitution approach.</p>
 */
public final class DashboardWorkStatusFilter {

    private final List<Integer> includedWorkStatuses;

    /**
     * @param includedWorkStatusesCsv comma-separated work_status values (e.g. {@code "4"} or
     *                                 {@code "4, 5"}); blank/null disables the filter.
     * @throws IllegalArgumentException if any non-blank token is not an integer
     */
    public DashboardWorkStatusFilter(String includedWorkStatusesCsv) {
        this.includedWorkStatuses = parse(includedWorkStatusesCsv);
    }

    private static List<Integer> parse(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(DashboardWorkStatusFilter::parseInt)
                .distinct()
                .sorted()
                .toList();
    }

    private static Integer parseInt(String token) {
        try {
            return Integer.valueOf(token);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid analytics.dashboard.included-work-statuses value: '" + token + "' (must be an integer)", e);
        }
    }

    /** The configured work_status values, sorted and de-duplicated; empty when the filter is disabled. */
    public List<Integer> includedWorkStatuses() {
        return includedWorkStatuses;
    }

    /** True when a restriction is configured (non-empty list). */
    public boolean isActive() {
        return !includedWorkStatuses.isEmpty();
    }

    /**
     * Renders the filter as an SQL {@code AND} predicate to append after an existing {@code WHERE}
     * on the {@code dim_scheme_table} alias, e.g. {@code " AND s.work_status IN (4)"}. Returns an
     * empty string when the filter is disabled so callers can inline it unconditionally.
     *
     * @param schemeAlias the alias used for {@code dim_scheme_table} in the query (e.g. {@code "s"})
     */
    public String andPredicate(String schemeAlias) {
        if (includedWorkStatuses.isEmpty()) {
            return "";
        }
        String values = includedWorkStatuses.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        return " AND " + schemeAlias + ".work_status IN (" + values + ")";
    }
}
