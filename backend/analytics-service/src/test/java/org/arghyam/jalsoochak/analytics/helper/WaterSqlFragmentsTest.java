package org.arghyam.jalsoochak.analytics.helper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the canonical water-supply SQL definitions shared by the dashboards and the officer reports.
 *
 * <p>These fragments are the reason a report and a dashboard cannot disagree about whether a scheme
 * supplied water. A change to any of them changes every KPI in the platform at once, so each is
 * asserted literally rather than through the behaviour of a caller.</p>
 */
class WaterSqlFragmentsTest {

    @Nested
    @DisplayName("supply-day predicate {{SWD}}")
    class SupplyDayPredicate {

        @Test
        @DisplayName("qualifies a SUBMITTED day with positive volume, and a legacy NULL-status day")
        void acceptsSubmittedAndLegacyRows() {
            String predicate = WaterSqlFragments.SUPPLIED_WATER_DAY;

            assertAll(
                    () -> assertTrue(predicate.contains("f.submission_status = 1"),
                            "SUBMITTED is status 1"),
                    () -> assertTrue(predicate.contains("f.submission_status IS NULL"),
                            "legacy direct-event rows carry no status and must still count"),
                    () -> assertTrue(predicate.contains("f.water_quantity > 0"),
                            "a zero-volume day is not a supply day"));
        }

        @Test
        @DisplayName("excludes NOT_SUBMITTED/outage days even when they carry a positive volume")
        void excludesNotSubmitted() {
            // status 0 is NOT_SUBMITTED. The predicate admits only status 1 or NULL, so an outage row
            // written with a stale positive water_quantity can never be counted as supply.
            assertFalse(WaterSqlFragments.SUPPLIED_WATER_DAY.contains("submission_status = 0"));
        }

        @Test
        @DisplayName("uses the same qualifying condition as the volume sum, so the two cannot drift")
        void matchesTheVolumeSum() {
            // Both must agree on what "supplied" means: a day counted as supply must contribute its
            // litres, and litres must only come from days counted as supply.
            String condition = "(f.submission_status = 1 OR f.submission_status IS NULL) "
                    + "AND f.water_quantity > 0";

            assertAll(
                    () -> assertTrue(normalise(WaterSqlFragments.SUPPLIED_WATER_DAY)
                            .contains(normalise(condition))),
                    () -> assertTrue(normalise(WaterSqlFragments.SUPPLIED_WATER_QUANTITY_SUM)
                            .contains(normalise(condition))));
        }
    }

    @Nested
    @DisplayName("de-duplicated water source {{LWQ}}")
    class LatestWaterQuantity {

        @Test
        @DisplayName("keeps one row per tenant/scheme/day, preferring the most recently updated")
        void dedupesByLatestUpdate() {
            String source = normalise(WaterSqlFragments.LATEST_WATER_QUANTITY);

            assertAll(
                    () -> assertTrue(source.contains(
                            "DISTINCT ON (fwq.tenant_id, fwq.scheme_id, fwq.date)")),
                    () -> assertTrue(source.contains("fwq.updated_at DESC, fwq.id DESC"),
                            "a concurrent replay must not double-count the day's volume"));
        }

        @Test
        @DisplayName("is a parenthesised sub-select, so callers can alias it like a table")
        void isUsableAsATableSource() {
            String source = WaterSqlFragments.LATEST_WATER_QUANTITY.strip();

            assertAll(
                    () -> assertTrue(source.startsWith("(")),
                    () -> assertTrue(source.endsWith(")")));
        }
    }

    @Nested
    @DisplayName("scheme-attribute row order")
    class SchemeAttributeRowOrder {

        @Test
        @DisplayName("orders by the count columns, not by write time")
        void ordersByCounts() {
            // dim_scheme_table fans out one row per LGD/department mapping. Summing fhtc_count without
            // collapsing a scheme to one row multiplies its households by its mapping count.
            String order = normalise(WaterSqlFragments.schemeAttributeRowOrder("s"));

            assertEquals(normalise("s.fhtc_count DESC NULLS LAST, "
                    + "s.house_hold_count DESC NULLS LAST, "
                    + "s.planned_fhtc DESC NULLS LAST"), order);
        }

        @Test
        @DisplayName("drops the alias prefix when selecting from an earlier CTE")
        void supportsBlankAlias() {
            assertEquals(normalise("fhtc_count DESC NULLS LAST, "
                            + "house_hold_count DESC NULLS LAST, "
                            + "planned_fhtc DESC NULLS LAST"),
                    normalise(WaterSqlFragments.schemeAttributeRowOrder("")));
        }
    }

    @Nested
    @DisplayName("token substitution")
    class TokenSubstitution {

        @Test
        @DisplayName("replaces every water token in one pass")
        void replacesAllTokens() {
            String rendered = WaterSqlFragments.withWaterFragments(
                    "SELECT {{SWS}} AS litres FROM {{LWQ}} f WHERE {{SWD}}");

            assertAll(
                    () -> assertFalse(rendered.contains("{{")),
                    () -> assertTrue(rendered.contains("fact_water_quantity_table")),
                    () -> assertTrue(rendered.contains("water_quantity > 0")));
        }

        @Test
        @DisplayName("fails fast on an unreplaced token rather than shipping an unfiltered query")
        void rejectsUnknownToken() {
            // A misspelled token would otherwise reach the database as invalid SQL — or worse, leave a
            // filter out entirely and silently over-report.
            IllegalStateException thrown = assertThrows(IllegalStateException.class,
                    () -> WaterSqlFragments.withWaterFragments("SELECT * FROM {{TYPO}}"));

            assertTrue(thrown.getMessage().contains("{{TYPO}}"),
                    "the message must name the offending query so the typo is findable");
        }

        @Test
        @DisplayName("leaves a query with no tokens untouched")
        void passesThroughPlainSql() {
            String sql = "SELECT 1";

            assertEquals(sql, WaterSqlFragments.withWaterFragments(sql));
        }
    }

    /** Collapses runs of whitespace so assertions survive SQL reformatting. */
    private static String normalise(String sql) {
        return sql.replaceAll("\\s+", " ").strip();
    }
}
