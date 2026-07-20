package org.arghyam.jalsoochak.analytics.helper;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Single source of truth for the scheme-regularity classification and its configurable threshold.
 *
 * <p>A scheme is <em>regular</em> over a window when it supplied water on at least
 * {@code thresholdDays(days, pct)} of the window's days:</p>
 *
 * <pre>thresholdDays(days, pct) = max(1, round(pct / 100 x days))   // HALF_UP</pre>
 *
 * <p>At the default 90%: 5 days -&gt; 5, 6 days -&gt; 5, 7 days -&gt; 6, 30 days -&gt; 27. The rounding is
 * <b>HALF_UP, not a ceiling</b> — 0.9 x 6 = 5.4 requires 5 days, not 6. The {@code max(1, ...)} floor is a
 * guard so a low configured percentage can never classify a scheme with zero supply days as regular; at
 * 90% it never binds.</p>
 *
 * <p>The threshold percentage is resolved per query with the same three-tier fallback as
 * {@link DashboardWorkStatusFilter}, evaluated entirely in SQL so it always reflects the latest config
 * without a service restart:</p>
 *
 * <ul>
 *   <li><b>Tenant-scoped screens</b> ({@link #tenantThresholdPercentExpr(Integer)}): the requested
 *       tenant's own config ({@code dim_tenant_table.regularity_threshold_percent}), then the national
 *       default (tenant-0 row), then the {@code analytics.dashboard.regularity.threshold-percent} env
 *       default.</li>
 *   <li><b>National-scoped screens</b> ({@link #nationalThresholdPercentExpr()}): the national default
 *       (tenant-0 row), then the env default. There is no own-tenant tier, so every state on a national
 *       screen is judged against one uniform bar.</li>
 * </ul>
 *
 * <p>Unlike the work-status filter, the own-tenant tier keys off the <em>request's</em> {@code tenantId}
 * literal rather than a scheme alias' {@code tenant_id} column: every tenant-scoped regularity API takes
 * {@code tenantId} as a request parameter, so the expression stays a scalar constant per query (Postgres
 * evaluates it once) and no {@code tenant_id} has to be threaded through every CTE.</p>
 *
 * <p>A NULL column means "not configured" and falls through to the next tier, so the migration adding the
 * column deliberately carries no {@code DEFAULT}. A stored value outside {@code (0, 100]} is treated as
 * unconfigured too, rather than being allowed to silently corrupt the KPI.</p>
 *
 * <p>Values originate from trusted configuration and are parsed to {@link BigDecimal}, so the resulting
 * literal is safe to inline into SQL. Inlining (rather than binding a parameter) keeps every existing
 * positional {@code ?} placeholder in the repository undisturbed, mirroring the repository's
 * {@code {{WS}}}/{@code {{SWD}}} token-substitution approach.</p>
 */
public final class RegularityThresholdFilter {

    private static final int NATIONAL_TENANT_ID = 0;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal DEFAULT_THRESHOLD_PERCENT = new BigDecimal("90");
    private static final String TENANT_TABLE = "analytics_schema.dim_tenant_table";
    private static final String THRESHOLD_COLUMN = "regularity_threshold_percent";

    /** The env-default threshold percentage, already validated to lie in {@code (0, 100]}. */
    private final BigDecimal envThresholdPercent;

    /** The env default as a SQL literal, e.g. {@code 90::numeric}. */
    private final String envLiteral;

    /**
     * @param thresholdPercent the {@code analytics.dashboard.regularity.threshold-percent} value;
     *                         blank/null falls back to 90.
     * @throws IllegalArgumentException if non-blank and not a number in {@code (0, 100]}
     */
    public RegularityThresholdFilter(String thresholdPercent) {
        this.envThresholdPercent = parse(thresholdPercent);
        this.envLiteral = envThresholdPercent.toPlainString() + "::numeric";
    }

    private static BigDecimal parse(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_THRESHOLD_PERCENT;
        }
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid analytics.dashboard.regularity.threshold-percent value: '" + value
                            + "' (must be a number)", e);
        }
        if (parsed.signum() <= 0 || parsed.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException(
                    "Invalid analytics.dashboard.regularity.threshold-percent value: '" + value
                            + "' (must be greater than 0 and at most 100)");
        }
        return parsed;
    }

    /** The env-default threshold percentage — the last tier of the fallback chain. */
    public BigDecimal envThresholdPercent() {
        return envThresholdPercent;
    }

    /**
     * The number of supply days a scheme needs to be classified regular over a {@code days}-long window.
     * This is the authoritative Java-side definition; {@link #isRegularExpr(String, String, String)} is its
     * SQL twin and the two must agree exactly (see the {@code ::numeric} note there).
     *
     * @param days            the window length in days
     * @param thresholdPercent the effective threshold percentage, in {@code (0, 100]}
     */
    public static int thresholdDays(long days, BigDecimal thresholdPercent) {
        if (days <= 0) {
            return 1;
        }
        int required = thresholdPercent
                .multiply(BigDecimal.valueOf(days))
                .divide(HUNDRED, 0, RoundingMode.HALF_UP)
                .intValueExact();
        return Math.max(1, required);
    }

    /**
     * Tenant-scoped threshold percentage: own tenant -&gt; tenant-0 -&gt; env default, as a scalar SQL
     * expression of type {@code numeric}.
     *
     * @param tenantId the tenant the query is scoped to; {@code null} drops the own-tenant tier, degrading
     *                 to the national chain
     */
    public String tenantThresholdPercentExpr(Integer tenantId) {
        if (tenantId == null) {
            return nationalThresholdPercentExpr();
        }
        return "COALESCE(" + tierSelect("dt_own", tenantId) + ", " + nationalCoalesceArgs() + ")";
    }

    /**
     * National-scoped threshold percentage: tenant-0 -&gt; env default, applied uniformly to every state.
     * Same shape as {@link #tenantThresholdPercentExpr(Integer)} but with no own-tenant tier.
     */
    public String nationalThresholdPercentExpr() {
        return "COALESCE(" + nationalCoalesceArgs() + ")";
    }

    /** The tenant-0 -&gt; env-default tail shared by both expressions (as COALESCE arguments). */
    private String nationalCoalesceArgs() {
        return tierSelect("dt_nat", NATIONAL_TENANT_ID) + ", " + envLiteral;
    }

    /**
     * One tier of the chain. The range guard makes an out-of-range stored value read as unconfigured so it
     * falls through rather than corrupting the KPI.
     */
    private static String tierSelect(String alias, int tenantId) {
        return "(SELECT " + alias + "." + THRESHOLD_COLUMN
                + " FROM " + TENANT_TABLE + " " + alias
                + " WHERE " + alias + ".tenant_id = " + tenantId
                + " AND " + alias + "." + THRESHOLD_COLUMN + " > 0"
                + " AND " + alias + "." + THRESHOLD_COLUMN + " <= 100)";
    }

    /**
     * The canonical "is this scheme regular" boolean. <b>Every</b> regularity query routes through this one
     * method — that is what stops the definition drifting, exactly as {@code {{SWD}}} does for supply days.
     *
     * <p>The {@code ::numeric} casts are mandatory, not stylistic: {@code ROUND(4.5::double precision)} is
     * <b>4</b> (banker's rounding) whereas {@code ROUND(4.5::numeric)} is <b>5</b> (half-up). At the default
     * 90% a 5-day window needs exactly 4.5 days rounded, so a missing cast silently breaks the spec. The
     * multiply-before-divide order mirrors {@link #thresholdDays(long, BigDecimal)} so the two cannot
     * disagree.</p>
     *
     * @param supplyDaysExpr SQL expression for the scheme's supply-day count
     * @param daysExpr       SQL expression for the window length in days
     * @param pctExpr        SQL expression for the effective threshold percentage
     */
    public static String isRegularExpr(String supplyDaysExpr, String daysExpr, String pctExpr) {
        return "((" + supplyDaysExpr + ")::numeric >= GREATEST(1::numeric, ROUND("
                + "(" + pctExpr + ")::numeric * (" + daysExpr + ")::numeric / 100.0)))";
    }

    /**
     * The regularity KPI: the share of schemes in scope that are regular, to 4 decimal places (HALF_UP).
     * {@link BigDecimal#ZERO} when no schemes are in scope. Single definition shared by the repository row
     * mappers and the service aggregates so the KPI cannot drift between screens.
     */
    public static BigDecimal regularityRate(int regularSchemeCount, int schemeCount) {
        if (schemeCount <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(regularSchemeCount)
                .divide(BigDecimal.valueOf(schemeCount), 4, RoundingMode.HALF_UP);
    }
}
