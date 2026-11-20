package org.arghyam.jalsoochak.message.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;
import org.arghyam.jalsoochak.message.dto.WeeklyReportKpis;
import org.arghyam.jalsoochak.message.dto.WeeklyReportOfficerRow;
import org.arghyam.jalsoochak.message.report.DefaultReportLayouts;
import org.arghyam.jalsoochak.message.report.ReportKind;
import org.arghyam.jalsoochak.message.report.layout.ReportLayout;
import org.arghyam.jalsoochak.message.report.render.LayoutPdfRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * Pins the exact drawing of the three situation reports — daily SO, weekly SO and weekly SDO — so a
 * change to how they are rendered can prove the PDFs did not change.
 *
 * <p>Each fixture renders one PDF and compares its {@link PdfContentSnapshot} with the file of the same
 * name in {@code src/test/resources/report-snapshots/}. On a mismatch the actual output is written to
 * {@code target/report-snapshots/} for diffing. To re-record after an intended change:</p>
 *
 * <pre>mvn test -Dtest=ReportPdfSnapshotTest -Dsnapshots.update=true</pre>
 */
class ReportPdfSnapshotTest {

    private static final Path SNAPSHOT_DIR = Paths.get("src", "test", "resources", "report-snapshots");
    private static final Path ACTUAL_DIR = Paths.get("target", "report-snapshots");
    private static final boolean UPDATE = Boolean.getBoolean("snapshots.update");

    private static final String SO = "SECTION_OFFICER";
    private static final String SDO = "SUB_DIVISIONAL_OFFICER";
    private static final String DASHBOARD_URL = "https://jalsoochak.jjmbrain.in/staff/";
    private static final String SUPPORT_PHONE = "919999999999";

    /** 10:00 IST on Monday 20 July 2026, the morning after the default reported week closes. */
    private static final Instant GENERATED_AT = Instant.parse("2026-07-20T04:30:00Z");

    private static final DefaultReportLayouts LAYOUTS = new DefaultReportLayouts(new ObjectMapper());
    private static final ReportLayout DAILY_SO_LAYOUT = LAYOUTS.builtIn(ReportKind.DAILY, SO).orElseThrow();
    private static final ReportLayout WEEKLY_SO_LAYOUT = LAYOUTS.builtIn(ReportKind.WEEKLY, SO).orElseThrow();
    private static final ReportLayout WEEKLY_SDO_LAYOUT = LAYOUTS.builtIn(ReportKind.WEEKLY, SDO).orElseThrow();

    @TempDir
    Path tempDir;

    private DailyReportPdfService daily;
    private WeeklyReportPdfService weekly;

    @BeforeEach
    void setUp() {
        daily = new DailyReportPdfService(new LayoutPdfRenderer());
        ReflectionTestUtils.setField(daily, "reportDir", tempDir + "/");
        ReflectionTestUtils.setField(daily, "dashboardUrl", DASHBOARD_URL);
        ReflectionTestUtils.setField(daily, "supportPhone", SUPPORT_PHONE);
        daily.setClock(Clock.fixed(GENERATED_AT, ZoneOffset.UTC));

        weekly = new WeeklyReportPdfService(new LayoutPdfRenderer());
        ReflectionTestUtils.setField(weekly, "reportDir", tempDir + "/");
        ReflectionTestUtils.setField(weekly, "dashboardUrl", DASHBOARD_URL);
        ReflectionTestUtils.setField(weekly, "supportPhone", SUPPORT_PHONE);
        weekly.setClock(Clock.fixed(GENERATED_AT, ZoneOffset.UTC));
    }

    @Nested
    class Daily {

        @Test
        void d1Typical() throws IOException {
            Path pdf = daily.generate(dailyKpis("2026-07-19T17:30:00"), 5521L, "Bharat Sharma", SO,
                    DAILY_SO_LAYOUT,
                    List.of(
                            scheme(1, "Rampur", "98767", "Ramesh", "919000000001"),
                            scheme(2, "Bhagwanpur Piped Water Supply Scheme", "98769",
                                    "Suresh, Mahesh", "919000000002, 919000000003")),
                    List.of(
                            anomaly(1, "Rampur", "98767", "Reading Less Than Previous", "Ramesh", "919000000001"),
                            anomaly(3, "Kishanganj", "98781", "Duplicate Image", "Mahesh", "919000000003")));

            assertMatchesSnapshot("D1-daily-typical", pdf);
        }

        @Test
        void d2EmptyListsAndNoSupportPhone() throws IOException {
            ReflectionTestUtils.setField(daily, "supportPhone", "");
            DailyReportKpis kpis = DailyReportKpis.builder()
                    .reportDate("2026-07-19").cutoffIst("2026-07-19T16:00:00")
                    .noSupplySchemeIds(List.of()).schemeAnomalies(List.of())
                    .build();

            Path pdf = daily.generate(kpis, 5521L, "Bharat Sharma", SO, DAILY_SO_LAYOUT, List.of(), List.of());

            assertMatchesSnapshot("D2-daily-empty-no-phone", pdf);
        }

        @Test
        void d3PaginatesBothLists() throws IOException {
            List<ReportSchemeRow> noSupply = new ArrayList<>();
            for (int i = 1; i <= 60; i++) {
                noSupply.add(scheme(i, i % 7 == 0
                                ? "Piped-Water-Supply-Scheme-Kishanganj-Rampur-Bhagwanpur-Extension-" + i
                                : "Scheme " + i + " Rural Piped Water Supply",
                        String.valueOf(98700 + i), "Operator " + i, "9190000" + String.format("%05d", i)));
            }
            List<ReportSchemeRow> anomalies = new ArrayList<>();
            for (int i = 1; i <= 30; i++) {
                anomalies.add(anomaly(i, "Scheme " + i, String.valueOf(98700 + i),
                        i % 2 == 0 ? "Reading Less Than Previous" : "Duplicate Image",
                        "Operator " + i, "9190000" + String.format("%05d", i)));
            }

            Path pdf = daily.generate(dailyKpis("2026-07-19T16:00:00"), 5521L, "Bharat Sharma", SO,
                    DAILY_SO_LAYOUT,
                    noSupply, anomalies);

            assertMatchesSnapshot("D3-daily-pagination", pdf);
        }

        @Test
        void d4MissingCutoffAndOfficerName() throws IOException {
            Path pdf = daily.generate(dailyKpis(null), 5521L, null, SO, DAILY_SO_LAYOUT,
                    List.of(scheme(1, "Rampur", "98767", "Ramesh", "919000000001")), List.of());

            assertMatchesSnapshot("D4-daily-missing-cutoff", pdf);
        }
    }

    @Nested
    class WeeklySectionOfficer {

        @Test
        void w1Typical() throws IOException {
            Path pdf = weekly.generate(weeklyKpis(defaultWeek(), week(148, 142, 6, 4, 63.4), week(148, 140, 4, 7, 61.2)),
                    5521L, "Bharat Sharma", SO, WEEKLY_SO_LAYOUT,
                    List.of(scheme(1, "Rampur", null, "Ramesh", "919000000001")),
                    List.of(scheme(2, "Bhagwanpur", null, "Suresh, Mahesh", "919000000002, 919000000003")),
                    List.of(scheme(3, "Kishanganj", null, "Mahesh", "919000000003")),
                    List.of());

            assertMatchesSnapshot("W1-weekly-so-typical", pdf);
        }

        @Test
        void w2EmptyWithoutSupportPhone() throws IOException {
            ReflectionTestUtils.setField(weekly, "supportPhone", "");
            WeeklyReportKpis sparse = WeeklyReportKpis.builder()
                    .weekStart("2026-07-13").weekEnd("2026-07-19")
                    .previousWeekStart("2026-07-06").previousWeekEnd("2026-07-12")
                    .build();

            Path pdf = weekly.generate(sparse, 5521L, null, SO, WEEKLY_SO_LAYOUT,
                    List.of(), List.of(), List.of(), null);

            assertMatchesSnapshot("W2-weekly-so-empty", pdf);
        }

        @Test
        void w3ThursdayStartWeek() throws IOException {
            String[] thursdayWeek = {"2026-07-16", "2026-07-22", "2026-07-09", "2026-07-15"};

            Path pdf = weekly.generate(weeklyKpis(thursdayWeek, week(20, 18, 2, 1, 55.0), week(20, 18, 2, 1, 55.0)),
                    5521L, "Bharat Sharma", SO, WEEKLY_SO_LAYOUT,
                    List.of(scheme(1, "Rampur", null, "Ramesh", "919000000001")),
                    List.of(), List.of(), List.of());

            assertMatchesSnapshot("W3-weekly-so-thursday-week", pdf);
        }
    }

    @Nested
    class WeeklySubDivisionalOfficer {

        @Test
        void w4TypicalWithOfficers() throws IOException {
            Path pdf = weekly.generate(weeklyKpis(defaultWeek(), week(148, 120, 6, 9, 58.7), week(150, 118, 8, 9, 60.0)),
                    7700L, "Kavita Rao", SDO, WEEKLY_SDO_LAYOUT,
                    List.of(sdoScheme(1, "Rampur", "Rampur, Kishanganj"), sdoScheme(4, "Dharampur", "Dharampur")),
                    List.of(sdoScheme(2, "Bhagwanpur", "Bhagwanpur")),
                    List.of(sdoScheme(3, "Kishanganj", "Kishanganj Khurd")),
                    List.of(officer(1, "Alice Das", 40, 35, 2, 3, 62.5),
                            officer(2, "Binod Kumar", 60, 50, 3, 4, 57.0),
                            officer(3, "Chandan Bora", 48, 35, 1, 2, 55.3)));

            assertMatchesSnapshot("W4-weekly-sdo-typical", pdf);
        }

        @Test
        void w5EmptyWithNoOfficerRows() throws IOException {
            WeeklyReportKpis sparse = WeeklyReportKpis.builder()
                    .weekStart("2026-07-13").weekEnd("2026-07-19")
                    .previousWeekStart("2026-07-06").previousWeekEnd("2026-07-12")
                    .build();

            Path pdf = weekly.generate(sparse, 7700L, "Kavita Rao", SDO, WEEKLY_SDO_LAYOUT,
                    List.of(), List.of(), List.of(), null);

            assertMatchesSnapshot("W5-weekly-sdo-empty", pdf);
        }

        @Test
        void w6PaginatesLongTables() throws IOException {
            List<WeeklyReportOfficerRow> officers = new ArrayList<>();
            for (int i = 1; i <= 40; i++) {
                officers.add(officer(i, "Section Officer Number " + i, 30 + i, 20 + i % 9, i % 5, i % 4, 40.0 + i / 10.0));
            }
            List<ReportSchemeRow> noSupply = new ArrayList<>();
            for (int i = 1; i <= 50; i++) {
                noSupply.add(sdoScheme(i, "Scheme " + i + " Rural Piped Water Supply", "Village " + i + ", Village " + (i + 1)));
            }
            List<ReportSchemeRow> lowLpcd = new ArrayList<>();
            for (int i = 1; i <= 30; i++) {
                lowLpcd.add(sdoScheme(100 + i, "Low LPCD Scheme " + i, "Village " + i));
            }

            Path pdf = weekly.generate(weeklyKpis(defaultWeek(), week(400, 300, 50, 30, 48.2), week(400, 310, 45, 28, 49.9)),
                    7700L, "Kavita Rao", SDO, WEEKLY_SDO_LAYOUT, noSupply, List.of(), lowLpcd, officers);

            assertMatchesSnapshot("W6-weekly-sdo-pagination", pdf);
        }

        @Test
        void w7TrendMix() throws IOException {
            // Flat total; supplying up (good); not supplying down (good: inverted); low LPCD up (bad:
            // inverted); average LPCD down by a fraction (bad).
            Path pdf = weekly.generate(weeklyKpis(defaultWeek(), week(148, 125, 5, 12, 58.5), week(148, 120, 9, 8, 60.0)),
                    7700L, "Kavita Rao", SDO, WEEKLY_SDO_LAYOUT, List.of(), List.of(), List.of(),
                    List.of(officer(1, "Alice Das", 148, 125, 5, 12, 58.5)));

            assertMatchesSnapshot("W7-weekly-sdo-trend-mix", pdf);
        }
    }

    // ---- fixtures -------------------------------------------------------------

    private static DailyReportKpis dailyKpis(String cutoffIst) {
        return DailyReportKpis.builder()
                .reportDate("2026-07-19")
                .cutoffIst(cutoffIst)
                .totalSchemes(148)
                .schemesSupplying(142)
                .schemesNotSupplying(6)
                .householdsWithSupply(14200)
                .householdsWithSupplyPct(95.9)
                .householdsWithoutSupply(600)
                .householdsWithoutSupplyPct(4.1)
                .totalHouseholds(14800)
                .avgLpcd(63.4)
                .anomalousCount(2)
                .noSupplySchemeIds(List.of(1, 2))
                .schemeAnomalies(List.of())
                .build();
    }

    /** Monday 13 to Sunday 19 July 2026, and the week before it. */
    private static String[] defaultWeek() {
        return new String[]{"2026-07-13", "2026-07-19", "2026-07-06", "2026-07-12"};
    }

    private static WeeklyReportKpis weeklyKpis(String[] weeks, WeeklyReportKpis.WeekKpis week,
                                               WeeklyReportKpis.WeekKpis previous) {
        return WeeklyReportKpis.builder()
                .weekStart(weeks[0]).weekEnd(weeks[1])
                .previousWeekStart(weeks[2]).previousWeekEnd(weeks[3])
                .week(week).previousWeek(previous)
                .noSupplySchemeIds(List.of()).lowSupplyDaysSchemeIds(List.of()).lowLpcdSchemeIds(List.of())
                .sectionOfficerSummaries(List.of())
                .build();
    }

    private static WeeklyReportKpis.WeekKpis week(int total, int supplying, int notSupplying, int lowLpcd,
                                                  double avgLpcd) {
        return WeeklyReportKpis.WeekKpis.builder()
                .totalSchemes(total).schemesSupplying(supplying).schemesNotSupplying(notSupplying)
                .schemesLowLpcd(lowLpcd).avgLpcd(avgLpcd).build();
    }

    private static ReportSchemeRow scheme(int id, String name, String imisId, String jalMitras, String mobiles) {
        return ReportSchemeRow.builder().schemeId(id).schemeName(name).imisId(imisId)
                .jalMitraNames(jalMitras).jalMitraMobiles(mobiles).build();
    }

    private static ReportSchemeRow anomaly(int id, String name, String imisId, String type, String jalMitra,
                                           String mobile) {
        return ReportSchemeRow.builder().schemeId(id).schemeName(name).imisId(imisId).anomalyType(type)
                .jalMitraNames(jalMitra).jalMitraMobiles(mobile).build();
    }

    private static ReportSchemeRow sdoScheme(int id, String name, String villages) {
        return ReportSchemeRow.builder().schemeId(id).schemeName(name).villageNames(villages)
                .jalMitraNames("Operator " + id).jalMitraMobiles("9190000" + String.format("%05d", id))
                .sectionOfficerNames("Alice Das").sectionOfficerMobiles("919868595001").build();
    }

    private static WeeklyReportOfficerRow officer(int n, String name, int total, int supplying, int notSupplying,
                                                  int lowLpcd, double avgLpcd) {
        return WeeklyReportOfficerRow.builder().officerUserId(1000L + n).name(name)
                .mobile("9198685" + String.format("%05d", n))
                .totalSchemes(total).schemesSupplying(supplying).schemesNotSupplying(notSupplying)
                .schemesLowLpcd(lowLpcd).avgLpcd(avgLpcd).build();
    }

    // ---- snapshot comparison --------------------------------------------------

    private static void assertMatchesSnapshot(String name, Path pdf) throws IOException {
        String actual = PdfContentSnapshot.of(pdf);
        Path expectedFile = SNAPSHOT_DIR.resolve(name + ".txt");

        if (UPDATE) {
            Files.createDirectories(SNAPSHOT_DIR);
            Files.writeString(expectedFile, actual);
            return;
        }

        assertThat(expectedFile)
                .as("snapshot %s is missing; record it with -Dsnapshots.update=true", expectedFile)
                .exists();
        String expected = Files.readString(expectedFile).replace("\r\n", "\n");
        if (expected.equals(actual)) {
            return;
        }

        Files.createDirectories(ACTUAL_DIR);
        Path actualFile = ACTUAL_DIR.resolve(name + ".actual.txt");
        Files.writeString(actualFile, actual);
        fail("PDF %s differs from its snapshot at %s. Diff %s against %s.",
                name, firstDifference(expected, actual), expectedFile, actualFile);
    }

    private static String firstDifference(String expected, String actual) {
        String[] e = expected.split("\n", -1);
        String[] a = actual.split("\n", -1);
        for (int i = 0; i < Math.min(e.length, a.length); i++) {
            if (!e[i].equals(a[i])) {
                return "line " + (i + 1) + "\n  expected: " + e[i] + "\n  actual:   " + a[i] + "\n";
            }
        }
        return "line " + (Math.min(e.length, a.length) + 1) + " (expected " + e.length + " lines, got "
                + a.length + ")";
    }
}
