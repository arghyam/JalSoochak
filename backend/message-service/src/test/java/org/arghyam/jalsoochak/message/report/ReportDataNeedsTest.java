package org.arghyam.jalsoochak.message.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.message.report.ReportDataNeeds.SchemeDetail;
import org.arghyam.jalsoochak.message.report.catalog.ReportKind;
import org.arghyam.jalsoochak.message.report.catalog.SchemeList;
import org.arghyam.jalsoochak.message.report.layout.ReportLayout;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportDataNeedsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DefaultReportLayouts DEFAULTS = new DefaultReportLayouts(MAPPER);
    private static final SchemeDetail PLAIN = new SchemeDetail(false, false);
    private static final SchemeDetail FULL = new SchemeDetail(true, true);

    private static ReportDataNeeds builtIn(ReportKind kind, String role) {
        return ReportDataNeeds.of(DEFAULTS.builtIn(kind, role).orElseThrow());
    }

    private static ReportDataNeeds of(String layoutJson) throws Exception {
        return ReportDataNeeds.of(MAPPER.readValue(layoutJson, ReportLayout.class));
    }

    /** A daily layout with one no-supply list of {@code columns}. */
    private static String noSupplyListOf(String columns) {
        return """
                {"sections":[{"type":"SCHEME_LIST","list":"NO_SUPPLY","title":"No supply","columns":[%s]}]}
                """.formatted(columns);
    }

    // The built-in layouts must need exactly what the router resolved for each role before layouts existed.

    @Test
    void theDailySectionOfficerLayoutNeedsBothDailyListsWithNoExtraDetail() {
        ReportDataNeeds needs = builtIn(ReportKind.DAILY, "SECTION_OFFICER");

        assertThat(needs.schemeLists()).isEqualTo(Map.of(SchemeList.NO_SUPPLY, PLAIN, SchemeList.ANOMALIES, PLAIN));
        assertThat(needs.officerRows()).isFalse();
    }

    @Test
    void theWeeklySectionOfficerLayoutNeedsAllThreeWeeklyListsWithNoExtraDetail() {
        ReportDataNeeds needs = builtIn(ReportKind.WEEKLY, "SECTION_OFFICER");

        assertThat(needs.schemeLists()).isEqualTo(Map.of(SchemeList.NO_SUPPLY, PLAIN,
                SchemeList.LOW_SUPPLY_DAYS, PLAIN, SchemeList.LOW_LPCD, PLAIN));
        assertThat(needs.officerRows()).isFalse();
    }

    @Test
    void theWeeklySubDivisionalOfficerLayoutNeedsOfficersVillagesAndItsOfficerRows() {
        ReportDataNeeds needs = builtIn(ReportKind.WEEKLY, "SUB_DIVISIONAL_OFFICER");

        assertThat(needs.schemeLists()).isEqualTo(Map.of(SchemeList.NO_SUPPLY, FULL, SchemeList.LOW_LPCD, FULL));
        assertThat(needs.officerRows()).isTrue();
    }

    @Test
    void eitherSectionOfficerColumnNeedsTheSectionOfficerLookup() throws Exception {
        assertThat(of(noSupplyListOf("{\"field\":\"SECTION_OFFICER_MOBILES\",\"label\":\"JE Mobile\"}"))
                .schemeList(SchemeList.NO_SUPPLY)).contains(new SchemeDetail(true, false));
        assertThat(of(noSupplyListOf("{\"field\":\"SECTION_OFFICER_NAMES\",\"label\":\"JE\"}"))
                .schemeList(SchemeList.NO_SUPPLY)).contains(new SchemeDetail(true, false));
    }

    @Test
    void theVillagesColumnNeedsTheVillageLookup() throws Exception {
        assertThat(of(noSupplyListOf("{\"field\":\"VILLAGES\",\"label\":\"Village\"}"))
                .schemeList(SchemeList.NO_SUPPLY)).contains(new SchemeDetail(false, true));
    }

    @Test
    void aSummaryOnlyLayoutNeedsNothing() throws Exception {
        ReportDataNeeds needs = of("""
                {"sections":[{"type":"SUMMARY","title":"Summary",
                  "columns":[{"field":"LABEL","label":"KPI"}],"rows":[{"kpi":"TOTAL_SCHEMES","label":"Total"}]}]}
                """);

        assertThat(needs.schemeLists()).isEmpty();
        assertThat(needs.schemeList(SchemeList.NO_SUPPLY)).isEmpty();
        assertThat(needs.officerRows()).isFalse();
    }

    @Test
    void aListWithoutAnIdIsIgnored() throws Exception {
        assertThat(of("""
                {"sections":[{"type":"SCHEME_LIST","title":"?","columns":[{"field":"VILLAGES","label":"V"}]}]}
                """).schemeLists()).isEmpty();
    }

    @Test
    void theNeedsCannotBeChangedAfterwards() {
        ReportDataNeeds needs = builtIn(ReportKind.DAILY, "SECTION_OFFICER");

        assertThatThrownBy(() -> needs.schemeLists().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
