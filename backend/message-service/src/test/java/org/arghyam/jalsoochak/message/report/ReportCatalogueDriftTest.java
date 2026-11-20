package org.arghyam.jalsoochak.message.report;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.message.report.catalog.KpiDisplay;
import org.arghyam.jalsoochak.message.report.catalog.OfficerColumn;
import org.arghyam.jalsoochak.message.report.catalog.Placeholder;
import org.arghyam.jalsoochak.message.report.catalog.ReportKind;
import org.arghyam.jalsoochak.message.report.catalog.ReportRole;
import org.arghyam.jalsoochak.message.report.catalog.SchemeColumn;
import org.arghyam.jalsoochak.message.report.catalog.SchemeList;
import org.arghyam.jalsoochak.message.report.catalog.SummaryColumn;
import org.arghyam.jalsoochak.message.report.catalog.SummaryKpi;
import org.arghyam.jalsoochak.message.report.layout.ReportBlock;
import org.arghyam.jalsoochak.message.report.layout.RoleLayout;
import org.arghyam.jalsoochak.message.report.layout.SituationReportLayoutConfig;
import org.arghyam.jalsoochak.message.report.render.ReportFormats;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Fails when tenant-service's copy of the report catalogue or the built-in layouts drifts from this
 * service's.
 *
 * <p>tenant-service validates a layout against its own copy (there is no shared module), so a
 * difference means it accepts something this service cannot draw, which then falls back to the
 * built-ins at report time, or refuses something this service could. Change both copies together,
 * and deploy message-service first.</p>
 *
 * <p>Reads tenant-service's files from the sibling directory of the monorepo, and is skipped when they
 * are absent, as in a checkout of this service alone.</p>
 */
class ReportCatalogueDriftTest {

    private static final Path TENANT_SERVICE = Path.of("..", "tenant-service");
    private static final Path TENANT_LAYOUTS = TENANT_SERVICE.resolve(Path.of("src", "test", "resources",
            "report-layouts"));
    private static final Path TENANT_CATALOGUE = TENANT_SERVICE.resolve(Path.of("src", "main", "java",
            "org", "arghyam", "jalsoochak", "tenant", "enums", "reportlayout"));

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern ENUM_HEADER = Pattern.compile("\\benum\\s+\\w+[^{]*\\{");
    private static final Pattern KIND = Pattern.compile("ReportKind\\.(\\w+)");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"([^\"]*)\"");

    // ── built-in layouts ─────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {
            "daily-section-officer.json", "weekly-section-officer.json", "weekly-sub-divisional-officer.json"})
    void tenantServiceValidatesTheSameBuiltInLayoutsThatAreDrawnHere(String file) throws IOException {
        Path theirs = TENANT_LAYOUTS.resolve(file);
        assumeTrue(Files.isRegularFile(theirs), "tenant-service sources are not beside this service");

        JsonNode drawn;
        try (InputStream in = new ClassPathResource("report-layouts/" + file).getInputStream()) {
            drawn = MAPPER.readTree(in);
        }

        assertThat(MAPPER.readTree(theirs.toFile()))
                .as("tenant-service's copy of %s; copy this service's file over it", file)
                .isEqualTo(drawn);
    }

    @Test
    void aConfigTenantServiceAcceptsIsUsableHere() throws IOException {
        Path sample = TENANT_LAYOUTS.resolve("custom-layout-sample.json");
        assumeTrue(Files.isRegularFile(sample), "tenant-service sources are not beside this service");

        // Strict, so a property tenant-service models and this service does not is caught here rather
        // than silently dropped at report time.
        SituationReportLayoutConfig config = MAPPER.copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(sample.toFile(), SituationReportLayoutConfig.class);

        assertThat(config.version()).isEqualTo(ReportLayoutResolver.SUPPORTED_VERSION);
        ReportFormats.of(config.settings().dateFormats(), config.settings().numberFormat());
        List<String> problems = new ArrayList<>();
        problems.addAll(problems(ReportKind.DAILY, config.daily()));
        problems.addAll(problems(ReportKind.WEEKLY, config.weekly()));
        assertThat(problems).isEmpty();
    }

    private static List<String> problems(ReportKind kind, ReportBlock block) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<ReportRole, RoleLayout> entry : block.roles().entrySet()) {
            RoleLayout role = entry.getValue();
            if (role.layout() != null) {
                LayoutSanityCheck.problems(kind, entry.getKey(), role.layout())
                        .forEach(p -> problems.add(kind + "." + entry.getKey() + ": " + p));
            }
        }
        return problems;
    }

    // ── catalogue enums ──────────────────────────────────────────────────────────────────────────

    static Stream<Arguments> catalogue() {
        return Stream.of(
                Arguments.of(ReportKind.class, "ReportKind.java"),
                Arguments.of(ReportRole.class, "ReportRole.java"),
                Arguments.of(SummaryKpi.class, "SummaryKpi.java"),
                Arguments.of(SummaryColumn.class, "SummaryColumn.java"),
                Arguments.of(SchemeList.class, "SchemeList.java"),
                Arguments.of(SchemeColumn.class, "SchemeColumn.java"),
                Arguments.of(OfficerColumn.class, "OfficerColumn.java"),
                Arguments.of(KpiDisplay.class, "KpiDisplay.java"),
                Arguments.of(Placeholder.class, "Placeholder.java"));
    }

    @ParameterizedTest
    @MethodSource("catalogue")
    void tenantServiceKnowsTheSameValuesInTheSameOrder(Class<? extends Enum<?>> ours, String file)
            throws IOException {
        List<String> names = Arrays.stream(ours.getEnumConstants()).map(Enum::name).toList();

        assertThat(tenantConstants(file).keySet())
                .as("constants of tenant-service's %s", file)
                .containsExactlyElementsOf(names);
    }

    @Test
    void tenantServiceOffersEachValueInTheSameReports() throws IOException {
        assertSameKinds("SummaryKpi.java", SummaryKpi.class, SummaryKpi::availableIn);
        assertSameKinds("SummaryColumn.java", SummaryColumn.class, SummaryColumn::availableIn);
        assertSameKinds("SchemeList.java", SchemeList.class, SchemeList::availableIn);
        assertSameKinds("Placeholder.java", Placeholder.class, Placeholder::availableIn);
    }

    @Test
    void tenantServiceGivesEachKpiTheSameDisplayRule() throws IOException {
        assertSameFirstArgument("SummaryKpi.java", SummaryKpi.class, kpi -> String.valueOf(kpi.hasPercent()));
    }

    @Test
    void tenantServiceSpellsEachPlaceholderTheSame() throws IOException {
        Map<String, String> theirs = tenantConstants("Placeholder.java");
        for (Placeholder placeholder : Placeholder.values()) {
            Matcher key = STRING_LITERAL.matcher(theirs.getOrDefault(placeholder.name(), ""));
            assertThat(key.find() ? key.group(1) : null)
                    .as("tenant-service's key for %s", placeholder)
                    .isEqualTo(placeholder.key());
        }
    }

    private static <E extends Enum<E>> void assertSameKinds(String file, Class<E> ours,
                                                            BiPredicate<E, ReportKind> availableIn)
            throws IOException {
        Map<String, String> theirs = tenantConstants(file);
        for (E value : ours.getEnumConstants()) {
            Set<ReportKind> ourKinds = EnumSet.noneOf(ReportKind.class);
            for (ReportKind kind : ReportKind.values()) {
                if (availableIn.test(value, kind)) {
                    ourKinds.add(kind);
                }
            }
            Set<ReportKind> theirKinds = EnumSet.noneOf(ReportKind.class);
            Matcher kind = KIND.matcher(theirs.getOrDefault(value.name(), ""));
            while (kind.find()) {
                theirKinds.add(ReportKind.valueOf(kind.group(1)));
            }
            assertThat(theirKinds).as("reports offering %s.%s in tenant-service", ours.getSimpleName(), value)
                    .isEqualTo(ourKinds);
        }
    }

    private static <E extends Enum<E>> void assertSameFirstArgument(String file, Class<E> ours,
                                                                    Function<E, String> expected)
            throws IOException {
        Map<String, String> theirs = tenantConstants(file);
        for (E value : ours.getEnumConstants()) {
            String arguments = theirs.getOrDefault(value.name(), "");
            assertThat(arguments.split(",")[0].trim())
                    .as("first argument of tenant-service's %s.%s", ours.getSimpleName(), value)
                    .isEqualTo(expected.apply(value));
        }
    }

    /**
     * The constants of tenant-service's enum in {@code file}, in declaration order, each with the text
     * between its parentheses ({@code ""} when it has none). Read from the source because the two
     * services share no code.
     */
    private static Map<String, String> tenantConstants(String file) throws IOException {
        Path source = TENANT_CATALOGUE.resolve(file);
        assumeTrue(Files.isRegularFile(source), "tenant-service sources are not beside this service");

        String text = Files.readString(source)
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "");
        Matcher header = ENUM_HEADER.matcher(text);
        assertThat(header.find()).as("an enum declaration in %s", file).isTrue();

        // The constants run to the first ';' or '}' outside parentheses.
        Map<String, String> constants = new LinkedHashMap<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = header.end(); i < text.length(); i++) {
            char c = text.charAt(i);
            if (depth == 0 && (c == ';' || c == '}' || c == ',')) {
                addConstant(constants, current.toString());
                current.setLength(0);
                if (c != ',') {
                    break;
                }
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            }
            current.append(c);
        }
        return constants;
    }

    private static void addConstant(Map<String, String> constants, String declaration) {
        String trimmed = declaration.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        int paren = trimmed.indexOf('(');
        String name = (paren < 0 ? trimmed : trimmed.substring(0, paren)).trim();
        String arguments = paren < 0 ? "" : trimmed.substring(paren + 1, trimmed.lastIndexOf(')'));
        constants.put(name, arguments);
    }
}
