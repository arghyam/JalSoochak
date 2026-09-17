package org.arghyam.jalsoochak.message.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;
import org.arghyam.jalsoochak.message.dto.WeeklyReportKpis;
import org.arghyam.jalsoochak.message.dto.WeeklyReportOfficerRow;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.arghyam.jalsoochak.message.service.ReportPdfCanvas.BLANK_LINE;
import static org.arghyam.jalsoochak.message.service.ReportPdfCanvas.CONTENT_WIDTH;
import static org.arghyam.jalsoochak.message.service.ReportPdfCanvas.DENSE_CELL_PAD;
import static org.arghyam.jalsoochak.message.service.ReportPdfCanvas.DOWN;
import static org.arghyam.jalsoochak.message.service.ReportPdfCanvas.NO_CHANGE;
import static org.arghyam.jalsoochak.message.service.ReportPdfCanvas.UP;

/**
 * Renders the Weekly Water Service Situation Report PDF, in either the Section Officer or the
 * Sub-Divisional Officer layout.
 *
 * <p>Both open with a summary comparing the reported week against the one before, then list schemes.
 * They differ in what they ask of the reader: the SO's lists schemes by how many days they ran
 * ({@code SO weekly report.docx}), while the SDO's adds a per-Section-Officer performance table and
 * carries the officer and village on every scheme row ({@code SDO weekly report.docx}), because an
 * SDO acts through their officers rather than directly on a scheme.</p>
 */
@Service
@Slf4j
public class WeeklyReportPdfService {

    @Value("${weekly-report.report.dir:${daily-report.report.dir:${escalation.report.dir:/tmp/escalation-reports/}}}")
    private String reportDir;

    @Value("${weekly-report.dashboard-url:${daily-report.dashboard-url:https://jalsoochak.jjmbrain.in/staff/}}")
    private String dashboardUrl;

    @Value("${weekly-report.support-phone:${daily-report.support-phone:}}")
    private String supportPhone;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter HEADER = DateTimeFormatter.ofPattern("dd MMMM yyyy");
    private static final DateTimeFormatter RANGE = DateTimeFormatter.ofPattern("dd-MMM-yyyy");

    private static final String SDO_ROLE = "SUB_DIVISIONAL_OFFICER";
    /** The platform reports on IST days throughout; the generation date must agree. */
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Kolkata");

    private static final String KPI_TOTAL_SCHEMES = "Total Schemes";
    private static final String KPI_TOTAL_HANDED_OVER = "Total Handed-Over Schemes";
    private static final String KPI_NOT_SUPPLYING = "Schemes Not Supplying Water at all";
    private static final String KPI_NOT_SUPPLYING_SDO = "Schemes Not Supplying Water (0 days in the week)";
    private static final String KPI_LOW_LPCD = "Schemes With Supply <=15 LPCD (average)";

    /**
     * KPIs where a rise is a deterioration, so the arrow colouring inverts: more schemes not
     * supplying, or more under-supplying, is worse however the arrow points.
     */
    private static final Set<String> NEGATIVE_KPI_LABELS =
            Set.of(KPI_NOT_SUPPLYING, KPI_NOT_SUPPLYING_SDO, KPI_LOW_LPCD);

    /**
     * Generates the report PDF and returns the path it was written to.
     *
     * <p>The resolved path, not just the filename: {@code weekly-report.report.dir} falls back
     * through {@code daily-report.report.dir} to {@code escalation.report.dir}, so a caller that
     * rebuilds the path from a property of its own picks the wrong directory the moment any of them
     * is set — which {@code DAILY_REPORT_DIR} already is in several environments. Handing back the
     * path this method actually saved to leaves nothing to re-derive.</p>
     *
     * @param officerUserType SECTION_OFFICER or SUB_DIVISIONAL_OFFICER — selects the layout
     * @param officerRows     SDO only: the per-Section-Officer performance rows
     */
    public Path generate(WeeklyReportKpis kpis, long officerUserId, String officerName, String officerUserType,
                         List<ReportSchemeRow> noSupplyRows,
                         List<ReportSchemeRow> lowSupplyDaysRows,
                         List<ReportSchemeRow> lowLpcdRows,
                         List<WeeklyReportOfficerRow> officerRows) throws IOException {
        ensureReportDirExists();

        LocalDate weekStart = LocalDate.parse(kpis.getWeekStart(), ISO);
        LocalDate weekEnd = LocalDate.parse(kpis.getWeekEnd(), ISO);
        String filename = ReportFileNaming.weeklyFilename(officerUserType, officerUserId, weekStart, weekEnd);
        Path filePath = Paths.get(reportDir, filename);

        boolean sdo = isSdo(officerUserType);

        try (PDDocument doc = new PDDocument()) {
            // The canvas closes in its own block: PDFBox refuses to serialise a document while a page
            // content stream is still open, so the save has to happen after the last page is flushed.
            try (ReportPdfCanvas c = new ReportPdfCanvas(doc, NEGATIVE_KPI_LABELS)) {
                c.newPage();

                drawHeader(c, officerName, sdo, weekStart, weekEnd);
                drawSummary(c, kpis, sdo);

                if (sdo) {
                    drawOfficerPerformance(c, officerRows);
                    drawSchemeSection(c, "3. List of Schemes with No Supply (zero days)", noSupplyRows, true);
                    drawSchemeSection(c, "4. List of Schemes with <=15 LPCD supply over the Week", lowLpcdRows, true);
                } else {
                    drawSchemeSection(c, "2. List of Schemes With no Supply (the whole week)", noSupplyRows, false);
                    drawSchemeSection(c, "3. List of Schemes With 1 to 3 days of supply", lowSupplyDaysRows, false);
                    drawSchemeSection(c, "4. List of Schemes with <=15 LPCD supply over the Week", lowLpcdRows, false);
                }

                drawFooter(c);
            }
            doc.save(filePath.toFile());
        }

        log.info("[WeeklyReportPdf] Generated {} (role={} noSupply={} lowDays={} lowLpcd={} officers={})",
                filename, officerUserType, noSupplyRows.size(), lowSupplyDaysRows.size(), lowLpcdRows.size(),
                officerRows != null ? officerRows.size() : 0);
        return filePath;
    }

    private void drawHeader(ReportPdfCanvas c, String officerName, boolean sdo,
                            LocalDate weekStart, LocalDate weekEnd) throws IOException {
        c.centeredLine(c.bold(), 16, "Weekly Water Service Situation Report");
        c.centeredLine(c.font(), 10, sdo ? "(SDO) — Auto generated by JalSoochak"
                : "(Section Officer) — Auto generated by JalSoochak");
        c.moveDown(BLANK_LINE);
        c.labelValueLine(11, sdo ? "Sub-Divisional Officer: " : "Officer: ",
                officerName != null ? officerName : "Officer");
        // IST, like every other date in this pipeline — a UTC "today" would print yesterday for a
        // report generated in the small hours of Monday morning.
        c.labelValueLine(11, "Date: ", LocalDate.now(REPORT_ZONE).format(HEADER));
        c.labelValueLine(11, "Reporting Period: ",
                "Monday " + weekStart.format(RANGE) + " to Sunday " + weekEnd.format(RANGE));
        c.moveDown(BLANK_LINE);
        c.headerLinkLine(9, "Visit JalSoochak Dashboard at ", dashboardUrl, " for more insights");
    }

    private void drawSummary(ReportPdfCanvas c, WeeklyReportKpis kpis, boolean sdo) throws IOException {
        c.sectionTitle("1. Summary");

        WeeklyReportKpis.WeekKpis week = orEmpty(kpis.getWeek());
        WeeklyReportKpis.WeekKpis prev = orEmpty(kpis.getPreviousWeek());

        float[] cols = {CONTENT_WIDTH * 0.40f, CONTENT_WIDTH * 0.22f, CONTENT_WIDTH * 0.22f,
                CONTENT_WIDTH * 0.16f};
        String[] header = {"KPI",
                weekLabel(kpis.getWeekStart(), kpis.getWeekEnd()),
                weekLabel(kpis.getPreviousWeekStart(), kpis.getPreviousWeekEnd()),
                "Trend"};
        boolean[] center = {false, true, true, true};

        List<String[]> rows = new ArrayList<>();
        if (sdo) {
            rows.add(intRow(KPI_TOTAL_HANDED_OVER, week.getTotalSchemes(), prev.getTotalSchemes()));
            rows.add(intRow("Schemes Supplying Water (>=4 days in the week)",
                    week.getSchemesSupplying(), prev.getSchemesSupplying()));
            rows.add(intRow(KPI_NOT_SUPPLYING_SDO, week.getSchemesNotSupplying(), prev.getSchemesNotSupplying()));
            rows.add(intRow(KPI_LOW_LPCD, week.getSchemesLowLpcd(), prev.getSchemesLowLpcd()));
        } else {
            rows.add(intRow(KPI_TOTAL_SCHEMES, week.getTotalSchemes(), prev.getTotalSchemes()));
            rows.add(intRow("Schemes Supplying Water (for minimum 1 day in the week)",
                    week.getSchemesSupplying(), prev.getSchemesSupplying()));
            rows.add(intRow(KPI_NOT_SUPPLYING, week.getSchemesNotSupplying(), prev.getSchemesNotSupplying()));
        }
        rows.add(dblRow("Average LPCD", week.getAvgLpcd(), prev.getAvgLpcd()));

        c.drawTable(cols, header, rows, 9.5f, 9.5f, center);
        // Both columns are computed from today's scheme mappings, which carry no validity dates — so
        // a reassignment moves the comparison week's figure too. Saying so beats a reader concluding
        // the numbers are wrong.
        c.footnote("Scheme totals reflect current mappings; both columns move if schemes are reassigned.");
    }

    private void drawOfficerPerformance(ReportPdfCanvas c, List<WeeklyReportOfficerRow> officerRows)
            throws IOException {
        c.sectionTitle("2. Section Officers Performance");

        float[] cols = {CONTENT_WIDTH * 0.05f, CONTENT_WIDTH * 0.19f, CONTENT_WIDTH * 0.15f,
                CONTENT_WIDTH * 0.10f, CONTENT_WIDTH * 0.15f, CONTENT_WIDTH * 0.13f,
                CONTENT_WIDTH * 0.12f, CONTENT_WIDTH * 0.11f};
        String[] header = {"S.No.", "Section Officer Name", "SO Mobile No.", "Total Schemes",
                "Schemes Supplying Water (>=4 days)", "Schemes Not Supplying Water (0 days)",
                "Schemes with Supply <=15 LPCD", "Average LPCD"};
        boolean[] center = {true, false, false, true, true, true, true, true};

        List<String[]> rows = new ArrayList<>();
        if (officerRows == null || officerRows.isEmpty()) {
            rows.add(new String[]{"", "None", "", "", "", "", "", ""});
        } else {
            int n = 1;
            for (WeeklyReportOfficerRow r : officerRows) {
                rows.add(new String[]{String.valueOf(n++), nvl(r.getName()), nvl(r.getMobile()),
                        String.valueOf(r.getTotalSchemes()), String.valueOf(r.getSchemesSupplying()),
                        String.valueOf(r.getSchemesNotSupplying()), String.valueOf(r.getSchemesLowLpcd()),
                        fmt(r.getAvgLpcd())});
            }
        }

        c.drawTable(cols, header, rows, 8f, 8f, DENSE_CELL_PAD, center);
        // Each row covers only the schemes that officer shares with this SDO, so the column will not
        // generally sum to the SDO's own total — and an officer's own report shows more schemes.
        c.footnote("Each officer's figures cover only the schemes they share with you.");
    }

    /**
     * One of the scheme-list sections. The SDO variant carries the owning Section Officer and the
     * village on every row; the SO variant does not, because for an SO both are already known.
     */
    private void drawSchemeSection(ReportPdfCanvas c, String title, List<ReportSchemeRow> rows, boolean sdo)
            throws IOException {
        c.sectionTitle(title);

        if (sdo) {
            float[] cols = {CONTENT_WIDTH * 0.05f, CONTENT_WIDTH * 0.15f, CONTENT_WIDTH * 0.14f,
                    CONTENT_WIDTH * 0.18f, CONTENT_WIDTH * 0.14f, CONTENT_WIDTH * 0.16f,
                    CONTENT_WIDTH * 0.18f};
            String[] header = {"S. No", "SO Name", "SO Phone Number", "Scheme Name", "Village Name",
                    "Jal Mitra Name", "Jal Mitra Phone"};
            c.drawTable(cols, header, sdoSchemeRows(rows), 8.5f, 8.5f, DENSE_CELL_PAD, null);
        } else {
            float[] cols = {CONTENT_WIDTH * 0.08f, CONTENT_WIDTH * 0.40f, CONTENT_WIDTH * 0.28f,
                    CONTENT_WIDTH * 0.24f};
            String[] header = {"S. No.", "Scheme Name", "Jal Mitra", "Jal Mitra Phone Number"};
            c.drawTable(cols, header, soSchemeRows(rows), 9f, 9.5f);
        }
    }

    private void drawFooter(ReportPdfCanvas c) throws IOException {
        if (supportPhone == null || supportPhone.isBlank()) {
            return;
        }
        c.footnote("For support, contact " + supportPhone);
    }

    private static List<String[]> soSchemeRows(List<ReportSchemeRow> rows) {
        List<String[]> out = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            out.add(new String[]{"", "None", "", ""});
            return out;
        }
        int n = 1;
        for (ReportSchemeRow r : rows) {
            out.add(new String[]{String.valueOf(n++), nvl(r.getSchemeName()),
                    nvl(r.getJalMitraNames()), nvl(r.getJalMitraMobiles())});
        }
        return out;
    }

    private static List<String[]> sdoSchemeRows(List<ReportSchemeRow> rows) {
        List<String[]> out = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            out.add(new String[]{"", "", "", "None", "", "", ""});
            return out;
        }
        int n = 1;
        for (ReportSchemeRow r : rows) {
            out.add(new String[]{String.valueOf(n++), nvl(r.getSectionOfficerNames()),
                    nvl(r.getSectionOfficerMobiles()), nvl(r.getSchemeName()), nvl(r.getVillageNames()),
                    nvl(r.getJalMitraNames()), nvl(r.getJalMitraMobiles())});
        }
        return out;
    }

    private static String[] intRow(String label, int current, int previous) {
        return new String[]{label, String.valueOf(current), String.valueOf(previous), trendInt(current - previous)};
    }

    private static String[] dblRow(String label, double current, double previous) {
        return new String[]{label, fmt(current), fmt(previous), trendDouble(current - previous)};
    }

    private static String trendInt(int delta) {
        if (delta == 0) return NO_CHANGE;
        return (delta > 0 ? UP + "+" : DOWN) + delta;
    }

    private static String trendDouble(double delta) {
        double rounded = Math.round(delta * 10.0) / 10.0;
        if (rounded == 0.0) return NO_CHANGE;
        return (rounded > 0 ? UP + "+" : DOWN) + fmt(rounded);
    }

    /** {@code (13-Jul-2026 to 19-Jul-2026)} for a summary column header. */
    private static String weekLabel(String start, String end) {
        if (start == null || end == null) {
            return "";
        }
        return LocalDate.parse(start, ISO).format(RANGE) + " to " + LocalDate.parse(end, ISO).format(RANGE);
    }

    private static WeeklyReportKpis.WeekKpis orEmpty(WeeklyReportKpis.WeekKpis kpis) {
        return kpis != null ? kpis : WeeklyReportKpis.WeekKpis.builder().build();
    }

    private static boolean isSdo(String officerUserType) {
        return officerUserType != null && SDO_ROLE.equalsIgnoreCase(officerUserType.trim());
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(Math.round(v * 10.0) / 10.0);
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private void ensureReportDirExists() throws IOException {
        Path dir = Paths.get(reportDir);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            log.info("[WeeklyReportPdf] Created report directory: {}", dir);
        }
    }
}
