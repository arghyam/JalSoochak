package org.arghyam.jalsoochak.message.report.catalog;

import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.arghyam.jalsoochak.message.dto.WeeklyReportKpis;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class SummaryKpiTest {

    private static final DailyReportKpis DAILY = DailyReportKpis.builder()
            .totalSchemes(11).schemesSupplying(12).schemesNotSupplying(13)
            .householdsWithSupply(14_000L).householdsWithSupplyPct(95.5)
            .householdsWithoutSupply(15_000L).householdsWithoutSupplyPct(4.5)
            .totalHouseholds(16_000L).avgLpcd(55.2).anomalousCount(17)
            .build();

    private static final WeeklyReportKpis.WeekKpis WEEK = WeeklyReportKpis.WeekKpis.builder()
            .totalSchemes(21).schemesSupplying(22).schemesNotSupplying(23).schemesLowLpcd(24).avgLpcd(25.5)
            .build();

    @Test
    void dailyKpisAreTheOnesTheDailyPayloadCarries() {
        assertThat(availableIn(ReportKind.DAILY)).containsExactlyInAnyOrder(
                SummaryKpi.TOTAL_SCHEMES, SummaryKpi.SCHEMES_SUPPLYING, SummaryKpi.SCHEMES_NOT_SUPPLYING,
                SummaryKpi.HOUSEHOLDS_WITH_SUPPLY, SummaryKpi.HOUSEHOLDS_WITHOUT_SUPPLY,
                SummaryKpi.TOTAL_HOUSEHOLDS, SummaryKpi.AVG_LPCD, SummaryKpi.ANOMALOUS_SUBMISSIONS);
    }

    @Test
    void weeklyKpisAreTheOnesTheWeeklyPayloadCarries() {
        assertThat(availableIn(ReportKind.WEEKLY)).containsExactlyInAnyOrder(
                SummaryKpi.TOTAL_SCHEMES, SummaryKpi.SCHEMES_SUPPLYING, SummaryKpi.SCHEMES_NOT_SUPPLYING,
                SummaryKpi.SCHEMES_LOW_LPCD, SummaryKpi.AVG_LPCD);
    }

    @Test
    void negativeKpisAreTheOnesWhereARiseIsWorse() {
        assertThat(Arrays.stream(SummaryKpi.values()).filter(SummaryKpi::negative))
                .containsExactlyInAnyOrder(SummaryKpi.SCHEMES_NOT_SUPPLYING, SummaryKpi.SCHEMES_LOW_LPCD,
                        SummaryKpi.HOUSEHOLDS_WITHOUT_SUPPLY, SummaryKpi.ANOMALOUS_SUBMISSIONS);
    }

    @Test
    void onlyTheHouseholdSplitCarriesAPercentage() {
        assertThat(Arrays.stream(SummaryKpi.values()).filter(SummaryKpi::hasPercent))
                .containsExactlyInAnyOrder(SummaryKpi.HOUSEHOLDS_WITH_SUPPLY, SummaryKpi.HOUSEHOLDS_WITHOUT_SUPPLY);
    }

    @Test
    void onlyAverageLpcdIsADecimal() {
        assertThat(Arrays.stream(SummaryKpi.values()).filter(k -> k.valueType() == SummaryKpi.ValueType.DECIMAL))
                .containsExactly(SummaryKpi.AVG_LPCD);
    }

    @Test
    void readsEachDailyValueFromItsOwnField() {
        assertThat(SummaryKpi.TOTAL_SCHEMES.dailyValue(DAILY)).isEqualTo(11);
        assertThat(SummaryKpi.SCHEMES_SUPPLYING.dailyValue(DAILY)).isEqualTo(12);
        assertThat(SummaryKpi.SCHEMES_NOT_SUPPLYING.dailyValue(DAILY)).isEqualTo(13);
        assertThat(SummaryKpi.HOUSEHOLDS_WITH_SUPPLY.dailyValue(DAILY)).isEqualTo(14_000);
        assertThat(SummaryKpi.HOUSEHOLDS_WITHOUT_SUPPLY.dailyValue(DAILY)).isEqualTo(15_000);
        assertThat(SummaryKpi.TOTAL_HOUSEHOLDS.dailyValue(DAILY)).isEqualTo(16_000);
        assertThat(SummaryKpi.AVG_LPCD.dailyValue(DAILY)).isCloseTo(55.2, within(1e-9));
        assertThat(SummaryKpi.ANOMALOUS_SUBMISSIONS.dailyValue(DAILY)).isEqualTo(17);
    }

    @Test
    void readsEachDailyPercentageFromItsOwnField() {
        assertThat(SummaryKpi.HOUSEHOLDS_WITH_SUPPLY.dailyPercent(DAILY)).isEqualTo(95.5);
        assertThat(SummaryKpi.HOUSEHOLDS_WITHOUT_SUPPLY.dailyPercent(DAILY)).isEqualTo(4.5);
    }

    @Test
    void readsEachWeeklyValueFromItsOwnField() {
        assertThat(SummaryKpi.TOTAL_SCHEMES.weeklyValue(WEEK)).isEqualTo(21);
        assertThat(SummaryKpi.SCHEMES_SUPPLYING.weeklyValue(WEEK)).isEqualTo(22);
        assertThat(SummaryKpi.SCHEMES_NOT_SUPPLYING.weeklyValue(WEEK)).isEqualTo(23);
        assertThat(SummaryKpi.SCHEMES_LOW_LPCD.weeklyValue(WEEK)).isEqualTo(24);
        assertThat(SummaryKpi.AVG_LPCD.weeklyValue(WEEK)).isEqualTo(25.5);
    }

    @Test
    void refusesToReadAKpiTheReportDoesNotCarry() {
        assertThatThrownBy(() -> SummaryKpi.SCHEMES_LOW_LPCD.dailyValue(DAILY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("SCHEMES_LOW_LPCD is not available in DAILY reports");
        assertThatThrownBy(() -> SummaryKpi.ANOMALOUS_SUBMISSIONS.weeklyValue(WEEK))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ANOMALOUS_SUBMISSIONS is not available in WEEKLY reports");
        assertThatThrownBy(() -> SummaryKpi.TOTAL_SCHEMES.dailyPercent(DAILY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("TOTAL_SCHEMES has no percentage");
    }

    private static Set<SummaryKpi> availableIn(ReportKind kind) {
        return Arrays.stream(SummaryKpi.values()).filter(k -> k.availableIn(kind))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(SummaryKpi.class)));
    }
}
