package org.arghyam.jalsoochak.message.report;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.message.report.catalog.ReportKind;
import org.arghyam.jalsoochak.message.report.catalog.ReportRole;
import org.arghyam.jalsoochak.message.report.catalog.SchemeList;
import org.arghyam.jalsoochak.message.report.layout.ColumnSpec;
import org.arghyam.jalsoochak.message.report.layout.FooterElement;
import org.arghyam.jalsoochak.message.report.layout.ReportLayout;
import org.arghyam.jalsoochak.message.report.layout.ReportSection;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultReportLayoutsTest {

    /** Spring Boot's mapper ignores unknown properties; the loader must not depend on that. */
    private static final ObjectMapper APP_MAPPER =
            new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final DefaultReportLayouts layouts = new DefaultReportLayouts(APP_MAPPER);

    @Nested
    class BuiltInByRole {

        @Test
        void dailySectionOfficerGetsTheDailyLayout() {
            assertThat(layouts.builtIn(ReportKind.DAILY, "SECTION_OFFICER")).contains(dailySectionOfficer());
        }

        @ParameterizedTest
        @ValueSource(strings = {"SUB_DIVISIONAL_OFFICER", " sub_divisional_officer "})
        void dailySubDivisionalOfficerHasNoBuiltInLayout(String role) {
            assertThat(layouts.builtIn(ReportKind.DAILY, role)).isEmpty();
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "JUNIOR_ENGINEER", " section_officer "})
        void anyOtherRoleGetsTheSectionOfficerLayout(String role) {
            assertThat(layouts.builtIn(ReportKind.DAILY, role)).contains(dailySectionOfficer());
            assertThat(layouts.builtIn(ReportKind.WEEKLY, role)).contains(weeklySectionOfficer());
        }

        @ParameterizedTest
        @ValueSource(strings = {"SUB_DIVISIONAL_OFFICER", " sub_divisional_officer "})
        void weeklySubDivisionalOfficerGetsTheSdoLayout(String role) {
            assertThat(layouts.builtIn(ReportKind.WEEKLY, role)).contains(weeklySubDivisionalOfficer());
        }
    }

    @Nested
    class Content {

        @Test
        void dailySectionOfficerListsNoSupplyThenAnomalies() {
            assertThat(shape(dailySectionOfficer())).containsExactly("SUMMARY", "NO_SUPPLY", "ANOMALIES");
        }

        @Test
        void weeklySectionOfficerListsSchemesByDaysSupplied() {
            assertThat(shape(weeklySectionOfficer()))
                    .containsExactly("SUMMARY", "NO_SUPPLY", "LOW_SUPPLY_DAYS", "LOW_LPCD");
        }

        @Test
        void weeklySubDivisionalOfficerLeadsWithItsOfficers() {
            assertThat(shape(weeklySubDivisionalOfficer()))
                    .containsExactly("SUMMARY", "OFFICER_PERFORMANCE", "NO_SUPPLY", "LOW_LPCD");
        }

        /**
         * The renderer turns a weight into {@code CONTENT_WIDTH * (w / sum)}. Only with a sum of 100
         * is that bit-for-bit today's {@code CONTENT_WIDTH * 0.21f}.
         */
        @Test
        void everyTableSetsWidthsThatSumToOneHundred() {
            for (ReportLayout layout : List.of(dailySectionOfficer(), weeklySectionOfficer(),
                    weeklySubDivisionalOfficer())) {
                for (ReportSection section : layout.sections()) {
                    List<? extends ColumnSpec<?>> columns = switch (section) {
                        case ReportSection.SummarySection s -> s.columns();
                        case ReportSection.SchemeListSection s -> s.columns();
                        case ReportSection.OfficerPerformanceSection s -> s.columns();
                    };
                    assertThat(columns).allSatisfy(c -> assertThat(c.width()).isNotNull());
                    assertThat(columns.stream().mapToDouble(ColumnSpec::width).sum())
                            .as(section.title()).isEqualTo(100.0);
                }
            }
        }

        @Test
        void everyBuiltInNumbersItsSectionsAndEndsWithTheSupportContact() {
            for (ReportLayout layout : List.of(dailySectionOfficer(), weeklySectionOfficer(),
                    weeklySubDivisionalOfficer())) {
                assertThat(layout.numberSections()).isTrue();
                assertThat(layout.footer()).containsExactly(
                        new FooterElement.SupportContact("For support, contact {supportPhone}"));
            }
        }
    }

    @Nested
    class Loading {

        private final ObjectMapper strict = DefaultReportLayouts.strict(APP_MAPPER);

        @Test
        void leavesTheApplicationMapperLenient() {
            assertThat(APP_MAPPER.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isFalse();
            assertThat(strict.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)).isTrue();
        }

        @Test
        void failsOnAMissingFile() {
            assertThatThrownBy(() -> load(new ClassPathResource("report-layouts/missing.json")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("report-layouts/missing.json")
                    .hasMessageContaining("cannot be read");
        }

        @Test
        void failsOnAMisspeltProperty() {
            assertThatThrownBy(() -> load(json("""
                    { "sections": [ { "type": "SCHEME_LIST", "list": "NO_SUPPLY", "title": "T",
                      "columns": [ { "field": "SCHEME_NAME", "label": "Scheme", "widht": 10 } ] } ] }
                    """)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot be read")
                    .hasMessageContaining("widht");
        }

        @Test
        void failsOnAnUnknownType() {
            assertThatThrownBy(() -> load(json("""
                    { "sections": [ { "type": "PIE_CHART", "title": "T" } ] }
                    """)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("PIE_CHART");
        }

        @Test
        void failsOnTrailingContent() {
            assertThatThrownBy(() -> load(json("{ } { }")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("cannot be read");
        }

        @Test
        void failsOnAnEmptyDocument() {
            assertThatThrownBy(() -> load(json("null")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("layout: is required");
        }

        @Test
        void failsWithEverySanityProblem() {
            assertThatThrownBy(() -> load(json("""
                    { "sections": [ { "type": "SCHEME_LIST", "list": "LOW_LPCD", "title": "",
                      "columns": [ { "field": "SCHEME_NAME", "label": "Scheme" } ] } ] }
                    """)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("is invalid for DAILY SECTION_OFFICER")
                    .hasMessageContaining("sections[0].list: 'LOW_LPCD' is not available in DAILY reports; "
                            + "sections[0].title: is required");
        }

        @Test
        void loadsAValidLayout() {
            ReportLayout layout = load(json("""
                    { "sections": [ { "type": "SCHEME_LIST", "list": "NO_SUPPLY", "title": "T",
                      "columns": [ { "field": "SCHEME_NAME", "label": "Scheme" } ] } ] }
                    """));

            assertThat(((ReportSection.SchemeListSection) layout.sections().get(0)).list())
                    .isEqualTo(SchemeList.NO_SUPPLY);
        }

        private ReportLayout load(Resource resource) {
            return DefaultReportLayouts.load(strict, resource, ReportKind.DAILY, ReportRole.SECTION_OFFICER);
        }

        private static ByteArrayResource json(String json) {
            return new ByteArrayResource(json.getBytes(StandardCharsets.UTF_8), "test layout");
        }
    }

    private ReportLayout dailySectionOfficer() {
        return DefaultReportLayouts.load(DefaultReportLayouts.strict(APP_MAPPER),
                new ClassPathResource(DefaultReportLayouts.DAILY_SECTION_OFFICER),
                ReportKind.DAILY, ReportRole.SECTION_OFFICER);
    }

    private ReportLayout weeklySectionOfficer() {
        return DefaultReportLayouts.load(DefaultReportLayouts.strict(APP_MAPPER),
                new ClassPathResource(DefaultReportLayouts.WEEKLY_SECTION_OFFICER),
                ReportKind.WEEKLY, ReportRole.SECTION_OFFICER);
    }

    private ReportLayout weeklySubDivisionalOfficer() {
        return DefaultReportLayouts.load(DefaultReportLayouts.strict(APP_MAPPER),
                new ClassPathResource(DefaultReportLayouts.WEEKLY_SUB_DIVISIONAL_OFFICER),
                ReportKind.WEEKLY, ReportRole.SUB_DIVISIONAL_OFFICER);
    }

    /** Each section as its type, or a scheme list as its list id. */
    private static List<String> shape(ReportLayout layout) {
        return layout.sections().stream().map(section -> switch (section) {
            case ReportSection.SummarySection s -> "SUMMARY";
            case ReportSection.SchemeListSection s -> s.list().name();
            case ReportSection.OfficerPerformanceSection s -> "OFFICER_PERFORMANCE";
        }).toList();
    }
}
