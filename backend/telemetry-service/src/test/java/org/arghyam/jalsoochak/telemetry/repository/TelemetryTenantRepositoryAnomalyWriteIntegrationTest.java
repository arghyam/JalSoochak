package org.arghyam.jalsoochak.telemetry.repository;

import org.arghyam.jalsoochak.telemetry.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Integration tests for the tenant-schema anomaly insert against a real PostgreSQL instance.
 *
 * <p>These exist because the columns were silently dropped once: the insert wrote only the
 * identifying columns, so {@code previous_reading} and {@code overridden_reading} stayed NULL in the
 * tenant schema while {@code analytics_schema} — fed by the Kafka event published beside it — held
 * both. A Mockito test on the service cannot catch that; only reading the row back can.
 *
 * <p>The second thing proven here is that a schema which predates V8 still takes the insert. The
 * column list is built from what the schema has, so a missing structured column must be skipped
 * rather than fail the write — an anomaly is never worth losing over a migration level.
 */
@Testcontainers
class TelemetryTenantRepositoryAnomalyWriteIntegrationTest {

    private static final String MIGRATED_SCHEMA = "tenant_as";
    private static final String PRE_MIGRATION_SCHEMA = "tenant_zz";
    private static final long SCHEME = 1L;
    private static final long OPERATOR = 9L;

    /** SUPPLY-PLAUSIBILITY type 10 — the refused-correction case that surfaced the gap. */
    private static final int TYPE_IMPLAUSIBLE_WATER_SUPPLY = 10;
    private static final int STATUS_OPEN = 1;

    private static final BigDecimal ATTEMPTED = new BigDecimal("9999.5");
    private static final BigDecimal BASELINE = new BigDecimal("220.4");
    private static final LocalDateTime BASELINE_AT = LocalDateTime.of(2026, 3, 2, 6, 30);

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void connect() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    private TelemetryTenantRepository repository() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        TelemetryTenantRepository repository =
                new TelemetryTenantRepository(jdbcTemplate, new PiiEncryptionService(key, key));
        // A fresh instance per test still shares no metadata cache with the container's real state
        // unless it is cleared, so the column probes run for real.
        repository.invalidateMetadataCaches();
        return repository;
    }

    @Test
    @DisplayName("writes the reading columns a refused correction is judged on")
    void writesTheStructuredReadingColumns() {
        repository().createTenantAnomalyRecord(MIGRATED_SCHEMA, TenantAnomalyRecord.builder()
                .userId(OPERATOR)
                .schemeId(SCHEME)
                .type(TYPE_IMPLAUSIBLE_WATER_SUPPLY)
                .reason("Correction rejected: implies an implausible daily water supply.")
                .status(STATUS_OPEN)
                .overriddenReading(ATTEMPTED)
                .previousReading(BASELINE)
                .previousReadingDate(BASELINE_AT)
                .retries(0)
                .build());

        Map<String, Object> row = latestAnomaly(MIGRATED_SCHEMA);

        assertEquals(0, ATTEMPTED.compareTo((BigDecimal) row.get("overridden_reading")),
                "the value that failed must be readable from the tenant row");
        assertEquals(0, BASELINE.compareTo((BigDecimal) row.get("previous_reading")),
                "so that (overridden_reading - previous_reading) * 1000 reproduces the litres");
        assertEquals(BASELINE_AT, ((java.sql.Timestamp) row.get("previous_reading_date")).toLocalDateTime());
        assertEquals(TYPE_IMPLAUSIBLE_WATER_SUPPLY, row.get("type"));
        assertEquals(STATUS_OPEN, row.get("status"));
        assertEquals(0, row.get("retries"));
        assertNotNull(row.get("created_at"));
    }

    @Test
    @DisplayName("writes the OCR columns an image-derived anomaly carries")
    void writesTheOcrColumns() {
        repository().createTenantAnomalyRecord(MIGRATED_SCHEMA, TenantAnomalyRecord.builder()
                .userId(OPERATOR)
                .schemeId(SCHEME)
                .type(2)
                .reason("Manual reading submitted as override.")
                .status(STATUS_OPEN)
                .aiReading(new BigDecimal("310.7"))
                .aiConfidencePercentage(new BigDecimal("82"))
                .overriddenReading(new BigDecimal("315"))
                .retries(3)
                .consecutiveDaysOverridden(5)
                .build());

        Map<String, Object> row = latestAnomaly(MIGRATED_SCHEMA);

        assertEquals(0, new BigDecimal("310.7").compareTo((BigDecimal) row.get("ai_reading")));
        assertEquals(0, new BigDecimal("82").compareTo((BigDecimal) row.get("ai_confidence_percentage")));
        assertEquals(3, row.get("retries"));
        assertEquals(5, row.get("consecutive_days_overridden"));
    }

    @Test
    @DisplayName("an unsupplied count falls to the column default rather than NULL")
    void omittedCountersKeepTheColumnDefault() {
        repository().createTenantAnomalyRecord(MIGRATED_SCHEMA, TenantAnomalyRecord.builder()
                .userId(OPERATOR)
                .schemeId(SCHEME)
                .type(9)
                .reason("Meter not working.")
                .status(STATUS_OPEN)
                .build());

        Map<String, Object> row = latestAnomaly(MIGRATED_SCHEMA);

        assertEquals(0, row.get("retries"),
                "omitting the column lets DEFAULT 0 stand; writing a literal NULL would not");
        assertEquals(0, row.get("consecutive_days_overridden"));
        assertNull(row.get("previous_reading"));
        assertEquals("Meter not working.", row.get("reason"));
    }

    @Test
    @DisplayName("a pre-V8 schema still takes the insert, under its own column names")
    void preMigrationSchemaSkipsTheColumnsItDoesNotHave() {
        repository().createTenantAnomalyRecord(PRE_MIGRATION_SCHEMA, TenantAnomalyRecord.builder()
                .userId(OPERATOR)
                .schemeId(SCHEME)
                .type(TYPE_IMPLAUSIBLE_WATER_SUPPLY)
                .reason("Correction rejected.")
                .status(STATUS_OPEN)
                // Supplied, but the schema has nowhere to put them. Losing the numbers beats losing
                // the anomaly.
                .overriddenReading(ATTEMPTED)
                .previousReading(BASELINE)
                .previousReadingDate(BASELINE_AT)
                .build());

        Map<String, Object> row = latestAnomaly(PRE_MIGRATION_SCHEMA);

        assertEquals("Correction rejected.", row.get("detail"),
                "pre-V8 the text still lives in detail");
        assertEquals(TYPE_IMPLAUSIBLE_WATER_SUPPLY, row.get("type"));
    }

    @Test
    @DisplayName("a missing NOT NULL field fails by name, not as a constraint violation")
    void rejectsAnIncompleteRecord() {
        assertThrows(NullPointerException.class, () -> TenantAnomalyRecord.builder()
                .schemeId(SCHEME)
                .type(TYPE_IMPLAUSIBLE_WATER_SUPPLY)
                .status(STATUS_OPEN)
                .build());
    }

    private static Map<String, Object> latestAnomaly(String schemaName) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM " + schemaName + ".anomaly_table ORDER BY id DESC LIMIT 1");
    }
}
