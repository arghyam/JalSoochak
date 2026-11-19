package org.arghyam.jalsoochak.message.report.catalog;

import org.arghyam.jalsoochak.message.report.ReportKind;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SchemeListTest {

    @Test
    void dailyReportListsNoSupplyAndAnomalies() {
        assertThat(Arrays.stream(SchemeList.values()).filter(l -> l.availableIn(ReportKind.DAILY)))
                .containsExactly(SchemeList.NO_SUPPLY, SchemeList.ANOMALIES);
    }

    @Test
    void weeklyReportListsNoSupplyLowSupplyDaysAndLowLpcd() {
        assertThat(Arrays.stream(SchemeList.values()).filter(l -> l.availableIn(ReportKind.WEEKLY)))
                .containsExactly(SchemeList.NO_SUPPLY, SchemeList.LOW_SUPPLY_DAYS, SchemeList.LOW_LPCD);
    }
}
