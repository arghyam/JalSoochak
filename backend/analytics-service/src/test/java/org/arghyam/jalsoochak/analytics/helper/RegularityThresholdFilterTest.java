package org.arghyam.jalsoochak.analytics.helper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link RegularityThresholdFilter}. Two concerns:
 *
 * <ul>
 *   <li>the {@link RegularityThresholdFilter#thresholdDays(long, BigDecimal)} spec table — the
 *       authoritative definition of "regular", guarded against the ceiling-vs-half-up trap;</li>
 *   <li>the emitted SQL fragments, which are rendered inline (not bound), so assertions inspect the
 *       SQL structure rather than a value list — mirroring {@link DashboardWorkStatusFilterTest}.</li>
 * </ul>
 */
class RegularityThresholdFilterTest {

    private static final BigDecimal NINETY = new BigDecimal("90");

    // ---- the spec table: thresholdDays(days, 90) ----

    /**
     * The KPI spec's own table. Rows 5 and 6 are the load-bearing ones: 0.9 x 5 = 4.5 -> 5 proves
     * HALF_UP (banker's rounding would give 4), and 0.9 x 6 = 5.4 -> 5 proves this is not a ceiling
     * (Math.ceil would give 6).
     */
    @ParameterizedTest(name = "days={0} -> threshold={1}")
    @CsvSource({
            "2, 2",
            "3, 3",
            "4, 4",
            "5, 5",
            "6, 5",
            "7, 6",
            "30, 27"
    })
    void thresholdDays_matchesSpecTableAtNinetyPercent(long days, int expected) {
        assertThat(RegularityThresholdFilter.thresholdDays(days, NINETY)).isEqualTo(expected);
    }

    @Test
    void thresholdDays_roundsHalfUp_notHalfEven() {
        // 0.9 x 5 = 4.5 -> 5 (HALF_UP), not 4 (HALF_EVEN / Postgres double-precision ROUND).
        assertThat(RegularityThresholdFilter.thresholdDays(5, NINETY)).isEqualTo(5);
        // 0.9 x 15 = 13.5 -> 14 (HALF_UP), not 14 by accident: HALF_EVEN also gives 14 here, so
        // pair it with the 4.5 case above which discriminates.
        assertThat(RegularityThresholdFilter.thresholdDays(15, NINETY)).isEqualTo(14);
    }

    @Test
    void thresholdDays_isNotCeiling() {
        // Every one of these would be one higher under Math.ceil.
        assertThat(RegularityThresholdFilter.thresholdDays(6, NINETY)).isEqualTo(5);
        assertThat(RegularityThresholdFilter.thresholdDays(7, NINETY)).isEqualTo(6);
        assertThat(RegularityThresholdFilter.thresholdDays(11, NINETY)).isEqualTo(10);
    }

    @Test
    void thresholdDays_supportsFractionalPercent() {
        // 87.5% of 8 = 7.0
        assertThat(RegularityThresholdFilter.thresholdDays(8, new BigDecimal("87.50"))).isEqualTo(7);
        // 87.5% of 30 = 26.25 -> 26
        assertThat(RegularityThresholdFilter.thresholdDays(30, new BigDecimal("87.50"))).isEqualTo(26);
    }

    @Test
    void thresholdDays_neverDropsBelowOne() {
        // A low configured percentage must never make a zero-supply scheme "regular".
        assertThat(RegularityThresholdFilter.thresholdDays(1, new BigDecimal("1"))).isEqualTo(1);
        assertThat(RegularityThresholdFilter.thresholdDays(10, new BigDecimal("1"))).isEqualTo(1);
        assertThat(RegularityThresholdFilter.thresholdDays(1, NINETY)).isEqualTo(1);
    }

    @Test
    void thresholdDays_atHundredPercent_requiresEveryDay() {
        assertThat(RegularityThresholdFilter.thresholdDays(30, new BigDecimal("100"))).isEqualTo(30);
    }

    @Test
    void thresholdDays_withNonPositiveDays_isOne() {
        assertThat(RegularityThresholdFilter.thresholdDays(0, NINETY)).isEqualTo(1);
    }

    // ---- env-default parsing ----

    @Test
    void envPercent_isRenderedAsNumericLiteral() {
        RegularityThresholdFilter filter = new RegularityThresholdFilter("90");
        assertThat(filter.nationalThresholdPercentExpr()).contains("90");
        assertThat(filter.envThresholdPercent()).isEqualByComparingTo(NINETY);
    }

    @Test
    void blankOrNullEnv_fallsBackToNinety() {
        for (String value : new String[] {null, "", "   "}) {
            RegularityThresholdFilter filter = new RegularityThresholdFilter(value);
            assertThat(filter.envThresholdPercent()).as("value=%s", value).isEqualByComparingTo(NINETY);
        }
    }

    @Test
    void nonNumericEnv_isRejected() {
        assertThatThrownBy(() -> new RegularityThresholdFilter("abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("abc");
    }

    @Test
    void outOfRangeEnv_isRejected() {
        assertThatThrownBy(() -> new RegularityThresholdFilter("0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegularityThresholdFilter("-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RegularityThresholdFilter("100.01"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- tenant-scoped percent expression: own tenant -> tenant-0 -> env ----

    @Test
    void tenantThresholdPercentExpr_hasThreeTierFallback() {
        RegularityThresholdFilter filter = new RegularityThresholdFilter("90");
        String expr = filter.tenantThresholdPercentExpr(7);

        assertThat(expr).startsWith("COALESCE(");
        // own-tenant tier, keyed on the request's tenantId literal (not a scheme alias)
        assertThat(expr).contains("WHERE dt_own.tenant_id = 7");
        // national (tenant-0) tier
        assertThat(expr).contains("WHERE dt_nat.tenant_id = 0");
        // env-default tier
        assertThat(expr).contains("90");
        assertThat(expr).contains("regularity_threshold_percent");
    }

    @Test
    void tenantThresholdPercentExpr_withNullTenantId_degradesToNational() {
        RegularityThresholdFilter filter = new RegularityThresholdFilter("90");
        String expr = filter.tenantThresholdPercentExpr(null);

        assertThat(expr).doesNotContain("dt_own");
        assertThat(expr).contains("dt_nat.tenant_id = 0");
    }

    // ---- national-scoped percent expression: tenant-0 -> env (no own-tenant tier) ----

    @Test
    void nationalThresholdPercentExpr_hasNoOwnTenantTier() {
        RegularityThresholdFilter filter = new RegularityThresholdFilter("90");
        String expr = filter.nationalThresholdPercentExpr();

        assertThat(expr).startsWith("COALESCE(");
        assertThat(expr).doesNotContain("dt_own");
        assertThat(expr).contains("dt_nat.tenant_id = 0");
    }

    // ---- the shared classification renderer ----

    @Test
    void isRegularExpr_castsToNumericAndGuardsAtOne() {
        String expr = RegularityThresholdFilter.isRegularExpr(
                "ssd.supply_days", "30", "90");

        // The Postgres rounding trap: ROUND(4.5::double precision) = 4 (banker's), whereas
        // ROUND(4.5::numeric) = 5 (half-up). Every rounding input must be numeric.
        assertThat(expr).contains("::numeric");
        assertThat(expr).contains("ROUND(");
        assertThat(expr).contains("GREATEST(1");
        assertThat(expr).contains("ssd.supply_days");
        assertThat(expr).contains(">=");
    }

    @Test
    void isRegularExpr_parenthesisesEveryInterpolatedExpression() {
        // Callers pass composite expressions; unparenthesised interpolation would mis-bind.
        String expr = RegularityThresholdFilter.isRegularExpr(
                "a + b", "c - d", "e * f");

        assertThat(expr).contains("(a + b)");
        assertThat(expr).contains("(c - d)");
        assertThat(expr).contains("(e * f)");
    }
}
