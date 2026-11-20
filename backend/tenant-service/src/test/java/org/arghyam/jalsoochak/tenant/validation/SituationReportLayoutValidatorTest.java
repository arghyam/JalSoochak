package org.arghyam.jalsoochak.tenant.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;

import org.arghyam.jalsoochak.tenant.dto.internal.SituationReportLayoutConfigDTO;
import org.arghyam.jalsoochak.tenant.exception.InvalidConfigValueException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Unit tests for {@link SituationReportLayoutValidator}, one group per rule of the plan's §5 (V1–V11).
 *
 * <p>Uses the mapper Spring Boot builds, which ignores unknown properties: the validator must find
 * them itself, as it has to in production.</p>
 */
@DisplayName("SituationReportLayoutValidator")
class SituationReportLayoutValidatorTest {

    private static final String DAILY_SO = "/daily/roles/SECTION_OFFICER/layout";
    private static final String DAILY_SO_PATH = "daily.roles.SECTION_OFFICER.layout";
    private static final String WEEKLY_SDO = "/weekly/roles/SUB_DIVISIONAL_OFFICER/layout";
    private static final String WEEKLY_SDO_PATH = "weekly.roles.SUB_DIVISIONAL_OFFICER.layout";

    private final ObjectMapper objectMapper = Jackson2ObjectMapperBuilder.json().build();
    private final SituationReportLayoutValidator validator = new SituationReportLayoutValidator(objectMapper);

    /** Valid: a daily SO layout with one summary, and a weekly SDO layout with a summary and an officer table. */
    private ObjectNode validDocument() {
        return (ObjectNode) read("""
                {
                  "version": 1,
                  "daily": { "roles": { "SECTION_OFFICER": { "layout": {
                    "header": [ { "type": "TITLE", "text": "Daily report" } ],
                    "sections": [
                      { "type": "SUMMARY", "title": "Summary",
                        "columns": [ { "field": "LABEL", "label": "KPI" }, { "field": "VALUE", "label": "Today" } ],
                        "rows": [ { "kpi": "TOTAL_SCHEMES", "label": "Total schemes" } ] },
                      { "type": "SCHEME_LIST", "list": "NO_SUPPLY", "title": "No supply",
                        "columns": [ { "field": "SCHEME_NAME", "label": "Scheme" } ] } ],
                    "footer": [ { "type": "SUPPORT_CONTACT", "text": "Call {supportPhone}" } ] } } } },
                  "weekly": { "roles": { "SUB_DIVISIONAL_OFFICER": { "layout": {
                    "sections": [
                      { "type": "SUMMARY", "title": "Summary",
                        "columns": [ { "field": "LABEL", "label": "KPI" },
                                     { "field": "CURRENT", "label": "{weekStart}" } ],
                        "rows": [ { "kpi": "SCHEMES_LOW_LPCD", "label": "Low LPCD" } ] },
                      { "type": "OFFICER_PERFORMANCE", "title": "Officers",
                        "columns": [ { "field": "OFFICER_NAME", "label": "Name" } ] } ] } } } }
                }
                """);
    }

    private List<String> problems(JsonNode raw) {
        SituationReportLayoutConfigDTO config;
        try {
            config = objectMapper.treeToValue(raw, SituationReportLayoutConfigDTO.class);
        } catch (IOException e) {
            throw new AssertionError("fixture does not bind", e);
        }
        return validator.problems(raw, config);
    }

    private List<String> problemsAfter(Consumer<ObjectNode> change) {
        ObjectNode doc = validDocument();
        change.accept(doc);
        return problems(doc);
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static ObjectNode at(ObjectNode doc, String pointer) {
        return (ObjectNode) doc.at(pointer);
    }

    private static ArrayNode array(ObjectNode doc, String pointer) {
        return (ArrayNode) doc.at(pointer);
    }

    @Nested
    @DisplayName("valid documents")
    class Valid {

        @Test
        @DisplayName("the fixture has no problems")
        void fixture() {
            assertThat(problems(validDocument())).isEmpty();
        }

        @Test
        @DisplayName("{\"version\": 1} alone resets both reports to the defaults")
        void versionOnly() {
            assertThat(problems(read("{\"version\":1}"))).isEmpty();
        }

        @Test
        @DisplayName("an empty roles object sends the report to nobody")
        void emptyRoles() {
            assertThat(problems(read("{\"version\":1,\"daily\":{\"roles\":{}}}"))).isEmpty();
        }

        @ParameterizedTest(name = "{0} as {1}.{2}")
        @CsvSource({
                "daily-section-officer.json, daily, SECTION_OFFICER",
                "weekly-section-officer.json, weekly, SECTION_OFFICER",
                "weekly-sub-divisional-officer.json, weekly, SUB_DIVISIONAL_OFFICER"
        })
        @DisplayName("each built-in layout validates, so ops can copy and edit it")
        void builtInLayouts(String file, String report, String role) throws IOException {
            ObjectNode doc = (ObjectNode) read("{\"version\":1}");
            doc.putObject(report).putObject("roles").putObject(role).set("layout", resource(file));

            assertThat(problems(doc)).isEmpty();
        }

        @Test
        @DisplayName("the custom sample, with every setting and a daily SDO layout, validates")
        void customSample() throws IOException {
            assertThat(problems(resource("custom-layout-sample.json"))).isEmpty();
        }

        @Test
        @DisplayName("Latin-1 letters, dashes, quotes, ≤ ≥ and ₹ are printable")
        void allowedCharacters() {
            assertThat(problemsAfter(doc -> at(doc, DAILY_SO + "/header/0")
                    .put("text", "Café – “Report” — ≤ 15 ≥ 4 ₹ 10 … •"))).isEmpty();
        }

        private JsonNode resource(String file) throws IOException {
            try (InputStream in = getClass().getResourceAsStream("/report-layouts/" + file)) {
                assertThat(in).as(file).isNotNull();
                return objectMapper.readTree(in);
            }
        }
    }

    @Test
    @DisplayName("validate lists every problem in one exception")
    void validateAggregates() {
        ObjectNode doc = validDocument();
        doc.put("version", 2);
        at(doc, DAILY_SO + "/sections/0").put("title", " ");
        at(doc, WEEKLY_SDO + "/sections/0/rows/0").put("kpi", "HOUSEHOLDS_WITH_SUPPLY");
        SituationReportLayoutConfigDTO config = objectMapper.convertValue(doc, SituationReportLayoutConfigDTO.class);

        assertThatThrownBy(() -> validator.validate(doc, config))
                .isInstanceOf(InvalidConfigValueException.class)
                .hasMessageStartingWith("SITUATION_REPORT_LAYOUT has 3 problems: ")
                .hasMessageContaining("version: must be 1")
                .hasMessageContaining(DAILY_SO_PATH + ".sections[0].title: is required")
                .hasMessageContaining(WEEKLY_SDO_PATH + ".sections[0].rows[0].kpi: 'HOUSEHOLDS_WITH_SUPPLY' is not"
                        + " available in WEEKLY reports (allowed: TOTAL_SCHEMES, SCHEMES_SUPPLYING,"
                        + " SCHEMES_NOT_SUPPLYING, SCHEMES_LOW_LPCD, AVG_LPCD)");
    }

    @Test
    @DisplayName("validate passes a valid document")
    void validatePasses() {
        ObjectNode doc = validDocument();
        SituationReportLayoutConfigDTO config = objectMapper.convertValue(doc, SituationReportLayoutConfigDTO.class);

        assertThatCode(() -> validator.validate(doc, config)).doesNotThrowAnyException();
    }

    @Nested
    @DisplayName("V1 version and unknown properties")
    class Version {

        @Test
        void missingVersion() {
            assertThat(problemsAfter(doc -> doc.remove("version"))).containsExactly("version: is required");
        }

        @Test
        void otherVersion() {
            assertThat(problemsAfter(doc -> doc.put("version", 2))).containsExactly("version: must be 1");
        }

        @Test
        @DisplayName("every unknown property is reported with its path, also where 'type' comes last")
        void unknownProperties() {
            JsonNode doc = read("""
                    {
                      "version": 1, "colour": "red",
                      "settings": { "dateFormats": { "dat": "dd" } },
                      "daily": { "roles": { "SECTION_OFFICER": { "useDefualt": true, "layout": {
                        "header": [ { "text": "T", "size": 12, "type": "TITLE" } ],
                        "sections": [
                          { "title": "S", "list": "NO_SUPPLY",
                            "columns": [ { "field": "SCHEME_NAME", "label": "Scheme", "align": "left" } ],
                            "type": "SCHEME_LIST" } ] } } } }
                    }
                    """);

            // Jackson reports an object's own unknown properties after those of the objects inside it.
            assertThat(problems(doc)).containsExactlyInAnyOrder(
                    "colour: unknown property",
                    "settings.dateFormats.dat: unknown property",
                    "daily.roles.SECTION_OFFICER.useDefualt: unknown property",
                    "daily.roles.SECTION_OFFICER.layout.header[0].size: unknown property",
                    "daily.roles.SECTION_OFFICER.layout.sections[0].columns[0].align: unknown property");
        }

        @Test
        @DisplayName("an unknown property whose value is an object or array keeps its own path")
        void unknownStructuredProperty() {
            assertThat(problemsAfter(doc -> {
                at(doc, DAILY_SO).putObject("theme").put("font", "x");
                at(doc, DAILY_SO).putArray("extras").add(1);
            })).containsExactly(
                    DAILY_SO_PATH + ".theme: unknown property",
                    DAILY_SO_PATH + ".extras: unknown property");
        }

        @Test
        @DisplayName("at most 50 unknown properties are listed, each costing a re-parse")
        void unknownPropertiesCapped() {
            assertThat(problemsAfter(doc -> {
                for (int i = 0; i < 60; i++) {
                    doc.put("extra" + i, i);
                }
            })).hasSize(SituationReportLayoutValidator.MAX_UNKNOWN_PROPERTIES_REPORTED)
                    .allMatch(problem -> problem.endsWith(": unknown property"));
        }
    }

    @Nested
    @DisplayName("V2 stored size")
    class Size {

        @Test
        @DisplayName("a document over 64 KB as stored is refused")
        void tooLarge() {
            List<String> problems = problemsAfter(doc -> {
                ArrayNode footnotes = at(doc, DAILY_SO + "/sections/0").putArray("footnotes");
                for (int i = 0; i < 240; i++) {
                    footnotes.add("x".repeat(280));
                }
            });

            assertThat(problems).singleElement().asString()
                    .startsWith("(document): must be at most 65536 bytes as stored, but is ");
        }
    }

    @Nested
    @DisplayName("V3 report blocks")
    class Blocks {

        @Test
        void rolesRequired() {
            assertThat(problems(read("{\"version\":1,\"weekly\":{}}")))
                    .containsExactly("weekly.roles: is required; use {} to send the report to nobody");
        }
    }

    @Nested
    @DisplayName("V4 useDefault or layout")
    class RoleEntries {

        @ParameterizedTest
        @CsvSource({"daily, SECTION_OFFICER", "weekly, SECTION_OFFICER", "weekly, SUB_DIVISIONAL_OFFICER"})
        @DisplayName("useDefault is allowed where a built-in layout exists")
        void useDefaultAllowed(String report, String role) {
            assertThat(problems(read("{\"version\":1,\"" + report + "\":{\"roles\":{\"" + role
                    + "\":{\"useDefault\":true}}}}"))).isEmpty();
        }

        @Test
        @DisplayName("useDefault is refused for a daily SDO report, which has no built-in layout")
        void noDailySdoDefault() {
            assertThat(problems(read("{\"version\":1,\"daily\":{\"roles\":{\"SUB_DIVISIONAL_OFFICER\":"
                    + "{\"useDefault\":true}}}}"))).containsExactly("daily.roles.SUB_DIVISIONAL_OFFICER.useDefault:"
                    + " there is no built-in DAILY layout for SUB_DIVISIONAL_OFFICER; give a layout");
        }

        @ParameterizedTest
        @ValueSource(strings = {"{}", "{\"useDefault\":false}", "null"})
        @DisplayName("an entry with neither is refused")
        void neither(String entry) {
            assertThat(problems(read("{\"version\":1,\"daily\":{\"roles\":{\"SECTION_OFFICER\":" + entry + "}}}")))
                    .containsExactly("daily.roles.SECTION_OFFICER: must set exactly one of \"useDefault\": true"
                            + " or a \"layout\"");
        }

        @Test
        @DisplayName("an entry with both is refused")
        void both() {
            assertThat(problemsAfter(doc -> at(doc, "/daily/roles/SECTION_OFFICER").put("useDefault", true)))
                    .containsExactly("daily.roles.SECTION_OFFICER: must set exactly one of \"useDefault\": true"
                            + " or a \"layout\"");
        }

        @Test
        @DisplayName("useDefault false next to a layout is the layout")
        void useDefaultFalseWithLayout() {
            assertThat(problemsAfter(doc -> at(doc, "/daily/roles/SECTION_OFFICER").put("useDefault", false)))
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("V5 element counts")
    class Counts {

        @Test
        void tooManyHeaderElements() {
            assertThat(problemsAfter(doc -> {
                ArrayNode header = array(doc, DAILY_SO + "/header");
                for (int i = 0; i < 15; i++) {
                    header.addObject().put("type", "SPACER");
                }
            })).containsExactly(DAILY_SO_PATH + ".header: at most 15 elements are allowed");
        }

        @Test
        void tooManyFooterElements() {
            assertThat(problemsAfter(doc -> {
                ArrayNode footer = array(doc, DAILY_SO + "/footer");
                for (int i = 0; i < 5; i++) {
                    footer.addObject().put("type", "SPACER");
                }
            })).containsExactly(DAILY_SO_PATH + ".footer: at most 5 elements are allowed");
        }

        @Test
        void noSections() {
            assertThat(problemsAfter(doc -> at(doc, DAILY_SO).remove("sections")))
                    .containsExactly(DAILY_SO_PATH + ".sections: must have 1 to 10 sections");
        }

        @Test
        void tooManySections() {
            assertThat(problemsAfter(doc -> {
                ArrayNode sections = array(doc, DAILY_SO + "/sections");
                for (int i = 0; i < 9; i++) {
                    sections.addObject().put("type", "SCHEME_LIST").put("list", i % 2 == 0 ? "ANOMALIES" : "NO_SUPPLY")
                            .put("title", "T").putArray("columns").addObject()
                            .put("field", "SCHEME_NAME").put("label", "S");
                }
            })).contains(DAILY_SO_PATH + ".sections: must have 1 to 10 sections");
        }

        @Test
        @DisplayName("a null element is reported rather than failing the bind")
        void nullElements() {
            assertThat(problemsAfter(doc -> {
                array(doc, DAILY_SO + "/header").addNull();
                array(doc, DAILY_SO + "/sections").addNull();
                array(doc, DAILY_SO + "/footer").addNull();
                array(doc, DAILY_SO + "/sections/0/rows").addNull();
                array(doc, DAILY_SO + "/sections/1/columns").addNull();
            })).containsExactly(
                    DAILY_SO_PATH + ".header[1]: must not be null",
                    DAILY_SO_PATH + ".sections[0].rows[1]: must not be null",
                    DAILY_SO_PATH + ".sections[1].columns[1]: must not be null",
                    DAILY_SO_PATH + ".sections[2]: must not be null",
                    DAILY_SO_PATH + ".footer[1]: must not be null");
        }
    }

    @Nested
    @DisplayName("V6 sections")
    class Sections {

        @Test
        void secondSummary() {
            assertThat(problemsAfter(doc -> array(doc, DAILY_SO + "/sections")
                    .add(doc.at(DAILY_SO + "/sections/0").deepCopy())))
                    .containsExactly(DAILY_SO_PATH + ".sections[2]: only one SUMMARY section is allowed");
        }

        @Test
        @DisplayName("an officer table is refused in a daily report")
        void officerTableInDaily() {
            assertThat(problemsAfter(doc -> array(doc, DAILY_SO + "/sections")
                    .add(doc.at(WEEKLY_SDO + "/sections/1").deepCopy())))
                    .containsExactly(DAILY_SO_PATH + ".sections[2]: OFFICER_PERFORMANCE is only available in"
                            + " WEEKLY reports for SUB_DIVISIONAL_OFFICER");
        }

        @Test
        @DisplayName("an officer table is refused in a weekly Section Officer report")
        void officerTableInWeeklySo() {
            assertThat(problemsAfter(doc -> {
                ObjectNode roles = at(doc, "/weekly/roles");
                roles.set("SECTION_OFFICER", roles.remove("SUB_DIVISIONAL_OFFICER"));
            })).containsExactly("weekly.roles.SECTION_OFFICER.layout.sections[1]: OFFICER_PERFORMANCE is only"
                    + " available in WEEKLY reports for SUB_DIVISIONAL_OFFICER");
        }

        @Test
        void secondOfficerTable() {
            assertThat(problemsAfter(doc -> array(doc, WEEKLY_SDO + "/sections")
                    .add(doc.at(WEEKLY_SDO + "/sections/1").deepCopy())))
                    .containsExactly(WEEKLY_SDO_PATH + ".sections[2]: only one OFFICER_PERFORMANCE section is allowed");
        }

        @Test
        void listNotInReport() {
            assertThat(problemsAfter(doc -> at(doc, DAILY_SO + "/sections/1").put("list", "LOW_LPCD")))
                    .containsExactly(DAILY_SO_PATH + ".sections[1].list: 'LOW_LPCD' is not available in DAILY"
                            + " reports (allowed: NO_SUPPLY, ANOMALIES)");
        }

        @Test
        void listTwice() {
            assertThat(problemsAfter(doc -> array(doc, DAILY_SO + "/sections")
                    .add(doc.at(DAILY_SO + "/sections/1").deepCopy())))
                    .containsExactly(DAILY_SO_PATH + ".sections[2].list: 'NO_SUPPLY' is already shown");
        }

        @Test
        void listRequired() {
            assertThat(problemsAfter(doc -> at(doc, DAILY_SO + "/sections/1").remove("list")))
                    .containsExactly(DAILY_SO_PATH + ".sections[1].list: is required");
        }
    }

    @Nested
    @DisplayName("V7 summary")
    class Summary {

        private static final String SUMMARY = DAILY_SO + "/sections/0";
        private static final String SUMMARY_PATH = DAILY_SO_PATH + ".sections[0]";

        @Test
        void tooFewColumns() {
            assertThat(problemsAfter(doc -> array(doc, SUMMARY + "/columns").remove(1)))
                    .containsExactly(SUMMARY_PATH + ".columns: must have 2 to 4 columns");
        }

        @Test
        void tooManyColumns() {
            assertThat(problemsAfter(doc -> {
                ArrayNode columns = array(doc, SUMMARY + "/columns");
                for (int i = 0; i < 3; i++) {
                    columns.addObject().put("field", "VALUE").put("label", "V" + i);
                }
            })).contains(SUMMARY_PATH + ".columns: must have 2 to 4 columns");
        }

        @Test
        void firstColumnIsLabel() {
            assertThat(problemsAfter(doc -> {
                at(doc, SUMMARY + "/columns/0").put("field", "VALUE");
                at(doc, SUMMARY + "/columns/1").put("field", "LABEL");
            })).containsExactly(SUMMARY_PATH + ".columns[0].field: must be LABEL");
        }

        @Test
        void columnNotInReport() {
            assertThat(problemsAfter(doc -> at(doc, SUMMARY + "/columns/1").put("field", "TREND")))
                    .containsExactly(SUMMARY_PATH + ".columns[1].field: 'TREND' is not available in DAILY reports"
                            + " (allowed: LABEL, VALUE)");
        }

        @Test
        void duplicateColumn() {
            assertThat(problemsAfter(doc -> array(doc, SUMMARY + "/columns").addObject()
                    .put("field", "VALUE").put("label", "Again")))
                    .containsExactly(SUMMARY_PATH + ".columns[2].field: 'VALUE' is already shown");
        }

        @Test
        void noRows() {
            assertThat(problemsAfter(doc -> array(doc, SUMMARY + "/rows").removeAll()))
                    .containsExactly(SUMMARY_PATH + ".rows: must have 1 to 12 rows");
        }

        @Test
        void tooManyRows() {
            assertThat(problemsAfter(doc -> {
                ArrayNode rows = array(doc, SUMMARY + "/rows");
                for (int i = 0; i < 12; i++) {
                    rows.addObject().put("kpi", "AVG_LPCD").put("label", "Row " + i);
                }
            })).contains(SUMMARY_PATH + ".rows: must have 1 to 12 rows");
        }

        @Test
        void kpiRequired() {
            assertThat(problemsAfter(doc -> at(doc, SUMMARY + "/rows/0").remove("kpi")))
                    .containsExactly(SUMMARY_PATH + ".rows[0].kpi: is required");
        }

        @Test
        void kpiNotInReport() {
            assertThat(problemsAfter(doc -> at(doc, SUMMARY + "/rows/0").put("kpi", "SCHEMES_LOW_LPCD")))
                    .containsExactly(SUMMARY_PATH + ".rows[0].kpi: 'SCHEMES_LOW_LPCD' is not available in DAILY"
                            + " reports (allowed: TOTAL_SCHEMES, SCHEMES_SUPPLYING, SCHEMES_NOT_SUPPLYING,"
                            + " HOUSEHOLDS_WITH_SUPPLY, HOUSEHOLDS_WITHOUT_SUPPLY, TOTAL_HOUSEHOLDS, AVG_LPCD,"
                            + " ANOMALOUS_SUBMISSIONS)");
        }

        @Test
        void duplicateKpi() {
            assertThat(problemsAfter(doc -> array(doc, SUMMARY + "/rows").addObject()
                    .put("kpi", "TOTAL_SCHEMES").put("label", "Again")))
                    .containsExactly(SUMMARY_PATH + ".rows[1].kpi: 'TOTAL_SCHEMES' is already shown");
        }

        @Test
        @DisplayName("display is allowed on a household KPI only")
        void display() {
            assertThat(problemsAfter(doc -> {
                at(doc, SUMMARY + "/rows/0").put("display", "COUNT");
                array(doc, SUMMARY + "/rows").addObject().put("kpi", "HOUSEHOLDS_WITH_SUPPLY")
                        .put("label", "HHs").put("display", "PERCENT");
            })).containsExactly(SUMMARY_PATH + ".rows[0].display: only applies to a KPI with a percentage"
                    + " (HOUSEHOLDS_WITH_SUPPLY, HOUSEHOLDS_WITHOUT_SUPPLY)");
        }

        @Test
        @DisplayName("labels are required and unique, since the trend colouring finds rows by label")
        void labels() {
            assertThat(problemsAfter(doc -> {
                array(doc, SUMMARY + "/rows").addObject().put("kpi", "AVG_LPCD").put("label", "Total schemes");
                array(doc, SUMMARY + "/rows").addObject().put("kpi", "TOTAL_HOUSEHOLDS").put("label", "");
            })).containsExactly(
                    SUMMARY_PATH + ".rows[1].label: 'Total schemes' is already used in this summary",
                    SUMMARY_PATH + ".rows[2].label: is required");
        }
    }

    @Nested
    @DisplayName("V8 table columns")
    class Columns {

        private static final String LIST = DAILY_SO + "/sections/1";
        private static final String LIST_PATH = DAILY_SO_PATH + ".sections[1]";

        @Test
        void noColumns() {
            assertThat(problemsAfter(doc -> array(doc, LIST + "/columns").removeAll()))
                    .containsExactly(LIST_PATH + ".columns: must have 1 to 10 columns");
        }

        @Test
        void tooManyColumns() {
            assertThat(problemsAfter(doc -> {
                ArrayNode columns = array(doc, LIST + "/columns");
                for (int i = 0; i < 10; i++) {
                    columns.addObject().put("field", "VILLAGES").put("label", "V" + i);
                }
            })).contains(LIST_PATH + ".columns: must have 1 to 10 columns");
        }

        @Test
        void anomalyTypeOutsideAnomalies() {
            assertThat(problemsAfter(doc -> array(doc, LIST + "/columns").addObject()
                    .put("field", "ANOMALY_TYPE").put("label", "Anomaly")))
                    .containsExactly(LIST_PATH + ".columns[1].field: 'ANOMALY_TYPE' is only available in the"
                            + " ANOMALIES list");
        }

        @Test
        void duplicateField() {
            assertThat(problemsAfter(doc -> array(doc, LIST + "/columns").addObject()
                    .put("field", "SCHEME_NAME").put("label", "Again")))
                    .containsExactly(LIST_PATH + ".columns[1].field: 'SCHEME_NAME' is already shown");
        }

        @Test
        void fieldAndLabelRequired() {
            assertThat(problemsAfter(doc -> array(doc, LIST + "/columns").addObject()))
                    .containsExactly(LIST_PATH + ".columns[1].field: is required",
                            LIST_PATH + ".columns[1].label: is required");
        }

        @Test
        void widthsAllOrNone() {
            assertThat(problemsAfter(doc -> {
                at(doc, LIST + "/columns/0").put("width", 60);
                array(doc, LIST + "/columns").addObject().put("field", "VILLAGES").put("label", "Village");
            })).containsExactly(LIST_PATH + ".columns: width must be set on every column or on none");
        }

        @ParameterizedTest
        @ValueSource(doubles = {0, -5, 100.5})
        void widthRange(double width) {
            assertThat(problemsAfter(doc -> at(doc, LIST + "/columns/0").put("width", width)))
                    .containsExactly(LIST_PATH + ".columns[0].width: must be greater than 0 and at most 100");
        }

        @Test
        void officerTableColumns() {
            assertThat(problemsAfter(doc -> array(doc, WEEKLY_SDO + "/sections/1/columns").addObject()
                    .put("field", "OFFICER_NAME").put("label", "Again")))
                    .containsExactly(WEEKLY_SDO_PATH
                            + ".sections[1].columns[1].field: 'OFFICER_NAME' is already shown");
        }
    }

    @Nested
    @DisplayName("V9 text")
    class Text {

        private static final String TITLE = DAILY_SO + "/header/0";
        private static final String TITLE_PATH = DAILY_SO_PATH + ".header[0].text";

        @Test
        @DisplayName("required text is non-blank")
        void requiredText() {
            assertThat(problemsAfter(doc -> {
                at(doc, TITLE).put("text", "  ");
                array(doc, DAILY_SO + "/header").addObject().put("type", "FIELD").put("label", "Date: ");
                array(doc, DAILY_SO + "/footer").addObject().put("type", "NOTE");
                at(doc, DAILY_SO + "/sections/1").remove("title");
            })).containsExactly(
                    TITLE_PATH + ": is required",
                    DAILY_SO_PATH + ".header[1].value: is required",
                    DAILY_SO_PATH + ".sections[1].title: is required",
                    DAILY_SO_PATH + ".footer[1].text: is required");
        }

        @Test
        @DisplayName("optional text may be blank")
        void optionalText() {
            assertThat(problemsAfter(doc -> {
                array(doc, DAILY_SO + "/header").addObject().put("type", "DASHBOARD_LINK").put("suffix", "");
                at(doc, DAILY_SO + "/sections/1").put("emptyText", "");
            })).isEmpty();
        }

        @Test
        void length() {
            assertThat(problemsAfter(doc -> {
                at(doc, TITLE).put("text", "x".repeat(200));
                at(doc, DAILY_SO + "/sections/1").put("title", "x".repeat(201));
            })).containsExactly(DAILY_SO_PATH + ".sections[1].title: must be at most 200 characters");
        }

        @Test
        @DisplayName("footnotes may be up to 300 characters")
        void footnoteLength() {
            assertThat(problemsAfter(doc -> at(doc, DAILY_SO + "/sections/0").putArray("footnotes")
                    .add("x".repeat(300)).add("y".repeat(301))))
                    .containsExactly(DAILY_SO_PATH + ".sections[0].footnotes[1]: must be at most 300 characters");
        }

        @Test
        @DisplayName("a script the report font can't shape is refused")
        void unprintableScript() {
            assertThat(problemsAfter(doc -> at(doc, TITLE).put("text", "ਰਿਪੋਰਟ")))
                    .containsExactly(TITLE_PATH + ": 'ਰ' (U+0A30) can't be printed in a report; use Latin letters,"
                            + " digits and common punctuation");
        }

        @ParameterizedTest
        @CsvSource({"'Line\nbreak', U+000A", "'Tab\there', U+0009", "'C1\u0085control', U+0085",
                "'Line\u2028separator', U+2028", "'Zero\u200Bwidth', U+200B"})
        @DisplayName("control and invisible characters are refused, named by code only")
        void controlCharacters(String text, String code) {
            assertThat(problemsAfter(doc -> at(doc, TITLE).put("text", text)))
                    .containsExactly(TITLE_PATH + ": " + code + " can't be printed in a report; use Latin letters,"
                            + " digits and common punctuation");
        }
    }

    @Nested
    @DisplayName("V10 placeholders")
    class Placeholders {

        private static final String TITLE = DAILY_SO + "/header/0";
        private static final String TITLE_PATH = DAILY_SO_PATH + ".header[0].text";

        @ParameterizedTest
        @ValueSource(strings = {"Report {officerName", "Report officerName}", "Report {{officerName}}",
                "Report {officer name}"})
        void malformedBraces(String text) {
            assertThat(problemsAfter(doc -> at(doc, TITLE).put("text", text)))
                    .contains(TITLE_PATH + ": has an unmatched brace or a malformed placeholder");
        }

        @Test
        void unknownPlaceholder() {
            assertThat(problemsAfter(doc -> at(doc, TITLE).put("text", "Report for {district}")))
                    .containsExactly(TITLE_PATH + ": {district} is not a placeholder of DAILY reports (allowed:"
                            + " {officerName}, {generatedDate}, {reportDate}, {cutoff}, {supportPhone})");
        }

        @Test
        @DisplayName("a weekly placeholder is refused in a daily report and vice versa")
        void otherReportsPlaceholder() {
            assertThat(problemsAfter(doc -> {
                at(doc, TITLE).put("text", "Week of {weekStart}");
                at(doc, WEEKLY_SDO + "/sections/0").put("title", "Up to {cutoff}");
            })).containsExactly(
                    TITLE_PATH + ": {weekStart} is not a placeholder of DAILY reports (allowed: {officerName},"
                            + " {generatedDate}, {reportDate}, {cutoff}, {supportPhone})",
                    WEEKLY_SDO_PATH + ".sections[0].title: {cutoff} is not a placeholder of WEEKLY reports"
                            + " (allowed: {officerName}, {generatedDate}, {weekStart}, {weekEnd},"
                            + " {previousWeekStart}, {previousWeekEnd}, {weekStartDayName}, {weekEndDayName},"
                            + " {lowLpcdThreshold}, {minSupplyDays}, {supportPhone})");
        }

        @Test
        @DisplayName("{supportPhone} is only allowed in the SUPPORT_CONTACT footer")
        void supportPhoneOutsideFooter() {
            assertThat(problemsAfter(doc -> at(doc, TITLE).put("text", "Call {supportPhone}")))
                    .containsExactly(TITLE_PATH + ": {supportPhone} is only allowed in a SUPPORT_CONTACT footer");
        }

        @Test
        @DisplayName("the SUPPORT_CONTACT footer must contain {supportPhone}")
        void supportContactWithoutPhone() {
            assertThat(problemsAfter(doc -> at(doc, DAILY_SO + "/footer/0").put("text", "Call the helpline")))
                    .containsExactly(DAILY_SO_PATH + ".footer[0].text: must contain {supportPhone}");
        }
    }

    @Nested
    @DisplayName("V11 settings")
    class Settings {

        private List<String> problemsWithSettings(String settings) {
            return problems(read("{\"version\":1,\"settings\":" + settings + "}"));
        }

        @Test
        void validSettings() {
            assertThat(problemsWithSettings("""
                    { "dashboardUrl": "https://example.org/staff/?a=1", "supportPhone": "+91 (0) 1800-000",
                      "dateFormats": { "date": "dd MMMM yyyy", "shortDate": "dd-MMM-yyyy", "time": "hh:mm a" },
                      "numberFormat": { "decimals": 0, "trimTrailingZeros": false } }
                    """)).isEmpty();
        }

        @ParameterizedTest
        @ValueSource(strings = {"http://example.org", "javascript:alert(1)", "/staff/", "https:///path",
                "https://exa mple.org", "https://example.org/ਰ", ""})
        void dashboardUrl(String url) {
            assertThat(problemsWithSettings("{\"dashboardUrl\":\"" + url + "\"}"))
                    .containsExactly("settings.dashboardUrl: must be an absolute https:// URL with a host,"
                            + " at most 300 characters");
        }

        @Test
        void dashboardUrlLength() {
            assertThat(problemsWithSettings("{\"dashboardUrl\":\"https://example.org/" + "a".repeat(281) + "\"}"))
                    .hasSize(1);
        }

        @ParameterizedTest
        @ValueSource(strings = {"18OO-000", "12", "   ", "123456789012345678901", "1800#000"})
        void supportPhone(String phone) {
            assertThat(problemsWithSettings("{\"supportPhone\":\"" + phone + "\"}"))
                    .containsExactly("settings.supportPhone: must be 3 to 20 digits, spaces and + ( ) -");
        }

        @Test
        @DisplayName("a date pattern must format a date, and a time pattern a time")
        void datePatterns() {
            assertThat(problemsWithSettings("""
                    { "dateFormats": { "date": "HH:mm", "shortDate": "dd-MM-yyyy {", "time": "dd" } }
                    """)).satisfiesExactly(
                    p -> assertThat(p).startsWith("settings.dateFormats.date: can't format a date ("),
                    p -> assertThat(p).startsWith("settings.dateFormats.shortDate: is not a valid date-time pattern: "),
                    p -> assertThat(p).startsWith("settings.dateFormats.time: can't format a time ("));
        }

        @Test
        void blankAndLongPatterns() {
            assertThat(problemsWithSettings("{\"dateFormats\":{\"date\":\" \",\"time\":\"" + "H".repeat(41) + "\"}}"))
                    .containsExactly(
                            "settings.dateFormats.date: must not be blank; leave it out for the built-in format",
                            "settings.dateFormats.time: must be at most 40 characters");
        }

        @Test
        @DisplayName("a pattern whose literal text can't be printed is refused")
        void unprintablePatternOutput() {
            assertThat(problemsWithSettings("{\"dateFormats\":{\"date\":\"dd 'ਤਾਰੀਖ'\"}}"))
                    .containsExactly("settings.dateFormats.date: prints 'ਤ' (U+0A24), which can't be printed in a"
                            + " report");
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, 2})
        void decimals(int decimals) {
            assertThat(problemsWithSettings("{\"numberFormat\":{\"decimals\":" + decimals + "}}"))
                    .containsExactly("settings.numberFormat.decimals: must be 0 or 1");
        }
    }
}
