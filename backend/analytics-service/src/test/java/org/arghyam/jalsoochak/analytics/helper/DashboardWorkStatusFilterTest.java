package org.arghyam.jalsoochak.analytics.helper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DashboardWorkStatusFilterTest {

    @Test
    void singleValue_buildsInPredicate() {
        DashboardWorkStatusFilter filter = new DashboardWorkStatusFilter("4");

        assertThat(filter.isActive()).isTrue();
        assertThat(filter.includedWorkStatuses()).containsExactly(4);
        assertThat(filter.andPredicate("s")).isEqualTo(" AND s.work_status IN (4)");
    }

    @Test
    void multipleValues_areSortedDedupedAndRendered() {
        DashboardWorkStatusFilter filter = new DashboardWorkStatusFilter(" 5, 4 , 4 ");

        assertThat(filter.includedWorkStatuses()).containsExactly(4, 5);
        assertThat(filter.andPredicate("d")).isEqualTo(" AND d.work_status IN (4, 5)");
    }

    @Test
    void blankOrNull_disablesFilter() {
        for (String csv : new String[] {null, "", "   ", " , "}) {
            DashboardWorkStatusFilter filter = new DashboardWorkStatusFilter(csv);
            assertThat(filter.isActive()).as("csv=%s", csv).isFalse();
            assertThat(filter.includedWorkStatuses()).isEmpty();
            assertThat(filter.andPredicate("s")).isEmpty();
        }
    }

    @Test
    void nonIntegerValue_isRejected() {
        assertThatThrownBy(() -> new DashboardWorkStatusFilter("4,abc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("abc");
    }

    @Test
    void includedWorkStatuses_isImmutable() {
        List<Integer> statuses = new DashboardWorkStatusFilter("4").includedWorkStatuses();
        assertThatThrownBy(() -> statuses.add(9)).isInstanceOf(UnsupportedOperationException.class);
    }
}
