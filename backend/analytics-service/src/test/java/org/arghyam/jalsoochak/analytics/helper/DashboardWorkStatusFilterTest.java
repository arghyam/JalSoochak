package org.arghyam.jalsoochak.analytics.helper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link DashboardWorkStatusFilter}. The filter renders SQL predicate fragments
 * (not bound parameters) so assertions inspect the emitted SQL structure rather than a value list.
 */
class DashboardWorkStatusFilterTest {

    // ---- env-default array literal ----

    @Test
    void singleEnvValue_rendersEnvArrayLiteral() {
        DashboardWorkStatusFilter filter = new DashboardWorkStatusFilter("4");
        assertThat(filter.andNationalPredicate("s")).contains("ARRAY[4]::int[]");
    }

    @Test
    void multipleEnvValues_areSortedDedupedInArrayLiteral() {
        DashboardWorkStatusFilter filter = new DashboardWorkStatusFilter(" 5, 4 , 4 ");
        assertThat(filter.andNationalPredicate("s")).contains("ARRAY[4, 5]::int[]");
    }

    @Test
    void blankOrNullEnv_rendersNullArrayLiteral() {
        for (String csv : new String[] {null, "", "   ", " , "}) {
            DashboardWorkStatusFilter filter = new DashboardWorkStatusFilter(csv);
            String national = filter.andNationalPredicate("s");
            assertThat(national).as("csv=%s", csv).contains("NULL::int[]");
            assertThat(national).as("csv=%s", csv).doesNotContain("ARRAY[");
        }
    }

    @Test
    void nonIntegerEnvValue_isRejected() {
        assertThatThrownBy(() -> new DashboardWorkStatusFilter("4,abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("abc");
    }

    // ---- tenant-scoped predicate: own tenant -> tenant-0 -> env ----

    @Test
    void tenantPredicate_hasThreeTierFallbackAndNullSafeGuard() {
        DashboardWorkStatusFilter filter = new DashboardWorkStatusFilter("4");
        String predicate = filter.andPredicate("s");

        assertThat(predicate).startsWith(" AND (");
        // own-tenant tier keyed on the scheme alias' tenant_id
        assertThat(predicate).contains("dt_own.tenant_id = s.tenant_id");
        // national (tenant-0) tier
        assertThat(predicate).contains("dt_nat.tenant_id = 0");
        // env-default tier
        assertThat(predicate).contains("ARRAY[4]::int[]");
        // disabled -> NULL effective set -> include all; active -> strict ANY(...) membership
        assertThat(predicate).contains("IS NULL OR s.work_status = ANY(");
    }

    @Test
    void tenantPredicate_usesProvidedAliasThroughout() {
        DashboardWorkStatusFilter filter = new DashboardWorkStatusFilter("4");
        String predicate = filter.andPredicate("d");

        assertThat(predicate).contains("dt_own.tenant_id = d.tenant_id");
        assertThat(predicate).contains("d.work_status = ANY(");
        assertThat(predicate).doesNotContain("s.work_status");
    }

    // ---- national-scoped predicate: tenant-0 -> env (no own-tenant tier) ----

    @Test
    void nationalPredicate_hasNoOwnTenantTier() {
        DashboardWorkStatusFilter filter = new DashboardWorkStatusFilter("4");
        String predicate = filter.andNationalPredicate("s");

        assertThat(predicate).startsWith(" AND (");
        assertThat(predicate).doesNotContain("dt_own");
        assertThat(predicate).contains("dt_nat.tenant_id = 0");
        assertThat(predicate).contains("ARRAY[4]::int[]");
        assertThat(predicate).contains("IS NULL OR s.work_status = ANY(");
    }

    @Test
    void bothPredicates_areAlwaysActive_neverEmpty() {
        // Even with a blank env the predicate is emitted (NULL-safe = "filter disabled"), never "".
        DashboardWorkStatusFilter disabled = new DashboardWorkStatusFilter("");
        assertThat(disabled.andPredicate("s")).isNotEmpty();
        assertThat(disabled.andNationalPredicate("s")).isNotEmpty();
    }
}
