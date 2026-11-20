package org.arghyam.jalsoochak.message.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.arghyam.jalsoochak.message.dto.DailyReportKpis;
import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;
import org.arghyam.jalsoochak.message.report.ResolvedReportLayout;
import org.arghyam.jalsoochak.message.report.render.DailyReportContent;
import org.arghyam.jalsoochak.message.report.render.LayoutPdfRenderer;
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
 * Renders the Daily Water Service Situation Report PDF for one officer, in the layout it is given.
 *
 * <p>The built-in layout follows {@code SO daily report.docx}: a summary of scheme counts, the
 * household split with percentages, Average LPCD (of the schemes that supplied) and the anomaly
 * count, then the schemes with no water supply and the schemes with anomalous submissions.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DailyReportPdfService {

    @Value("${daily-report.report.dir:${escalation.report.dir:/tmp/escalation-reports/}}")
    private String reportDir;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    /** The platform reports on IST days throughout; the generation date must agree. */
    private static final ZoneId REPORT_ZONE = ZoneId.of("Asia/Kolkata");

    private final LayoutPdfRenderer renderer;

    /** Source of {@code {generatedDate}}; read in {@link #REPORT_ZONE} whatever the clock's own zone. */
    private Clock clock = Clock.systemUTC();

    /**
     * Generates the report PDF and returns the path it was written to.
     *
     * <p>The resolved path, not just the filename: this service writes to
     * {@code daily-report.report.dir} ({@code DAILY_REPORT_DIR}), which a caller rebuilding the path
     * from {@code escalation.report.dir} does not see. Handing back the path leaves nothing to
     * re-derive and no way for the two to drift.</p>
     *
     * @param officerUserId the filename discriminator. Deliberately the id and not the officer's
     *                      name: names collide, and the reports share one storage bucket, so a
     *                      collision would expose one officer's report to another.
     * @param layout        what the report shows, and where, with the tenant's settings
     */
    public Path generate(DailyReportKpis kpis, long officerUserId, String officerName, String officerUserType,
                         ResolvedReportLayout layout,
                         List<ReportSchemeRow> noSupplyRows,
                         List<ReportSchemeRow> anomalyRows) throws IOException {
        ensureReportDirExists();

        LocalDate reportDate = LocalDate.parse(kpis.getReportDate(), ISO);
        String filename = ReportFileNaming.dailyFilename(officerUserType, officerUserId, reportDate);
        Path filePath = Paths.get(reportDir, filename);

        DailyReportContent content = DailyReportContent.builder()
                .kpis(kpis)
                .officerName(officerName)
                .generatedDate(LocalDate.ofInstant(clock.instant(), REPORT_ZONE))
                .formats(layout.formats())
                .dashboardUrl(layout.dashboardUrl())
                .supportPhone(layout.supportPhone())
                .noSupplyRows(noSupplyRows)
                .anomalyRows(anomalyRows)
                .build();

        try (PDDocument doc = new PDDocument()) {
            renderer.render(doc, layout.layout(), content);
            doc.save(filePath.toFile());
        }

        log.info("[DailyReportPdf] Generated {} ({} no-supply rows, {} anomaly rows)",
                filename, noSupplyRows.size(), anomalyRows.size());
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
            log.info("[DailyReportPdf] Created report directory: {}", dir);
        }
    }
}
