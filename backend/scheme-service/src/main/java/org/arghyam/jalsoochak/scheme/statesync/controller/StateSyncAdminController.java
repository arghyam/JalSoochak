package org.arghyam.jalsoochak.scheme.statesync.controller;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.scheme.statesync.config.StateSyncProperties;
import org.arghyam.jalsoochak.scheme.statesync.run.RunKind;
import org.arghyam.jalsoochak.scheme.statesync.run.StateSyncRunRepository;
import org.arghyam.jalsoochak.scheme.statesync.run.StateSyncRunner;
import org.arghyam.jalsoochak.scheme.statesync.run.StateSyncRunner.RunResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Operator surface for the state master-data sync: start a run, refresh one scheme, read the run
 * history and the issue queue. Every endpoint is scoped to the one configured tenant; with
 * {@code state-sync.enabled=false} the write endpoints answer 409 and nothing is called upstream.
 */
@RestController
@RequestMapping("/api/v1/scheme/state-sync")
@RequiredArgsConstructor
public class StateSyncAdminController {

    private static final String ADMIN = "hasAnyRole('SUPER_USER','STATE_ADMIN','SUPER_STATE_ADMIN') "
            + "and @schemeSecurity.canAccessTenant(#tenantCode, authentication)";

    private final StateSyncProperties properties;
    private final StateSyncRunner runner;
    private final StateSyncRunRepository runRepository;

    /**
     * The sync's settings for this tenant. Answers 200 even when the sync is not set up for the tenant
     * ({@code "configured": false}), so it can be used to find out why the other endpoints answer 404;
     * the tenant the sync does serve is not named to another tenant's admin.
     */
    @PreAuthorize(ADMIN)
    @GetMapping("/config")
    public Map<String, Object> config(@RequestParam String tenantCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!isConfiguredTenant(tenantCode)) {
            body.put("configured", false);
            body.put("enabled", properties.isEnabled());
            body.put("tenantCode", null);
            return body;
        }
        body.put("configured", true);
        body.put("enabled", properties.isEnabled());
        body.put("mode", properties.getMode());
        body.put("tenantCode", properties.getTenantCode());
        body.put("deltaCron", properties.getDeltaCron());
        body.put("fullCron", properties.getFullCron());
        body.put("zone", properties.getZone());
        body.put("retireOnEmptyList", properties.isRetireOnEmptyList());
        body.put("archiveSpareReadingDays", properties.getArchiveSpareReadingDays());
        return body;
    }

    /** Starts a FULL or DELTA run in the background. 202 with the run id, or 409 when one is already running. */
    @PreAuthorize(ADMIN)
    @PostMapping("/runs")
    public ResponseEntity<Map<String, Object>> startRun(@RequestParam String tenantCode, @RequestParam RunKind kind,
                                                        Authentication authentication) {
        requireConfiguredTenant(tenantCode);
        if (kind == RunKind.SCHEME_REFRESH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "use POST /schemes/refresh for one scheme");
        }
        Optional<Long> runId = runner.startAsync(kind, triggeredBy(authentication));
        if (runId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "another state-sync run is in progress");
        }
        return ResponseEntity.accepted().body(Map.of("runId", runId.get(), "kind", kind, "mode", properties.getMode()));
    }

    /**
     * Re-pulls one scheme now and reconciles it, synchronously.
     *
     * @param ref the upstream scheme code (e.g. {@code SCH-000008}) or the IMIS id (digits only)
     */
    @PreAuthorize(ADMIN)
    @PostMapping("/schemes/refresh")
    public RunResult refreshScheme(@RequestParam String tenantCode, @RequestParam String ref,
                                   Authentication authentication) {
        requireConfiguredTenant(tenantCode);
        return runner.runNow(RunKind.SCHEME_REFRESH, ref, triggeredBy(authentication))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "another state-sync run is in progress"));
    }

    @PreAuthorize(ADMIN)
    @GetMapping("/runs")
    public List<StateSyncRunRepository.RunRow> runs(@RequestParam String tenantCode,
                                                    @RequestParam(defaultValue = "20") int limit) {
        return runRepository.listRuns(tenant(tenantCode).id(), clamp(limit, 1, 200));
    }

    @PreAuthorize(ADMIN)
    @GetMapping("/issues")
    public List<StateSyncRunRepository.IssueRow> issues(@RequestParam String tenantCode,
                                                        @RequestParam(required = false) Long runId,
                                                        @RequestParam(required = false) String category,
                                                        @RequestParam(defaultValue = "100") int limit,
                                                        @RequestParam(defaultValue = "0") int offset) {
        return runRepository.listIssues(tenant(tenantCode).id(), runId, category, clamp(limit, 1, 1000),
                Math.max(0, offset));
    }

    /**
     * Our schemes the last successful full run did not find upstream, for review with the JJM team:
     * {@code NOT_IN_JJM} (never matched any upstream scheme) and {@code NO_LONGER_LISTED} (carries an
     * upstream code the full list no longer has). {@code format=csv} downloads it as a spreadsheet.
     */
    @PreAuthorize(ADMIN)
    @GetMapping("/unmatched-schemes")
    public ResponseEntity<?> unmatchedSchemes(@RequestParam String tenantCode,
                                              @RequestParam(defaultValue = "json") String format) {
        StateSyncRunRepository.ReviewList list = runRepository.unmatchedSchemes(tenant(tenantCode).id());
        if (!"csv".equalsIgnoreCase(format)) {
            return ResponseEntity.ok(list);
        }
        StringBuilder csv = new StringBuilder("reason,scheme_id,scheme_name,jjm_code,imis_id,smt_id,last_reading_date\n");
        for (StateSyncRunRepository.ReviewRow row : list.schemes()) {
            csv.append(String.join(",", cell(row.reason()), cell(row.schemeId()), cell(row.schemeName()),
                    cell(row.stateSchemeCode()), cell(row.centreSchemeId()), cell(row.stateSchemeId()),
                    cell(row.lastReadingDate()))).append('\n');
        }
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"unmatched-schemes-"
                        + tenantCode.trim().toLowerCase(Locale.ROOT) + ".csv\"")
                .body(csv.toString());
    }

    /** One CSV cell; quoted when needed, and a leading formula character neutralised for spreadsheets. */
    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = value.toString();
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            text = "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    @ExceptionHandler(StateSyncRunner.SyncDisabledException.class)
    public ResponseEntity<Map<String, String>> disabled(StateSyncRunner.SyncDisabledException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(StateSyncRunner.SyncMisconfiguredException.class)
    public ResponseEntity<Map<String, String>> misconfigured(StateSyncRunner.SyncMisconfiguredException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("message", e.getMessage()));
    }

    private StateSyncRunRepository.Tenant tenant(String tenantCode) {
        requireConfiguredTenant(tenantCode);
        return runRepository.findTenant(tenantCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown tenant"));
    }

    private void requireConfiguredTenant(String tenantCode) {
        if (!isConfiguredTenant(tenantCode)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "state sync is not configured for this tenant");
        }
    }

    private boolean isConfiguredTenant(String tenantCode) {
        return properties.getTenantCode() != null && properties.getTenantCode().equalsIgnoreCase(tenantCode.trim());
    }

    private static String triggeredBy(Authentication authentication) {
        String who = "ADMIN:" + (authentication == null ? "unknown" : authentication.getName());
        return who.length() <= 64 ? who : who.substring(0, 64);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
