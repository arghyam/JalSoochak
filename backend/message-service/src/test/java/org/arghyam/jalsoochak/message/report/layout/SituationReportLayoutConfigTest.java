package org.arghyam.jalsoochak.message.report.layout;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.arghyam.jalsoochak.message.report.catalog.KpiDisplay;
import org.arghyam.jalsoochak.message.report.catalog.ReportRole;
import org.arghyam.jalsoochak.message.report.catalog.SchemeColumn;
import org.arghyam.jalsoochak.message.report.catalog.SchemeList;
import org.arghyam.jalsoochak.message.report.catalog.SummaryColumn;
import org.arghyam.jalsoochak.message.report.catalog.SummaryKpi;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Parses tenant config documents the way the stored row will be read: with a mapper that, like
 * Spring Boot's, ignores unknown properties.
 */
class SituationReportLayoutConfigTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final String CONFIG = """
            {
              "version": 1,
              "settings": {
                "dashboardUrl": "https://example.org/staff/",
                "supportPhone": "1800-000-0000",
                "dateFormats": { "date": "dd-MM-yyyy", "shortDate": "dd-MM-yyyy" },
                "numberFormat": { "decimals": 0 }
              },
              "daily": {
                "roles": {
                  "SUB_DIVISIONAL_OFFICER": { "layout": {
                    "numberSections": false,
                    "header": [
                      { "type": "TITLE", "text": "Daily Water Supply Report" },
                      { "type": "FIELD", "label": "Junior Engineer: ", "value": "{officerName}" },
                      { "type": "SPACER" },
                      { "type": "DASHBOARD_LINK", "prefix": "Dashboard: " } ],
                    "sections": [
                      { "type": "SCHEME_LIST", "list": "NO_SUPPLY", "title": "Schemes with no supply today",
                        "columns": [
                          { "field": "SERIAL_NO", "label": "S. No." },
                          { "field": "VILLAGES", "label": "Village" } ] },
                      { "type": "SUMMARY", "title": "Summary",
                        "columns": [ { "field": "LABEL", "label": "Indicator", "width": 70 },
                                     { "field": "VALUE", "label": "Today", "width": 30 } ],
                        "rows": [ { "kpi": "HOUSEHOLDS_WITHOUT_SUPPLY", "label": "Households without supply",
                                    "display": "COUNT" } ],
                        "footnotes": [ "Counts only." ] } ],
                    "footer": [
                      { "type": "NOTE", "text": "Auto generated" },
                      { "type": "SPACER" },
                      { "type": "SUPPORT_CONTACT", "text": "Helpline: {supportPhone}" } ] } },
                  "SECTION_OFFICER": { "useDefault": true }
                }
              },
              "weekly": { "roles": {} }
            }
            """;

    @Test
    void readsSettings() throws Exception {
        SituationReportLayoutConfig config = mapper.readValue(CONFIG, SituationReportLayoutConfig.class);

        assertThat(config.version()).isEqualTo(1);
        assertThat(config.settings().dashboardUrl()).isEqualTo("https://example.org/staff/");
        assertThat(config.settings().supportPhone()).isEqualTo("1800-000-0000");
        assertThat(config.settings().dateFormats()).isEqualTo(new DateFormats("dd-MM-yyyy", "dd-MM-yyyy", null));
        assertThat(config.settings().numberFormat()).isEqualTo(new NumberFormatSpec(0, null));
    }

    @Test
    void keepsTheRolesInDocumentOrder() throws Exception {
        SituationReportLayoutConfig config = mapper.readValue(CONFIG, SituationReportLayoutConfig.class);

        assertThat(config.daily().roles().keySet())
                .containsExactly(ReportRole.SUB_DIVISIONAL_OFFICER, ReportRole.SECTION_OFFICER);
        assertThat(config.daily().roles().get(ReportRole.SECTION_OFFICER)).isEqualTo(new RoleLayout(true, null));
        assertThat(config.weekly().roles()).isEmpty();
    }

    @Test
    void readsHeaderAndFooterElementsByType() throws Exception {
        ReportLayout layout = dailySdoLayout(mapper.readValue(CONFIG, SituationReportLayoutConfig.class));

        assertThat(layout.header()).containsExactly(
                new HeaderElement.Title("Daily Water Supply Report"),
                new HeaderElement.Field("Junior Engineer: ", "{officerName}"),
                new Spacer(),
                new HeaderElement.DashboardLink("Dashboard: ", ""));
        assertThat(layout.footer()).containsExactly(
                new FooterElement.Note("Auto generated"),
                new Spacer(),
                new FooterElement.SupportContact("Helpline: {supportPhone}"));
    }

    @Test
    void readsSectionsByTypeWithTheirOwnColumnCatalogues() throws Exception {
        ReportLayout layout = dailySdoLayout(mapper.readValue(CONFIG, SituationReportLayoutConfig.class));

        assertThat(layout.numberSections()).isFalse();
        assertThat(layout.sections()).hasSize(2);

        ReportSection.SchemeListSection list = (ReportSection.SchemeListSection) layout.sections().get(0);
        assertThat(list.list()).isEqualTo(SchemeList.NO_SUPPLY);
        assertThat(list.columns()).containsExactly(
                new ColumnSpec<>(SchemeColumn.SERIAL_NO, "S. No.", null),
                new ColumnSpec<>(SchemeColumn.VILLAGES, "Village", null));
        assertThat(list.emptyText()).isEqualTo(ReportSection.DEFAULT_EMPTY_TEXT);
        assertThat(list.footnotes()).isEmpty();

        ReportSection.SummarySection summary = (ReportSection.SummarySection) layout.sections().get(1);
        assertThat(summary.columns()).containsExactly(
                new ColumnSpec<>(SummaryColumn.LABEL, "Indicator", 70f),
                new ColumnSpec<>(SummaryColumn.VALUE, "Today", 30f));
        assertThat(summary.rows()).containsExactly(new SummaryRowSpec(
                SummaryKpi.HOUSEHOLDS_WITHOUT_SUPPLY, "Households without supply", KpiDisplay.COUNT));
        assertThat(summary.footnotes()).containsExactly("Counts only.");
    }

    @Test
    void numbersSectionsAndLeavesHeaderAndFooterEmptyWhenAbsent() throws Exception {
        ReportLayout layout = mapper.readValue("""
                { "sections": [ { "type": "SCHEME_LIST", "list": "NO_SUPPLY", "title": "T",
                                  "columns": [ { "field": "SCHEME_NAME", "label": "Scheme" } ] } ] }
                """, ReportLayout.class);

        assertThat(layout.numberSections()).isTrue();
        assertThat(layout.header()).isEmpty();
        assertThat(layout.footer()).isEmpty();
    }

    @Test
    void officerTableDefaultsItsEmptyText() throws Exception {
        ReportSection section = mapper.readValue("""
                { "type": "OFFICER_PERFORMANCE", "title": "T",
                  "columns": [ { "field": "OFFICER_NAME", "label": "Name" } ] }
                """, ReportSection.class);

        assertThat(((ReportSection.OfficerPerformanceSection) section).emptyText())
                .isEqualTo(ReportSection.DEFAULT_EMPTY_TEXT);
    }

    @Test
    void ignoresAnUnknownProperty() throws Exception {
        ReportLayout layout = mapper.readValue("""
                { "fromANewerTenantService": true, "header": [ { "type": "SPACER", "height": 3 } ] }
                """, ReportLayout.class);

        assertThat(layout.header()).containsExactly(new Spacer());
    }

    @Test
    void failsOnAnUnknownCatalogueValue() {
        assertThatThrownBy(() -> mapper.readValue("""
                { "kpi": "SCHEMES_ON_FIRE", "label": "x" }
                """, SummaryRowSpec.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void failsOnAnUnknownSectionType() {
        assertThatThrownBy(() -> mapper.readValue("""
                { "sections": [ { "type": "PIE_CHART", "title": "x" } ] }
                """, ReportLayout.class))
                .isInstanceOf(InvalidTypeIdException.class);
    }

    @Test
    void failsOnAFooterElementInTheHeader() {
        assertThatThrownBy(() -> mapper.readValue("""
                { "header": [ { "type": "NOTE", "text": "x" } ] }
                """, ReportLayout.class))
                .isInstanceOf(InvalidTypeIdException.class);
    }

    @Test
    void failsOnAnUnknownRole() {
        assertThatThrownBy(() -> mapper.readValue("""
                { "roles": { "JUNIOR_ENGINEER": { "useDefault": true } } }
                """, ReportBlock.class))
                .isInstanceOf(InvalidFormatException.class);
    }

    @Test
    void isImmutable() throws Exception {
        SituationReportLayoutConfig config = mapper.readValue(CONFIG, SituationReportLayoutConfig.class);
        ReportLayout layout = dailySdoLayout(config);

        assertThatThrownBy(() -> config.daily().roles().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> layout.sections().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> layout.header().add(new Spacer())).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void copiesTheListsItIsGiven() {
        List<HeaderElement> header = new ArrayList<>(List.of(new Spacer()));
        ReportLayout layout = new ReportLayout(null, header, null, null);

        header.add(new Spacer());

        assertThat(layout.header()).hasSize(1);
        assertThat(layout.sections()).isEmpty();
    }

    private static ReportLayout dailySdoLayout(SituationReportLayoutConfig config) {
        return config.daily().roles().get(ReportRole.SUB_DIVISIONAL_OFFICER).layout();
    }
}
