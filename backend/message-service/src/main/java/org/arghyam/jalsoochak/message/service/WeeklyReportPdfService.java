package org.arghyam.jalsoochak.message.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;
import org.arghyam.jalsoochak.message.dto.WeeklyReportKpis;
import org.arghyam.jalsoochak.message.dto.WeeklyReportOfficerRow;
import org.arghyam.jalsoochak.message.report.layout.ReportLayout;
import org.arghyam.jalsoochak.message.report.render.LayoutPdfRenderer;
import org.arghyam.jalsoochak.message.report.render.ReportFormats;
import org.arghyam.jalsoochak.message.report.render.WeeklyReportContent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Renders the Weekly Water Service Situation Report PDF for one officer, in the layout it is given.
 *
 * <p>The built-in layouts open with a summary comparing the reported week against the one before,
 * then list schemes. They differ in what they ask of the reader: the Section Officer's lists schemes
 * by how many days they ran ({@code SO weekly report.docx}), while the Sub-Divisional Officer's adds a
 * per-Section-Officer performance table and carries the officer and village on every scheme row
 * ({@code SDO weekly report.docx}), because an SDO acts through their officers rather than directly
 * on a scheme.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class WeeklyReportPdfService {

    @Value("${weekly-report.report.dir:${daily-report.report.dir:${escalation.report.dir:/tmp/escalation-reports/}}}")
    private String reportDir;

    @Value("${weekly-report.dashboard-url:${daily-report.dashboard-url:https://jalsoochak.jjmbrain.in/staff/}}")
    private String dashboardUrl;

    @Value("${weekly-report.support-phone:${daily-report.support-phone:}}")
    private String supportPhone;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    /** The platform reports on IST days throughout; the generation date must agree. */
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Kolkata");

    private final LayoutPdfRenderer renderer;

    /**
     * Source of {@code {generatedDate}}; read in {@link #REPORT_ZONE} whatever the clock's own zone. A
     * UTC "today" would print yesterday for a report generated in the small hours of Monday morning.
     */
    private Clock clock = Clock.systemUTC();

    /**
     * Generates the report PDF and returns the path it was written to.
     *
     * <p>The resolved path, not just the filename: {@code weekly-report.report.dir} falls back
     * through {@code daily-report.report.dir} to {@code escalation.report.dir}, so a caller that
     * rebuilds the path from a property of its own picks the wrong directory the moment any of them
     * is set — which {@code DAILY_REPORT_DIR} already is in several environments. Handing back the
     * path this method actually saved to leaves nothing to re-derive.</p>
     *
     * @param officerUserType SECTION_OFFICER or SUB_DIVISIONAL_OFFICER; names the file
     * @param layout          what the report shows, and where
     * @param officerRows     SDO only: the per-Section-Officer performance rows
     */
    public Path generate(WeeklyReportKpis kpis, long officerUserId, String officerName, String officerUserType,
                         ReportLayout layout,
                         List<ReportSchemeRow> noSupplyRows,
                         List<ReportSchemeRow> lowSupplyDaysRows,
                         List<ReportSchemeRow> lowLpcdRows,
                         List<WeeklyReportOfficerRow> officerRows) throws IOException {
        ensureReportDirExists();

        LocalDate weekStart = LocalDate.parse(kpis.getWeekStart(), ISO);
        LocalDate weekEnd = LocalDate.parse(kpis.getWeekEnd(), ISO);
        String filename = ReportFileNaming.weeklyFilename(officerUserType, officerUserId, weekStart, weekEnd);
        Path filePath = Paths.get(reportDir, filename);

        WeeklyReportContent content = WeeklyReportContent.builder()
                .kpis(kpis)
                .officerName(officerName)
                .generatedDate(LocalDate.ofInstant(clock.instant(), REPORT_ZONE))
                .formats(ReportFormats.defaults())
                .dashboardUrl(dashboardUrl)
                .supportPhone(supportPhone)
                .noSupplyRows(noSupplyRows)
                .lowSupplyDaysRows(lowSupplyDaysRows)
                .lowLpcdRows(lowLpcdRows)
                .officerRows(officerRows)
                .build();

        try (PDDocument doc = new PDDocument()) {
            renderer.render(doc, layout, content);
            doc.save(filePath.toFile());
        }

        log.info("[WeeklyReportPdf] Generated {} (role={} noSupply={} lowDays={} lowLpcd={} officers={})",
                filename, officerUserType, noSupplyRows.size(), lowSupplyDaysRows.size(), lowLpcdRows.size(),
                officerRows != null ? officerRows.size() : 0);
        return filePath;
    }

    /** Test seam: fixes {@code {generatedDate}} so rendered output is reproducible. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    private void ensureReportDirExists() throws IOException {
        Path dir = Paths.get(reportDir);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            log.info("[WeeklyReportPdf] Created report directory: {}", dir);
        }
    }
}
