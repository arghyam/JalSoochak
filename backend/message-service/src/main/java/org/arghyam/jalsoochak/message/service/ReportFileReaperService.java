package org.arghyam.jalsoochak.message.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * Periodically reaps stale report PDFs left in the shared report directory.
 *
 * <p>On the happy path a generated report is uploaded to MinIO and its local copy is deleted
 * immediately (see {@code NotificationEventRouter}). Two paths intentionally or unintentionally
 * leave a file behind: a MinIO-upload failure <em>retains</em> the local PDF on purpose for
 * recovery, and a crash between generation and deletion leaks one. Across many officers × days ×
 * retries these accumulate and can exhaust disk on the message-service host.</p>
 *
 * <p>This reaper deletes {@code *.pdf} files whose last-modified time is older than
 * {@code daily-report.reaper.max-age-hours}, bounding disk growth without touching the intentional
 * short-term retention (recent files are left alone). It operates on the same directory the daily
 * report and escalation PDFs are written to.</p>
 */
@Service
@Slf4j
public class ReportFileReaperService {

    @Value("${daily-report.report.dir:${escalation.report.dir:/tmp/escalation-reports/}}")
    private String reportDir;

    @Value("${daily-report.reaper.max-age-hours:6}")
    private long maxAgeHours;

    /**
     * Deletes report PDFs older than the configured max age. Scheduled hourly by default; both the
     * interval and the initial delay are configurable so a deployment can tune or effectively pause
     * reaping without a code change.
     */
    @Scheduled(fixedDelayString = "${daily-report.reaper.interval-ms:3600000}",
            initialDelayString = "${daily-report.reaper.initial-delay-ms:3600000}")
    public void reapStaleReports() {
        Path dir = Paths.get(reportDir);
        if (!Files.isDirectory(dir)) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofHours(maxAgeHours));
        // Collect first, then delete — avoids mutating the directory while its stream is open.
        List<Path> stale;
        try (Stream<Path> files = Files.list(dir)) {
            stale = files.filter(f -> isStaleReport(f, cutoff)).toList();
        } catch (IOException e) {
            log.warn("[ReportReaper] could not list report directory {}: {}", dir, e.getMessage());
            return;
        }
        int deleted = 0;
        for (Path file : stale) {
            if (tryDelete(file)) {
                deleted++;
            }
        }
        if (deleted > 0) {
            log.info("[ReportReaper] deleted {} stale report file(s) older than {}h from {}",
                    deleted, maxAgeHours, dir);
        }
    }

    private boolean isStaleReport(Path file, Instant cutoff) {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        if (!file.getFileName().toString().toLowerCase().endsWith(".pdf")) {
            return false;
        }
        try {
            return Files.getLastModifiedTime(file).toInstant().isBefore(cutoff);
        } catch (IOException e) {
            log.warn("[ReportReaper] could not read last-modified time of {}: {}", file, e.getMessage());
            return false;
        }
    }

    private boolean tryDelete(Path file) {
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("[ReportReaper] could not delete stale report {}: {}", file, e.getMessage());
            return false;
        }
    }
}
