package org.arghyam.jalsoochak.message.report;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.arghyam.jalsoochak.message.report.catalog.SchemeList;
import org.arghyam.jalsoochak.message.report.layout.ReportLayout;
import org.arghyam.jalsoochak.message.report.layout.ReportSection;
import org.arghyam.jalsoochak.message.repository.TenantReportLayoutRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportLayoutResolverTest {

    /** Strict about unknown properties, as a plain ObjectMapper is: the resolver must not depend on Spring's. */
    private static final ObjectMapper APP_MAPPER = new ObjectMapper();
    private static final DefaultReportLayouts DEFAULTS = new DefaultReportLayouts(new ObjectMapper());

    private static final int TENANT_ID = 7;
    private static final String SO = "SECTION_OFFICER";
    private static final String SDO = "SUB_DIVISIONAL_OFFICER";
    private static final String DAILY_URL = "https://daily.example.org/staff/";
    private static final String DAILY_PHONE = "1800-111-1111";
    private static final String WEEKLY_URL = "https://weekly.example.org/staff/";
    private static final String WEEKLY_PHONE = "1800-222-2222";
    private static final String TENANT_URL = "https://tenant.example.org/staff/";

    /** A daily layout of one summary row. */
    private static final String DAILY_LAYOUT = """
            {"sections":[{"type":"SUMMARY","title":"Tenant summary",
              "columns":[{"field":"LABEL","label":"KPI"},{"field":"VALUE","label":"Today"}],
              "rows":[{"kpi":"TOTAL_SCHEMES","label":"Total schemes"}]}]}
            """;

    /** A weekly layout of one scheme list. */
    private static final String WEEKLY_LAYOUT = """
            {"sections":[{"type":"SCHEME_LIST","list":"LOW_LPCD","title":"Tenant low LPCD",
              "columns":[{"field":"SCHEME_NAME","label":"Scheme"}]}]}
            """;

    @Mock
    private TenantReportLayoutRepository repository;

    private SimpleMeterRegistry meters;
    private ReportLayoutResolver resolver;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        resolver = new ReportLayoutResolver(repository, DEFAULTS, APP_MAPPER, meters,
                DAILY_URL, DAILY_PHONE, WEEKLY_URL, WEEKLY_PHONE);
    }

    private void stored(String json) {
        when(repository.findConfigValue(TENANT_ID)).thenReturn(Optional.of(json));
    }

    private Optional<ResolvedReportLayout> resolve(ReportKind kind, String role) {
        return resolver.resolve(TENANT_ID, kind, role);
    }

    private static ReportLayout builtIn(ReportKind kind, String role) {
        return DEFAULTS.builtIn(kind, role).orElseThrow();
    }

    private static String firstTitle(ResolvedReportLayout resolved) {
        return resolved.layout().sections().get(0).title();
    }

    private double fallbacks(String report) {
        return meters.counter(ReportLayoutResolver.FALLBACK_METRIC, "report", report).count();
    }

    /** {@code {"version":1, <blocks>}}. */
    private static String config(String blocks) {
        return "{\"version\":1," + blocks + "}";
    }

    @Nested
    class WithoutConfig {

        @Test
        void aSectionOfficerGetsTheBuiltInLayoutsWithEachReportsEnvSettings() {
            ResolvedReportLayout daily = resolve(ReportKind.DAILY, SO).orElseThrow();
            ResolvedReportLayout weekly = resolve(ReportKind.WEEKLY, SO).orElseThrow();

            assertThat(daily.layout()).isEqualTo(builtIn(ReportKind.DAILY, SO));
            assertThat(daily.dashboardUrl()).isEqualTo(DAILY_URL);
            assertThat(daily.supportPhone()).isEqualTo(DAILY_PHONE);
            assertThat(daily.formats().date(LocalDate.of(2026, 7, 19))).isEqualTo("19 July 2026");
            assertThat(weekly.layout()).isEqualTo(builtIn(ReportKind.WEEKLY, SO));
            assertThat(weekly.dashboardUrl()).isEqualTo(WEEKLY_URL);
            assertThat(weekly.supportPhone()).isEqualTo(WEEKLY_PHONE);
        }

        @Test
        void aSubDivisionalOfficerGetsTheWeeklyReportOnly() {
            assertThat(resolve(ReportKind.DAILY, SDO)).isEmpty();
            assertThat(resolve(ReportKind.WEEKLY, SDO).orElseThrow().layout())
                    .isEqualTo(builtIn(ReportKind.WEEKLY, SDO));
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {"", "JUNIOR_ENGINEER"})
        void anyOtherRoleGetsTheSectionOfficerLayouts(String role) {
            assertThat(resolve(ReportKind.DAILY, role).orElseThrow().layout()).isEqualTo(builtIn(ReportKind.DAILY, SO));
            assertThat(resolve(ReportKind.WEEKLY, role).orElseThrow().layout())
                    .isEqualTo(builtIn(ReportKind.WEEKLY, SO));
        }

        @Test
        void aConfigOfOnlyTheVersionChangesNothing() {
            stored("{\"version\":1}");

            assertThat(resolve(ReportKind.DAILY, SO).orElseThrow().layout()).isEqualTo(builtIn(ReportKind.DAILY, SO));
            assertThat(resolve(ReportKind.DAILY, SDO)).isEmpty();
            assertThat(resolve(ReportKind.WEEKLY, SDO).orElseThrow().layout())
                    .isEqualTo(builtIn(ReportKind.WEEKLY, SDO));
            assertThat(fallbacks("daily") + fallbacks("weekly")).isZero();
        }
    }

    @Nested
    class WithABlock {

        @ParameterizedTest
        @ValueSource(strings = {SDO, " sub_divisional_officer "})
        void aListedRoleGetsItsLayout(String role) {
            stored(config("\"daily\":{\"roles\":{\"SUB_DIVISIONAL_OFFICER\":{\"layout\":" + DAILY_LAYOUT + "}}}"));

            ResolvedReportLayout resolved = resolve(ReportKind.DAILY, role).orElseThrow();

            assertThat(firstTitle(resolved)).isEqualTo("Tenant summary");
            assertThat(fallbacks("daily")).isZero();
        }

        @Test
        void useDefaultGetsTheBuiltInLayout() {
            stored(config("\"weekly\":{\"roles\":{\"SUB_DIVISIONAL_OFFICER\":{\"useDefault\":true}}}"));

            assertThat(resolve(ReportKind.WEEKLY, SDO).orElseThrow().layout())
                    .isEqualTo(builtIn(ReportKind.WEEKLY, SDO));
        }

        @ParameterizedTest
        @NullSource
        @ValueSource(strings = {SO, "JUNIOR_ENGINEER", ""})
        void aRoleTheBlockDoesNotListGetsNothing(String role) {
            stored(config("\"weekly\":{\"roles\":{\"SUB_DIVISIONAL_OFFICER\":{\"useDefault\":true}}}"));

            assertThat(resolve(ReportKind.WEEKLY, role)).isEmpty();
        }

        @Test
        void anEmptyBlockSendsTheReportToNobody() {
            stored(config("\"daily\":{\"roles\":{}}"));

            assertThat(resolve(ReportKind.DAILY, SO)).isEmpty();
            assertThat(fallbacks("daily")).isZero();
        }

        @Test
        void oneReportsBlockLeavesTheOtherReportBuiltIn() {
            stored(config("\"daily\":{\"roles\":{}}"));

            assertThat(resolve(ReportKind.WEEKLY, SO).orElseThrow().layout())
                    .isEqualTo(builtIn(ReportKind.WEEKLY, SO));
        }

        @Test
        void aBrokenEntryForAnotherReportDoesNotMatter() {
            // Only what this report needs is checked: a daily OFFICER_PERFORMANCE can't be drawn, but
            // it is never asked for here.
            stored(config("""
                    "daily":{"roles":{"SECTION_OFFICER":{"layout":{"sections":[
                      {"type":"OFFICER_PERFORMANCE","title":"Officers",
                       "columns":[{"field":"OFFICER_NAME","label":"Name"}]}]}}}},
                    "weekly":{"roles":{"SECTION_OFFICER":{"layout":%s}}}
                    """.formatted(WEEKLY_LAYOUT)));

            assertThat(firstTitle(resolve(ReportKind.WEEKLY, SO).orElseThrow())).isEqualTo("Tenant low LPCD");
            assertThat(fallbacks("weekly")).isZero();
        }

        @Test
        void anUnknownPropertyIsIgnored() {
            // Written by a newer tenant-service: it must not send a working tenant back to the built-ins.
            stored("""
                    {"version":1,"addedLater":true,
                     "daily":{"roles":{"SECTION_OFFICER":{"alsoNew":1,"layout":%s}}}}
                    """.formatted(DAILY_LAYOUT));

            assertThat(firstTitle(resolve(ReportKind.DAILY, SO).orElseThrow())).isEqualTo("Tenant summary");
            assertThat(fallbacks("daily")).isZero();
        }
    }

    @Nested
    class Settings {

        @Test
        void tenantSettingsOverrideTheEnvironmentFieldByField() {
            stored(config("\"settings\":{\"dashboardUrl\":\"" + TENANT_URL + "\"}"));

            ResolvedReportLayout resolved = resolve(ReportKind.WEEKLY, SO).orElseThrow();

            assertThat(resolved.dashboardUrl()).isEqualTo(TENANT_URL);
            assertThat(resolved.supportPhone()).isEqualTo(WEEKLY_PHONE);
        }

        @Test
        void tenantSettingsApplyToTheBuiltInLayoutsToo() {
            stored(config("\"settings\":{\"supportPhone\":\"1800-000-0000\"}"));

            ResolvedReportLayout resolved = resolve(ReportKind.DAILY, SO).orElseThrow();

            assertThat(resolved.layout()).isEqualTo(builtIn(ReportKind.DAILY, SO));
            assertThat(resolved.supportPhone()).isEqualTo("1800-000-0000");
            assertThat(resolved.dashboardUrl()).isEqualTo(DAILY_URL);
        }

        @Test
        void tenantFormatsReplaceTheBuiltInOnes() {
            stored(config("""
                    "settings":{"dateFormats":{"date":"dd-MM-yyyy"},"numberFormat":{"decimals":0}}
                    """));

            ResolvedReportLayout resolved = resolve(ReportKind.DAILY, SO).orElseThrow();

            assertThat(resolved.formats().date(LocalDate.of(2026, 7, 19))).isEqualTo("19-07-2026");
            assertThat(resolved.formats().shortDate(LocalDate.of(2026, 7, 19))).isEqualTo("19-Jul-2026");
            assertThat(resolved.formats().decimal(63.4)).isEqualTo("63");
        }
    }

    @Nested
    class UnusableConfig {

        @ParameterizedTest
        @ValueSource(strings = {
                "{not json",
                "null",
                "[]",
                "{\"version\":2}",
                "{\"daily\":{\"roles\":{}}}",
                // Catalogue drift: a KPI, a section type and a role this service does not know.
                "{\"version\":1,\"daily\":{\"roles\":{\"SECTION_OFFICER\":{\"layout\":{\"sections\":[{\"type\":\"SUMMARY\","
                        + "\"title\":\"S\",\"columns\":[{\"field\":\"LABEL\",\"label\":\"K\"}],"
                        + "\"rows\":[{\"kpi\":\"NOT_A_KPI\",\"label\":\"X\"}]}]}}}}}",
                "{\"version\":1,\"daily\":{\"roles\":{\"SECTION_OFFICER\":{\"layout\":{\"sections\":[{\"type\":\"CHART\"}]}}}}}",
                "{\"version\":1,\"daily\":{\"roles\":{\"JUNIOR_ENGINEER\":{\"useDefault\":true}}}}",
                // Things tenant-service's validation rules out, written some other way.
                "{\"version\":1,\"daily\":{}}",
                "{\"version\":1,\"daily\":{\"roles\":{\"SECTION_OFFICER\":{}}}}",
                "{\"version\":1,\"daily\":{\"roles\":{\"SECTION_OFFICER\":{\"useDefault\":false}}}}",
                "{\"version\":1,\"daily\":{\"roles\":{\"SECTION_OFFICER\":{\"useDefault\":true,\"layout\":{\"sections\":[]}}}}}",
                "{\"version\":1,\"daily\":{\"roles\":{\"SECTION_OFFICER\":{\"layout\":{\"sections\":[]}}}}}",
                "{\"version\":1,\"settings\":{\"dateFormats\":{\"date\":\"yyyy-bb\"}}}"
        })
        void fallsBackToTheBuiltInLayoutWithTheEnvSettingsAndCountsIt(String json) {
            stored(json);

            ResolvedReportLayout resolved = resolve(ReportKind.DAILY, SO).orElseThrow();

            assertThat(resolved.layout()).isEqualTo(builtIn(ReportKind.DAILY, SO));
            assertThat(resolved.dashboardUrl()).isEqualTo(DAILY_URL);
            assertThat(resolved.supportPhone()).isEqualTo(DAILY_PHONE);
            assertThat(fallbacks("daily")).isEqualTo(1.0);
            assertThat(fallbacks("weekly")).isZero();
        }

        @Test
        void theTenantsSettingsAreDroppedWithTheRestOfIt() {
            stored(config("""
                    "settings":{"dashboardUrl":"%s"},
                    "weekly":{"roles":{"SECTION_OFFICER":{"layout":{"sections":[
                      {"type":"SCHEME_LIST","list":"ANOMALIES","title":"Daily only",
                       "columns":[{"field":"SCHEME_NAME","label":"Scheme"}]}]}}}}
                    """.formatted(TENANT_URL)));

            ResolvedReportLayout resolved = resolve(ReportKind.WEEKLY, SO).orElseThrow();

            assertThat(resolved.layout()).isEqualTo(builtIn(ReportKind.WEEKLY, SO));
            assertThat(resolved.dashboardUrl()).isEqualTo(WEEKLY_URL);
            assertThat(fallbacks("weekly")).isEqualTo(1.0);
        }

        @Test
        void aDailySubDivisionalOfficerStillGetsNothing() {
            // useDefault where no built-in exists: the fallback is the built-ins, which have none.
            stored(config("\"daily\":{\"roles\":{\"SUB_DIVISIONAL_OFFICER\":{\"useDefault\":true}}}"));

            assertThat(resolve(ReportKind.DAILY, SDO)).isEmpty();
            assertThat(fallbacks("daily")).isEqualTo(1.0);
        }

        @Test
        void isLoggedAtErrorWithTheTenantReportAndRole() {
            stored("{\"version\":2}");
            Logger logger = (Logger) LoggerFactory.getLogger(ReportLayoutResolver.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                resolve(ReportKind.WEEKLY, " sub_divisional_officer ");
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage())
                        .contains("tenant=7 report=WEEKLY role=sub_divisional_officer")
                        .contains("version 2 is not supported");
            });
        }
    }

    @Test
    void aDatabaseErrorIsNotTreatedAsNoConfig() {
        // Rendering the built-ins for a tenant that has its own would be wrong; the event is retried.
        when(repository.findConfigValue(TENANT_ID)).thenThrow(new DataAccessResourceFailureException("down"));

        assertThatThrownBy(() -> resolve(ReportKind.DAILY, SO)).isInstanceOf(DataAccessException.class);
        assertThat(fallbacks("daily")).isZero();
    }

    @Test
    void theNeedsFollowTheResolvedLayout() {
        stored(config("\"weekly\":{\"roles\":{\"SECTION_OFFICER\":{\"layout\":" + WEEKLY_LAYOUT + "}}}"));

        ReportDataNeeds needs = resolve(ReportKind.WEEKLY, SO).orElseThrow().needs();

        assertThat(needs.schemeLists()).containsOnlyKeys(SchemeList.LOW_LPCD);
        assertThat(needs.officerRows()).isFalse();
    }

    @Test
    void sectionsAreTheTenantsInOrder() {
        stored(config("""
                "daily":{"roles":{"SECTION_OFFICER":{"layout":{"sections":[
                  {"type":"SCHEME_LIST","list":"ANOMALIES","title":"First",
                   "columns":[{"field":"ANOMALY_TYPE","label":"Anomaly"}]},
                  {"type":"SCHEME_LIST","list":"NO_SUPPLY","title":"Second",
                   "columns":[{"field":"SCHEME_NAME","label":"Scheme"}]}]}}}}
                """));

        List<ReportSection> sections = resolve(ReportKind.DAILY, SO).orElseThrow().layout().sections();

        assertThat(sections).extracting(ReportSection::title).containsExactly("First", "Second");
    }
}
