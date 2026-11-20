package org.arghyam.jalsoochak.message.report.catalog;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryColumnTest {

    @Test
    void dailySummaryHasALabelAndOneValue() {
        assertThat(Arrays.stream(SummaryColumn.values()).filter(c -> c.availableIn(ReportKind.DAILY)))
                .containsExactly(SummaryColumn.LABEL, SummaryColumn.VALUE);
    }

    @Test
    void weeklySummaryComparesTwoWeeks() {
        assertThat(Arrays.stream(SummaryColumn.values()).filter(c -> c.availableIn(ReportKind.WEEKLY)))
                .containsExactly(SummaryColumn.LABEL, SummaryColumn.CURRENT, SummaryColumn.PREVIOUS,
                        SummaryColumn.TREND);
    }

    @Test
    void everyColumnHasAPositiveDefaultWeight() {
        assertThat(SummaryColumn.values()).allSatisfy(c -> assertThat(c.defaultWeight()).isPositive());
    }
}
