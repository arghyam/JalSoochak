package org.arghyam.jalsoochak.telemetry.repository;

import org.arghyam.jalsoochak.telemetry.channel.ReportingChannel;
import org.arghyam.jalsoochak.telemetry.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NUDGE-SCHEME: integration tests for how the reason writers treat rows from other days and the
 * operator's scheme selection, against a real PostgreSQL instance.
 *
 * <ul>
 *   <li>Writing a reason must not consume the {@code scheme-selection-*} placeholder: every later step
 *       of the same conversation (the "Others" follow-up, the meter-replaced photo) resolves the scheme
 *       from it, and once it is gone they fall back to the operator's first scheme.</li>
 *   <li>A reason written today must not move, overwrite or delete a reason from an earlier day.</li>
 * </ul>
 */
@Testcontainers
class TelemetryTenantRepositoryPendingReasonIntegrationTest {

    /** Own schema: these writers insert {@code quantity}, which the shared test schema omits. */
    private static final String SCHEMA = "tenant_pr";
    private static final long SCHEME = 1L;
    private static final long OTHER_SCHEME = 2L;
    private static final long OPERATOR = 9L;

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 30);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);
    private static final LocalDateTime NOW = TODAY.atTime(18, 5);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void createSchema() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("CREATE SCHEMA " + SCHEMA);
        jdbcTemplate.execute("""
                CREATE TABLE tenant_pr.flow_reading_table (
                    id                  SERIAL       PRIMARY KEY,
                    scheme_id           INTEGER      NOT NULL,
                    reading_at          TIMESTAMP    NOT NULL,
                    reading_date        DATE         NOT NULL,
                    extracted_reading   NUMERIC,
                    confirmed_reading   NUMERIC,
                    correlation_id      VARCHAR(255) NOT NULL,
                    quantity            NUMERIC      NOT NULL DEFAULT 0,
                    channel_id          INTEGER,
                    meter_change_reason TEXT,
                    issue_report_reason TEXT,
                    image_url           TEXT DEFAULT '',
                    reported_via_id     INTEGER,
                    created_by          INTEGER      NOT NULL,
                    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
                    updated_by          INTEGER,
                    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
                    deleted_at          TIMESTAMP
                )
                """);
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("TRUNCATE tenant_pr.flow_reading_table RESTART IDENTITY");
    }

    private TelemetryTenantRepository repository() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        TelemetryTenantRepository repository = new TelemetryTenantRepository(jdbcTemplate, new PiiEncryptionService(key, key));
        repository.invalidateMetadataCaches();
        return repository;
    }

    // ── scheme selection survives a reason ──────────────────────────────────────

    @Test
    void createIssueReportRecord_leavesTheSchemeSelectionPlaceholderInPlace() {
        insertRow(OTHER_SCHEME, TODAY, "scheme-selection-abc", 0, null, null);

        repository().createIssueReportRecord(SCHEMA, OTHER_SCHEME, OPERATOR, NOW, "issue-report-1", "No power", ReportingChannel.WHATSAPP);

        assertThat(repository().findLatestPendingSchemeSelectionForDate(SCHEMA, OPERATOR, TODAY))
                .hasValueSatisfying(sel -> assertThat(sel.schemeId()).isEqualTo(OTHER_SCHEME));
        assertThat(rowsWhere("issue_report_reason = 'No power'")).hasSize(1);
    }

    // ── how a reason row was reported ───────────────────────────────────────────

    @Test
    void reasonRowsRecordHowTheyWereReported() {
        repository().upsertPendingIssueReportRecord(SCHEMA, SCHEME, OPERATOR, NOW, "No power", ReportingChannel.WHATSAPP);
        repository().upsertPendingMeterChangeRecord(SCHEMA, OTHER_SCHEME, OPERATOR, NOW, "Meter stolen", ReportingChannel.WHATSAPP);

        assertThat(rowsWhere("issue_report_reason = 'No power'"))
                .singleElement()
                .satisfies(row -> assertThat(row.get("reported_via_id")).isEqualTo(ReportingChannel.WHATSAPP.getCode()));
        assertThat(rowsWhere("meter_change_reason = 'Meter stolen'"))
                .singleElement()
                .satisfies(row -> assertThat(row.get("reported_via_id")).isEqualTo(ReportingChannel.WHATSAPP.getCode()));
    }

    /** The reason is added to a reading reported through another channel, which keeps that channel. */
    @Test
    void anIssueReportOnTodaysReadingKeepsHowTheReadingWasReported() {
        long readingId = insertRow(SCHEME, TODAY, "bfm-1", 50, null, null);
        jdbcTemplate.update("UPDATE tenant_pr.flow_reading_table SET reported_via_id = ? WHERE id = ?",
                ReportingChannel.API.getCode(), readingId);

        repository().createIssueReportRecord(SCHEMA, SCHEME, OPERATOR, NOW, "issue-report-3", "No power", ReportingChannel.WHATSAPP);

        assertThat(rowById(readingId).get("reported_via_id")).isEqualTo(ReportingChannel.API.getCode());
    }

    @Test
    void createIssueReportRecord_stillAnnotatesTodaysRealReadingRow() {
        long readingId = insertRow(SCHEME, TODAY, "bfm-1", 50, null, null);

        long written = repository().createIssueReportRecord(SCHEMA, SCHEME, OPERATOR, NOW, "issue-report-2", "Meter replaced", ReportingChannel.WHATSAPP);

        assertThat(written).isEqualTo(readingId);
    }

    @Test
    void upsertPendingIssueReportRecord_leavesTheSchemeSelectionPlaceholderInPlace() {
        insertRow(OTHER_SCHEME, TODAY, "scheme-selection-abc", 0, null, null);

        repository().upsertPendingIssueReportRecord(SCHEMA, OTHER_SCHEME, OPERATOR, NOW, "No power", ReportingChannel.WHATSAPP);

        assertThat(repository().findLatestPendingSchemeSelectionForDate(SCHEMA, OPERATOR, TODAY))
                .hasValueSatisfying(sel -> assertThat(sel.schemeId()).isEqualTo(OTHER_SCHEME));
    }

    // ── earlier days are left alone ─────────────────────────────────────────────

    @Test
    void upsertPendingIssueReportRecord_keepsYesterdaysReasonOnYesterday() {
        long yesterdayId = insertRow(SCHEME, YESTERDAY, "issue-report-old", 0, null, "Pipe burst");

        repository().upsertPendingIssueReportRecord(SCHEMA, SCHEME, OPERATOR, NOW, "No power", ReportingChannel.WHATSAPP);

        Map<String, Object> yesterday = rowById(yesterdayId);
        assertThat(yesterday.get("reading_date").toString()).isEqualTo(YESTERDAY.toString());
        assertThat(yesterday.get("issue_report_reason")).isEqualTo("Pipe burst");
        assertThat(rowsWhere("reading_date = DATE '2026-09-30' AND issue_report_reason = 'No power'")).hasSize(1);
    }

    @Test
    void upsertPendingIssueReportRecord_updatesTodaysReason_whenAnsweredAgainToday() {
        long todayId = insertRow(SCHEME, TODAY, "issue-report-today", 0, null, "Pipe burst");

        String correlation = repository().upsertPendingIssueReportRecord(SCHEMA, SCHEME, OPERATOR, NOW, "No power", ReportingChannel.WHATSAPP);

        assertThat(correlation).isEqualTo("issue-report-today");
        assertThat(rowById(todayId).get("issue_report_reason")).isEqualTo("No power");
        assertThat(rowsWhere("issue_report_reason IS NOT NULL")).hasSize(1);
    }

    @Test
    void upsertPendingMeterChangeRecord_keepsEarlierDaysMeterChangeRows() {
        long yesterdayId = insertRow(SCHEME, YESTERDAY, "meter-change-old", 0, "Meter damaged", null);

        repository().upsertPendingMeterChangeRecord(SCHEMA, SCHEME, OPERATOR, NOW, "Meter stolen", ReportingChannel.WHATSAPP);

        Map<String, Object> yesterday = rowById(yesterdayId);
        assertThat(yesterday.get("deleted_at")).isNull();
        assertThat(yesterday.get("reading_date").toString()).isEqualTo(YESTERDAY.toString());
        assertThat(yesterday.get("meter_change_reason")).isEqualTo("Meter damaged");
        assertThat(rowsWhere("reading_date = DATE '2026-09-30' AND meter_change_reason = 'Meter stolen'")).hasSize(1);
    }

    @Test
    void upsertPendingMeterChangeRecord_stillCollapsesTodaysDuplicatePendingRows() {
        long older = insertRow(SCHEME, TODAY, "meter-change-a", 0, "Meter damaged", null);
        long newer = insertRow(SCHEME, TODAY, "meter-change-b", 0, "Meter damaged", null);

        String correlation = repository().upsertPendingMeterChangeRecord(SCHEMA, SCHEME, OPERATOR, NOW, "Meter stolen", ReportingChannel.WHATSAPP);

        assertThat(correlation).isEqualTo("meter-change-b");
        assertThat(rowById(newer).get("meter_change_reason")).isEqualTo("Meter stolen");
        assertThat(rowById(older).get("deleted_at")).isNotNull();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static long insertRow(long schemeId, LocalDate date, String correlationId, int confirmed,
                                  String meterChangeReason, String issueReason) {
        Number id = jdbcTemplate.queryForObject("""
                INSERT INTO tenant_pr.flow_reading_table
                    (scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, correlation_id,
                     meter_change_reason, issue_report_reason, created_by, updated_by)
                VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """, Number.class,
                schemeId, date.atTime(9, 0), date, confirmed, correlationId,
                meterChangeReason, issueReason, OPERATOR, OPERATOR);
        return id.longValue();
    }

    private static Map<String, Object> rowById(long id) {
        return jdbcTemplate.queryForMap("SELECT * FROM tenant_pr.flow_reading_table WHERE id = ?", id);
    }

    private static List<Map<String, Object>> rowsWhere(String predicate) {
        return jdbcTemplate.queryForList("SELECT * FROM tenant_pr.flow_reading_table WHERE deleted_at IS NULL AND " + predicate);
    }
}
