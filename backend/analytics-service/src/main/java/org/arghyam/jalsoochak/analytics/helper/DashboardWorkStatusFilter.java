package org.arghyam.jalsoochak.analytics.helper;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Single source of truth for the {@code work_status} restriction applied to dashboard calculations.
 *
 * <p>Only schemes whose {@code dim_scheme_table.work_status} is in the <em>effective</em> included set
 * are counted in dashboard aggregates (water quantity, regularity, submission, scheme status,
 * critical/continuous schemes, etc.). {@code 4} = handed-over. The effective set is resolved per query
 * with a three-tier fallback, evaluated entirely in SQL so it always reflects the latest config without
 * a service restart:</p>
 *
 * <ul>
 *   <li><b>Tenant-scoped screens</b> ({@link #andPredicate(String)}): the scheme's own tenant config
 *       (`dim_tenant_table.included_work_statuses`), then the national default (tenant-0 row), then the
 *       {@code analytics.dashboard.included-work-statuses} env default.</li>
 *   <li><b>National-scoped screens</b> ({@link #andNationalPredicate(String)}): the national default
 *       (tenant-0 row), then the env default. There is no own-tenant tier, so every scheme on a national
 *       screen is judged against one uniform policy.</li>
 * </ul>
 *
 * <p>An empty effective set (nothing configured at any tier — env blank, no tenant-0 row, no own-tenant
 * config) resolves to {@code NULL}, which the predicate treats as "filter disabled" ⇒ all schemes are
 * included. This preserves the pre-filter behaviour as an escape hatch and keeps the predicate NULL-safe
 * only when the filter is disabled; when a set <em>is</em> configured the filter is strict (a scheme
 * whose {@code work_status} is not in the set — including a NULL {@code work_status} — is excluded).</p>
 *
 * <p>{@code dim_tenant_table} is a tiny lookup table, so the correlated sub-selects are cheap; the
 * national predicate's effective set is constant per query (Postgres evaluates it once). Values originate
 * from trusted configuration and are parsed to {@code int}s, so the resulting array literal is safe to
 * inline into SQL. Inlining (rather than binding a parameter) keeps every existing positional {@code ?}
 * placeholder in the repository undisturbed, mirroring the repository's {@code {{SWS}}}/{@code {{LWQ}}}
 * token-substitution approach.</p>
 */
public final class DashboardWorkStatusFilter {

    private static final int NATIONAL_TENANT_ID = 0;

    /** The env-default effective set as a SQL literal: {@code ARRAY[..]::int[]} or {@code NULL::int[]}. */
    private final String envArrayLiteral;

    /**
     * @param includedWorkStatusesCsv comma-separated work_status values (e.g. {@code "4"} or
     *                                 {@code "4, 5"}); blank/null means no env default (falls through to
     *                                 "filter disabled" when nothing else is configured).
     * @throws IllegalArgumentException if any non-blank token is not an integer
     */
    public DashboardWorkStatusFilter(String includedWorkStatusesCsv) {
        this.envArrayLiteral = toArrayLiteral(parse(includedWorkStatusesCsv));
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

    /** {@code ARRAY[4, 5]::int[]} for a non-empty set, else {@code NULL::int[]} ("filter disabled"). */
    private static String toArrayLiteral(List<Integer> statuses) {
        if (statuses.isEmpty()) {
            return "NULL::int[]";
        }
        return "ARRAY[" + statuses.stream().map(String::valueOf).collect(Collectors.joining(", ")) + "]::int[]";
    }

    /**
     * Tenant-scoped {@code {{WS}}} predicate: an {@code AND (...)} clause to append after an existing
     * {@code WHERE}/{@code ON} on the {@code dim_scheme_table} alias. Effective set = own tenant →
     * tenant-0 → env default. Include-all when the effective set is NULL (filter disabled).
     *
     * @param schemeAlias the alias used for {@code dim_scheme_table} in the query (e.g. {@code "s"})
     */
    public String andPredicate(String schemeAlias) {
        String effectiveSet = "COALESCE("
                + "NULLIF((SELECT dt_own.included_work_statuses FROM analytics_schema.dim_tenant_table dt_own"
                + " WHERE dt_own.tenant_id = " + schemeAlias + ".tenant_id), '{}'::int[]), "
                + nationalCoalesceArgs() + ")";
        return renderPredicate(schemeAlias, effectiveSet);
    }

    /**
     * National-scoped {@code {{NWS}}} predicate: same shape as {@link #andPredicate(String)} but with no
     * own-tenant tier. Effective set = tenant-0 → env default, applied uniformly to every scheme.
     *
     * @param schemeAlias the alias used for {@code dim_scheme_table} in the query (e.g. {@code "s"})
     */
    public String andNationalPredicate(String schemeAlias) {
        String effectiveSet = "COALESCE(" + nationalCoalesceArgs() + ")";
        return renderPredicate(schemeAlias, effectiveSet);
    }

    /** The tenant-0 → env-default tail shared by both predicates (as COALESCE arguments). */
    private String nationalCoalesceArgs() {
        return "NULLIF((SELECT dt_nat.included_work_statuses FROM analytics_schema.dim_tenant_table dt_nat"
                + " WHERE dt_nat.tenant_id = " + NATIONAL_TENANT_ID + "), '{}'::int[]), "
                + envArrayLiteral;
    }

    private static String renderPredicate(String schemeAlias, String effectiveSet) {
        return " AND (" + effectiveSet + " IS NULL OR "
                + schemeAlias + ".work_status = ANY(" + effectiveSet + "))";
    }

    // ------------------------------------------------------------------
    // History-based (as-of-date) variants for the pre-aggregation pipeline
    // ------------------------------------------------------------------

    /**
     * Tenant-scoped predicate resolved against the SCD-2 filter history
     * ({@code dim_tenant_work_status_filter_table}) as of {@code asOfDateSql}: the filter row in
     * force on that date wins, so pre-aggregated buckets are built with the filter that applied
     * to the period being aggregated, not today's. Tiers: own tenant → national (tenant 0) →
     * env default, each read from the history row covering the date (half-open intervals).
     *
     * @param schemeAlias the alias used for {@code dim_scheme_table} in the query (e.g. {@code "ds"})
     * @param asOfDateSql a SQL date expression for the as-of date — either a {@code DATE '...'}
     *                    literal built from a typed {@link java.time.LocalDate} or a plain column /
     *                    {@code CURRENT_DATE} reference; never user-supplied text
     */
    public String andHistoryPredicate(String schemeAlias, String asOfDateSql) {
        String effectiveSet = "COALESCE("
                + "NULLIF((" + historyTierSelect(schemeAlias + ".tenant_id", asOfDateSql) + "), '{}'::int[]), "
                + nationalHistoryCoalesceArgs(asOfDateSql) + ")";
        return renderPredicate(schemeAlias, effectiveSet);
    }

    /**
     * National-scoped variant of {@link #andHistoryPredicate(String, String)}: no own-tenant tier —
     * the national (tenant 0) history row as of the date, then the env default, applied uniformly.
     */
    public String andNationalHistoryPredicate(String schemeAlias, String asOfDateSql) {
        String effectiveSet = "COALESCE(" + nationalHistoryCoalesceArgs(asOfDateSql) + ")";
        return renderPredicate(schemeAlias, effectiveSet);
    }

    /** The national-history → env-default tail shared by both history predicates. */
    private String nationalHistoryCoalesceArgs(String asOfDateSql) {
        return "NULLIF((" + historyTierSelect(String.valueOf(NATIONAL_TENANT_ID), asOfDateSql) + "), '{}'::int[]), "
                + envArrayLiteral;
    }

    /** Scalar sub-select for one history tier: the filter set in force on the as-of date. */
    private static String historyTierSelect(String tenantIdSql, String asOfDateSql) {
        return "SELECT wsf.included_work_statuses"
                + " FROM analytics_schema.dim_tenant_work_status_filter_table wsf"
                + " WHERE wsf.tenant_id = " + tenantIdSql
                + " AND wsf.effective_from <= " + asOfDateSql
                + " AND (wsf.effective_to IS NULL OR wsf.effective_to > " + asOfDateSql + ")"
                + " ORDER BY wsf.effective_from DESC LIMIT 1";
    }
}
