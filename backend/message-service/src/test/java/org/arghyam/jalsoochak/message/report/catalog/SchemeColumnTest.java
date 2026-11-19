package org.arghyam.jalsoochak.message.report.catalog;

import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SchemeColumnTest {

    private static final ReportSchemeRow ROW = ReportSchemeRow.builder()
            .schemeId(7)
            .schemeName("Rampur PWSS")
            .imisId("IMIS-7")
            .villageNames("Rampur, Sitapur")
            .jalMitraNames("Asha")
            .jalMitraMobiles("91XXXXXXXXXX")
            .sectionOfficerNames("Ravi")
            .sectionOfficerMobiles("91YYYYYYYYYY")
            .anomalyType("Duplicate reading")
            .build();

    @Test
    void readsEachCellFromItsOwnField() {
        assertThat(SchemeColumn.SERIAL_NO.cell(ROW, 3)).isEqualTo("3");
        assertThat(SchemeColumn.SCHEME_NAME.cell(ROW, 3)).isEqualTo("Rampur PWSS");
        assertThat(SchemeColumn.CENTRE_SCHEME_ID.cell(ROW, 3)).isEqualTo("IMIS-7");
        assertThat(SchemeColumn.VILLAGES.cell(ROW, 3)).isEqualTo("Rampur, Sitapur");
        assertThat(SchemeColumn.PUMP_OPERATOR_NAMES.cell(ROW, 3)).isEqualTo("Asha");
        assertThat(SchemeColumn.PUMP_OPERATOR_MOBILES.cell(ROW, 3)).isEqualTo("91XXXXXXXXXX");
        assertThat(SchemeColumn.SECTION_OFFICER_NAMES.cell(ROW, 3)).isEqualTo("Ravi");
        assertThat(SchemeColumn.SECTION_OFFICER_MOBILES.cell(ROW, 3)).isEqualTo("91YYYYYYYYYY");
        assertThat(SchemeColumn.ANOMALY_TYPE.cell(ROW, 3)).isEqualTo("Duplicate reading");
    }

    @ParameterizedTest
    @EnumSource(value = SchemeColumn.class, names = "SERIAL_NO", mode = EnumSource.Mode.EXCLUDE)
    void printsAnUnresolvedFieldAsBlank(SchemeColumn column) {
        assertThat(column.cell(new ReportSchemeRow(), 1)).isEmpty();
    }

    @Test
    void onlySectionOfficerColumnsNeedTheSectionOfficerLookup() {
        assertThat(Arrays.stream(SchemeColumn.values()).filter(SchemeColumn::needsSectionOfficers))
                .containsExactly(SchemeColumn.SECTION_OFFICER_NAMES, SchemeColumn.SECTION_OFFICER_MOBILES);
    }

    @Test
    void onlyTheVillagesColumnNeedsTheVillageLookup() {
        assertThat(Arrays.stream(SchemeColumn.values()).filter(SchemeColumn::needsVillages))
                .containsExactly(SchemeColumn.VILLAGES);
    }

    @Test
    void anomalyTypeIsOnlyAllowedInTheAnomaliesList() {
        assertThat(Arrays.stream(SchemeList.values()).filter(SchemeColumn.ANOMALY_TYPE::allowedIn))
                .containsExactly(SchemeList.ANOMALIES);
    }

    @ParameterizedTest
    @EnumSource(value = SchemeColumn.class, names = "ANOMALY_TYPE", mode = EnumSource.Mode.EXCLUDE)
    void everyOtherColumnIsAllowedInEveryList(SchemeColumn column) {
        assertThat(SchemeList.values()).allSatisfy(list -> assertThat(column.allowedIn(list)).isTrue());
        assertThat(column.defaultWeight()).isPositive();
    }
}
