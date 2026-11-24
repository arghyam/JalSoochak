package org.arghyam.jalsoochak.message.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The payload as analytics-service sends it inside {@code WEEKLY_REPORT_KPIS}. */
class WeeklyReportKpisTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsTheThresholdsAnalyticsApplied() throws Exception {
        WeeklyReportKpis kpis = objectMapper.readValue(
                "{\"weekStart\":\"2026-07-16\",\"lowLpcdThreshold\":15,\"minSupplyDays\":4}", WeeklyReportKpis.class);

        assertThat(kpis.getLowLpcdThreshold()).isEqualTo(15);
        assertThat(kpis.getMinSupplyDays()).isEqualTo(4);
    }

    @Test
    void readsThresholdsAnOlderAnalyticsLeavesOutAsAbsent() throws Exception {
        WeeklyReportKpis kpis = objectMapper.readValue("{\"weekStart\":\"2026-07-16\"}", WeeklyReportKpis.class);

        assertThat(kpis.getLowLpcdThreshold()).isNull();
        assertThat(kpis.getMinSupplyDays()).isNull();
    }
}
