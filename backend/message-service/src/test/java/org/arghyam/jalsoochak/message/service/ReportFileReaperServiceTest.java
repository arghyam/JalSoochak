package org.arghyam.jalsoochak.message.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for {@link ReportFileReaperService} — verifies stale report PDFs are reaped while
 * recent files (still within the intentional short-term retention window) and non-PDF files are
 * left untouched, and that a missing directory is a safe no-op.
 */
class ReportFileReaperServiceTest {

    @TempDir
    Path tempDir;

    private ReportFileReaperService newReaper(long maxAgeHours) {
        ReportFileReaperService reaper = new ReportFileReaperService();
        ReflectionTestUtils.setField(reaper, "reportDir", tempDir.toString() + "/");
        ReflectionTestUtils.setField(reaper, "maxAgeHours", maxAgeHours);
        return reaper;
    }

    private Path writeFileAged(String name, Instant lastModified) throws Exception {
        Path file = tempDir.resolve(name);
        Files.write(file, new byte[]{1, 2, 3});
        Files.setLastModifiedTime(file, FileTime.from(lastModified));
        return file;
    }

    @Test
    void reapStaleReports_deletesOldPdfs_butKeepsRecentOnes() throws Exception {
        Path stale = writeFileAged("daily_report_SECTION_OFFICER_500_2026-07-01.pdf",
                Instant.now().minus(10, ChronoUnit.HOURS));
        Path fresh = writeFileAged("daily_report_SECTION_OFFICER_600_2026-07-20.pdf",
                Instant.now().minus(1, ChronoUnit.HOURS));

        newReaper(6).reapStaleReports();

        assertThat(stale.toFile()).doesNotExist();
        assertThat(fresh.toFile()).exists();
    }

    @Test
    void reapStaleReports_ignoresNonPdfFiles_evenWhenOld() throws Exception {
        Path staleTxt = writeFileAged("scratch.txt", Instant.now().minus(48, ChronoUnit.HOURS));

        newReaper(6).reapStaleReports();

        assertThat(staleTxt.toFile()).exists();
    }

    @Test
    void reapStaleReports_missingDirectory_isNoOp() {
        ReportFileReaperService reaper = new ReportFileReaperService();
        ReflectionTestUtils.setField(reaper, "reportDir", tempDir.resolve("nope").toString());
        ReflectionTestUtils.setField(reaper, "maxAgeHours", 6L);

        assertThatCode(reaper::reapStaleReports).doesNotThrowAnyException();
    }
}
