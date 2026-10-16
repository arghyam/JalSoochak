package org.arghyam.jalsoochak.telemetry.repository;

import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NUDGE-SCHEME: {@link TelemetryTenantRepository#findSchemeOfLatestWhatsAppRowForDate}, the lookup that
 * keeps a conversation on its selected scheme once a meter photo has been written onto the
 * {@code scheme-selection-*} placeholder, against a real PostgreSQL instance.
 *
 * <p>The operator is mapped to {@link #FIRST} and {@link #SELECTED}; {@link #UNMAPPED} is a scheme
 * they were taken off.
 */
@Testcontainers
class TelemetryTenantRepositorySchemeSelectionIntegrationTest {

    private static final String SCHEMA = "tenant_ss";
    /** A tenant schema from before V61, with no {@code reported_via_id}. */
    private static final String LEGACY_SCHEMA = "tenant_ss_legacy";

    private static final long FIRST = 1L;
    private static final long SELECTED = 2L;
    private static final long UNMAPPED = 3L;
    private static final long OPERATOR = 9L;
    private static final long OTHER_OPERATOR = 10L;

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 16);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void createSchemas() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
        createSchema(SCHEMA, "reported_via_id     INTEGER,");
        createSchema(LEGACY_SCHEMA, "");
    }

    private static void createSchema(String schema, String reportedViaColumn) {
        jdbcTemplate.execute("CREATE SCHEMA " + schema);
        jdbcTemplate.execute("""
                CREATE TABLE %s.flow_reading_table (
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
                    %s
                    created_by          INTEGER      NOT NULL,
                    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
                    updated_by          INTEGER,
                    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
                    deleted_at          TIMESTAMP
                )
                """.formatted(schema, reportedViaColumn));
        jdbcTemplate.execute("""
                CREATE TABLE %s.scheme_master_table (
                    id         INTEGER PRIMARY KEY,
                    deleted_at TIMESTAMP
                )
                """.formatted(schema));
        jdbcTemplate.execute("""
                CREATE TABLE %s.user_scheme_mapping_table (
                    id         SERIAL  PRIMARY KEY,
                    user_id    INTEGER NOT NULL,
                    scheme_id  INTEGER NOT NULL,
                    status     INTEGER NOT NULL,
                    deleted_at TIMESTAMP
                )
                """.formatted(schema));
    }

    @BeforeEach
    void seedMappings() {
        for (String schema : new String[]{SCHEMA, LEGACY_SCHEMA}) {
            jdbcTemplate.execute("TRUNCATE " + schema + ".flow_reading_table RESTART IDENTITY");
            jdbcTemplate.execute("TRUNCATE " + schema + ".user_scheme_mapping_table RESTART IDENTITY");
            jdbcTemplate.execute("TRUNCATE " + schema + ".scheme_master_table");
            jdbcTemplate.update("INSERT INTO " + schema + ".scheme_master_table (id) VALUES (?), (?), (?)",
                    FIRST, SELECTED, UNMAPPED);
            jdbcTemplate.update("INSERT INTO " + schema + ".user_scheme_mapping_table (user_id, scheme_id, status) "
                    + "VALUES (?, ?, 1), (?, ?, 1), (?, ?, 0)",
                    OPERATOR, FIRST, OPERATOR, SELECTED, OPERATOR, UNMAPPED);
        }
    }

    private TelemetryTenantRepository repository() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        TelemetryTenantRepository repository = new TelemetryTenantRepository(jdbcTemplate, new PiiEncryptionService(key, key));
        repository.invalidateMetadataCaches();
        return repository;
    }

    /** The user's report: pick the second scheme, send a photo, then type a correction. */
    @Test
    void aPhotoOnTheSelectedSchemeStillNamesThatScheme() {
        TelemetryTenantRepository repository = repository();
        repository.upsertPendingSchemeSelectionRecord(
                SCHEMA, SELECTED, OPERATOR, TODAY.atTime(9, 0), ReportingChannel.WHATSAPP);
        long placeholderId = repository.findLatestPendingSchemeSelectionForDate(SCHEMA, OPERATOR, TODAY)
                .orElseThrow().id();

        // What BfmReadingService.createReading does with the photo: it reuses the placeholder.
        repository.updateFlowReadingFromIngestion(SCHEMA, placeholderId, TODAY.atTime(9, 2),
                new BigDecimal("1200"), new BigDecimal("1200"), "ocr-request-1", null,
                "https://storage.example.org/bfm/x.jpg", null, OPERATOR, ReadingChannel.BFM, "m3",
                ReportingChannel.WHATSAPP);

        // The cause: the selection no longer reads as pending...
        assertThat(repository.findLatestPendingSchemeSelectionForDate(SCHEMA, OPERATOR, TODAY)).isEmpty();
        // ...but the conversation's latest row still names the scheme the operator picked.
        assertThat(repository.findSchemeOfLatestWhatsAppRowForDate(SCHEMA, OPERATOR, TODAY)).contains(SELECTED);
    }

    /** Re-selecting re-points an older placeholder in place, so its id predates other schemes' rows. */
    @Test
    void theLatestReadingTimeWinsOverTheLatestId() {
        insertRow(SELECTED, TODAY.atTime(10, 0), ReportingChannel.WHATSAPP);
        insertRow(FIRST, TODAY.atTime(9, 0), ReportingChannel.WHATSAPP);

        assertThat(repository().findSchemeOfLatestWhatsAppRowForDate(SCHEMA, OPERATOR, TODAY)).contains(SELECTED);
    }

    @Test
    void aReadingSubmittedThroughTheApiDoesNotRedirectTheConversation() {
        insertRow(SELECTED, TODAY.atTime(9, 0), ReportingChannel.WHATSAPP);
        insertRow(FIRST, TODAY.atTime(10, 0), ReportingChannel.API);

        assertThat(repository().findSchemeOfLatestWhatsAppRowForDate(SCHEMA, OPERATOR, TODAY)).contains(SELECTED);
    }

    @Test
    void aSchemeTheOperatorWasTakenOffIsNeverNamed() {
        insertRow(SELECTED, TODAY.atTime(9, 0), ReportingChannel.WHATSAPP);
        insertRow(UNMAPPED, TODAY.atTime(10, 0), ReportingChannel.WHATSAPP);

        assertThat(repository().findSchemeOfLatestWhatsAppRowForDate(SCHEMA, OPERATOR, TODAY)).contains(SELECTED);
    }

    @Test
    void anotherDayAnotherOperatorAndADeletedRowAreIgnored() {
        insertRow(SELECTED, TODAY.minusDays(1).atTime(9, 0), ReportingChannel.WHATSAPP);
        insertRow(SELECTED, TODAY.atTime(9, 0), ReportingChannel.WHATSAPP, OTHER_OPERATOR);
        long deleted = insertRow(SELECTED, TODAY.atTime(9, 5), ReportingChannel.WHATSAPP);
        jdbcTemplate.update("UPDATE tenant_ss.flow_reading_table SET deleted_at = NOW() WHERE id = ?", deleted);

        assertThat(repository().findSchemeOfLatestWhatsAppRowForDate(SCHEMA, OPERATOR, TODAY)).isEmpty();
    }

    @Test
    void aSchemaWithoutReportedViaHasNoAnswer() {
        jdbcTemplate.update("""
                INSERT INTO tenant_ss_legacy.flow_reading_table
                    (scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, correlation_id, created_by)
                VALUES (?, ?, ?, 0, 0, 'scheme-selection-legacy', ?)
                """, SELECTED, TODAY.atTime(9, 0), TODAY, OPERATOR);

        assertThat(repository().findSchemeOfLatestWhatsAppRowForDate(LEGACY_SCHEMA, OPERATOR, TODAY)).isEmpty();
    }

    private long insertRow(long schemeId, LocalDateTime readingAt, ReportingChannel reportedVia) {
        return insertRow(schemeId, readingAt, reportedVia, OPERATOR);
    }

    private long insertRow(long schemeId, LocalDateTime readingAt, ReportingChannel reportedVia, long operatorId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tenant_ss.flow_reading_table
                    (scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, correlation_id,
                     reported_via_id, created_by)
                VALUES (?, ?, ?, 50, 50, 'row', ?, ?)
                RETURNING id
                """, Long.class, schemeId, readingAt, readingAt.toLocalDate(), reportedVia.getCode(), operatorId);
    }
}
