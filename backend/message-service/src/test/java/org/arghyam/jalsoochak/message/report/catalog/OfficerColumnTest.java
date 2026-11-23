package org.arghyam.jalsoochak.message.report.catalog;

import org.arghyam.jalsoochak.message.dto.WeeklyReportOfficerRow;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.function.DoubleFunction;

import static org.assertj.core.api.Assertions.assertThat;

class OfficerColumnTest {

    private static final WeeklyReportOfficerRow ROW = WeeklyReportOfficerRow.builder()
            .officerUserId(9L).name("Ravi").mobile("91XXXXXXXXXX")
            .totalSchemes(10).schemesSupplying(7).schemesNotSupplying(2).schemesLowLpcd(1).avgLpcd(48.25)
            .build();

    private static final DoubleFunction<String> DECIMAL = v -> "<" + v + ">";

    @Test
    void readsEachCellFromItsOwnField() {
        assertThat(OfficerColumn.SERIAL_NO.cell(ROW, 4, DECIMAL)).isEqualTo("4");
        assertThat(OfficerColumn.OFFICER_NAME.cell(ROW, 4, DECIMAL)).isEqualTo("Ravi");
        assertThat(OfficerColumn.OFFICER_MOBILE.cell(ROW, 4, DECIMAL)).isEqualTo("91XXXXXXXXXX");
        assertThat(OfficerColumn.TOTAL_SCHEMES.cell(ROW, 4, DECIMAL)).isEqualTo("10");
        assertThat(OfficerColumn.SCHEMES_SUPPLYING.cell(ROW, 4, DECIMAL)).isEqualTo("7");
        assertThat(OfficerColumn.SCHEMES_NOT_SUPPLYING.cell(ROW, 4, DECIMAL)).isEqualTo("2");
        assertThat(OfficerColumn.SCHEMES_LOW_LPCD.cell(ROW, 4, DECIMAL)).isEqualTo("1");
    }

    @Test
    void formatsAverageLpcdWithTheReportsDecimalFormat() {
        assertThat(OfficerColumn.AVG_LPCD.cell(ROW, 4, DECIMAL)).isEqualTo("<48.25>");
    }

    @Test
    void printsAnUnresolvedNameOrMobileAsBlank() {
        WeeklyReportOfficerRow unresolved = WeeklyReportOfficerRow.builder().officerUserId(9L).build();

        assertThat(OfficerColumn.OFFICER_NAME.cell(unresolved, 1, DECIMAL)).isEmpty();
        assertThat(OfficerColumn.OFFICER_MOBILE.cell(unresolved, 1, DECIMAL)).isEmpty();
    }

    @Test
    void namesAndMobilesAreTheOnlyLeftAlignedColumns() {
        assertThat(Arrays.stream(OfficerColumn.values()).filter(c -> !c.centred()))
                .containsExactly(OfficerColumn.OFFICER_NAME, OfficerColumn.OFFICER_MOBILE);
    }
}
