package org.arghyam.jalsoochak.analytics.helper;

import org.arghyam.jalsoochak.analytics.enums.SubmissionStatus;

/**
 * Single source of truth for what "this scheme supplied water" means in SQL.
 *
 * <p>The same question is asked by the dashboards, the region aggregates and the officer situation
 * reports. Expressed separately in each, the answers drifted: the daily report used to test
 * {@code fact_meter_reading_table.confirmed_reading > 0}, but {@code confirmed_reading} is a
 * <em>cumulative meter index</em>, so that condition holds for practically every reading ever taken —
 * the KPI labelled "Schemes Supplying Water" was really counting schemes that submitted a reading.
 * Holding the definition here, and injecting it as a token, is what keeps a report and a dashboard from
 * disagreeing about the same day.</p>
 *
 * <p>Fragments are injected by token rather than bound as parameters so that a caller's existing
 * positional {@code ?} placeholders are undisturbed:</p>
 *
 * <ul>
 *   <li>{@code {{LWQ}}} — {@link #LATEST_WATER_QUANTITY}, the de-duplicated water source, aliased
 *       {@code f} by callers</li>
 *   <li>{@code {{SWD}}} — {@link #SUPPLIED_WATER_DAY}, a boolean for a {@code WHERE}/{@code AND}</li>
 *   <li>{@code {{SWS}}} — {@link #SUPPLIED_WATER_QUANTITY_SUM}, a litres sum; callers append their own
 *       {@code AS <column>} alias</li>
 * </ul>
 *
 * <p>All substitution funnels through {@link #withWaterFragments(String)}, which fails fast on any
 * token it could not replace — a misspelled token would otherwise reach the database as invalid SQL or,
 * worse, silently leave a filter out of an otherwise valid query.</p>
 */
public final class WaterSqlFragments {

    private static final int SUBMITTED_STATUS = SubmissionStatus.SUBMITTED.getCode();

    private WaterSqlFragments() {
    }

    /**
     * De-duplicated water source: the latest {@code fact_water_quantity_table} row per
     * (tenant_id, scheme_id, date).
     *
     * <p>The table has no uniqueness constraint on that triple. Ingestion keeps the "current" row for a
     * day via find-latest-and-update ordered by {@code updated_at DESC, id DESC}, so production data
     * carries no duplicates today — but a stray one (a concurrent replay, say) would double-count that
     * day's volume if every row were summed. Reading through this sub-select makes that impossible.</p>
     */
    public static final String LATEST_WATER_QUANTITY = """
            (SELECT DISTINCT ON (fwq.tenant_id, fwq.scheme_id, fwq.date) fwq.*
                     FROM analytics_schema.fact_water_quantity_table fwq
                     ORDER BY fwq.tenant_id, fwq.scheme_id, fwq.date, fwq.updated_at DESC, fwq.id DESC)""";

    /**
     * Canonical "did this scheme supply water on this day" predicate, over the de-duplicated source
     * aliased {@code f}. Callers place it in a {@code WHERE}/{@code AND} and count
     * {@code DISTINCT f.date}.
     *
     * <p>A NOT_SUBMITTED/outage day never qualifies, even when it carries a positive
     * {@code water_quantity} left over from an earlier write. {@code NULL} status is a legacy
     * direct-event row and does qualify.</p>
     */
    public static final String SUPPLIED_WATER_DAY = String.format(
            "((f.submission_status = %d OR f.submission_status IS NULL) AND f.water_quantity > 0)",
            SUBMITTED_STATUS);

    /**
     * Canonical supplied volume in litres over the de-duplicated source aliased {@code f}, using the
     * <em>same</em> qualifying condition as {@link #SUPPLIED_WATER_DAY} so the two cannot drift: a day
     * counted as supply contributes its litres, and litres only ever come from days counted as supply.
     *
     * <p>{@code water_quantity} is litres (BIGINT since analytics migration V45); telemetry converts
     * from the meter's native m³ at the boundary.</p>
     */
    public static final String SUPPLIED_WATER_QUANTITY_SUM = String.format(
            "COALESCE(SUM(CASE WHEN (f.submission_status = %d OR f.submission_status IS NULL) "
                    + "AND f.water_quantity > 0 THEN f.water_quantity ELSE 0 END), 0)::bigint",
            SUBMITTED_STATUS);

    /**
     * Tie-break that collapses a fanned-out scheme to the single row its <em>household attributes</em>
     * should be read from. Pair with
     * {@code SELECT DISTINCT ON (<alias>.tenant_id, <alias>.scheme_id) … ORDER BY <alias>.tenant_id,
     * <alias>.scheme_id,} this expression.
     *
     * <p>{@code dim_scheme_table} holds one row per scheme <em>per LGD/department mapping</em>, so a
     * scheme spanning three villages appears three times. Summing {@code fhtc_count} over the raw rows
     * multiplies a scheme's households by its mapping count — inflating every population, LPCD
     * denominator and household percentage built on it. {@code COUNT(DISTINCT scheme_id)} is naturally
     * safe; {@code SUM} is not.</p>
     *
     * <p>Not to be confused with {@code canonicalSchemeRowOrder} ({@code {{CSR}}}), which orders by
     * {@code updated_at DESC} to find the most recently <em>written</em> row. That one is correct for
     * reading a scheme's current status or name, because the dimension writer rewrites only the row it
     * finds; this one is correct for reading its counts. They are not interchangeable.</p>
     *
     * @param alias alias of {@code dim_scheme_table}, or blank when selecting from an earlier CTE
     */
    public static String schemeAttributeRowOrder(String alias) {
        String prefix = (alias == null || alias.isBlank()) ? "" : alias + ".";
        return prefix + "fhtc_count DESC NULLS LAST, "
                + prefix + "house_hold_count DESC NULLS LAST, "
                + prefix + "planned_fhtc DESC NULLS LAST";
    }

    /**
     * Applies the {@code {{LWQ}}} / {@code {{SWS}}} / {@code {{SWD}}} substitutions to a built SQL
     * string, then fails fast if any {@code {{...}}} token remains.
     *
     * <p>Callers that also carry work-status or threshold tokens must replace those <em>before</em>
     * calling this, so the generated predicates are themselves scrubbed by the guard.</p>
     *
     * @throws IllegalStateException if a token could not be replaced
     */
    public static String withWaterFragments(String sql) {
        String out = sql
                .replace("{{SWS}}", SUPPLIED_WATER_QUANTITY_SUM)
                .replace("{{SWD}}", SUPPLIED_WATER_DAY)
                .replace("{{LWQ}}", LATEST_WATER_QUANTITY);
        if (out.contains("{{")) {
            throw new IllegalStateException("Unreplaced SQL token in query: " + out);
        }
        return out;
    }
}
