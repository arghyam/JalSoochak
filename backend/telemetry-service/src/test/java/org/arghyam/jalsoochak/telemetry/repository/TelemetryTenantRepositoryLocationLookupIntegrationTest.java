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

import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * LOCATION-AFFINITY: the coordinate lookups the boundary check reads, against real PostgreSQL.
 *
 * <p>These are integration rather than Mockito tests because the thing most likely to break is not
 * the row mapper but the column probe in front of it. {@code tenant_as} carries
 * {@code latitude}/{@code longitude}; {@code tenant_zz} predates them. A tenant on the older schema
 * must come back as "no location" — never an exception, because that would turn a missing migration
 * into a lost reading.
 */
@Testcontainers
@DisplayName("TelemetryTenantRepository — location lookups")
class TelemetryTenantRepositoryLocationLookupIntegrationTest {

    private static final String MIGRATED_SCHEMA = "tenant_as";
    private static final String PRE_MIGRATION_SCHEMA = "tenant_zz";

    private static final double SCHEME_LAT = 26.1445d;
    private static final double SCHEME_LNG = 91.7362d;

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
        repository.invalidateMetadataCaches();
        return repository;
    }

    private long insertScheme(String schema, Double latitude, Double longitude) {
        return jdbcTemplate.queryForObject(String.format("""
                INSERT INTO %s.scheme_master_table (fhtc_count, latitude, longitude)
                VALUES (0, ?, ?)
                RETURNING id
                """, schema), Long.class, latitude, longitude);
    }

    private long insertSchemeWithoutCoordinateColumns(String schema) {
        return jdbcTemplate.queryForObject(String.format("""
                INSERT INTO %s.scheme_master_table (fhtc_count) VALUES (0) RETURNING id
                """, schema), Long.class);
    }

    private long insertReading(String schema, Double latitude, Double longitude) {
        return jdbcTemplate.queryForObject(String.format("""
                INSERT INTO %s.flow_reading_table
                    (scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading,
                     correlation_id, created_by, latitude, longitude)
                VALUES (1, NOW(), CURRENT_DATE, 0, 0, ?, 1, ?, ?)
                RETURNING id
                """, schema), Long.class, "loc-" + System.nanoTime(), latitude, longitude);
    }

    @Test
    @DisplayName("a scheme with coordinates returns both halves")
    void schemeWithCoordinates() {
        long schemeId = insertScheme(MIGRATED_SCHEMA, SCHEME_LAT, SCHEME_LNG);

        Optional<TelemetryGeoPoint> point = repository().findSchemeLocation(MIGRATED_SCHEMA, schemeId);

        assertThat(point).isPresent();
        assertThat(point.get().latitude()).isEqualTo(SCHEME_LAT);
        assertThat(point.get().longitude()).isEqualTo(SCHEME_LNG);
        assertThat(point.get().isComplete()).isTrue();
    }

    @Test
    @DisplayName("a scheme with NULL coordinates is present but incomplete, not absent")
    void schemeWithNullCoordinates() {
        // The distinction matters: "row exists, no coordinates" is a master-data gap an admin can
        // fix, while "no row" is a resolution bug. Both skip the check, but they are counted the
        // same way only because the policy treats an incomplete pair as no location.
        long schemeId = insertScheme(MIGRATED_SCHEMA, null, null);

        Optional<TelemetryGeoPoint> point = repository().findSchemeLocation(MIGRATED_SCHEMA, schemeId);

        assertThat(point).isPresent();
        assertThat(point.get().isComplete()).isFalse();
    }

    @Test
    @DisplayName("half a coordinate pair is incomplete")
    void schemeWithOnlyLatitude() {
        long schemeId = insertScheme(MIGRATED_SCHEMA, SCHEME_LAT, null);

        Optional<TelemetryGeoPoint> point = repository().findSchemeLocation(MIGRATED_SCHEMA, schemeId);

        assertThat(point).isPresent();
        assertThat(point.get().isComplete()).isFalse();
    }

    @Test
    @DisplayName("a missing scheme returns empty")
    void missingScheme() {
        assertThat(repository().findSchemeLocation(MIGRATED_SCHEMA, 999_999L)).isEmpty();
    }

    @Test
    @DisplayName("a null scheme id returns empty without querying")
    void nullSchemeId() {
        assertThat(repository().findSchemeLocation(MIGRATED_SCHEMA, null)).isEmpty();
    }

    @Test
    @DisplayName("a schema without the coordinate columns degrades to no location rather than throwing")
    void preMigrationSchemaDegrades() {
        // This is why the lookup goes through resolveSelectColumn. A tenant that has not taken V6/V7
        // must still be able to submit readings; the boundary check simply has no opinion there.
        long schemeId = insertSchemeWithoutCoordinateColumns(PRE_MIGRATION_SCHEMA);

        Optional<TelemetryGeoPoint> point = repository().findSchemeLocation(PRE_MIGRATION_SCHEMA, schemeId);

        assertThat(point).isPresent();
        assertThat(point.get().isComplete()).isFalse();
    }

    @Test
    @DisplayName("a reading's submitted location reads back from the row")
    void readingLocation() {
        long readingId = insertReading(MIGRATED_SCHEMA, 26.1553d, 91.7362d);

        Optional<TelemetryGeoPoint> point = repository().findReadingLocation(MIGRATED_SCHEMA, readingId);

        assertThat(point).isPresent();
        assertThat(point.get().latitude()).isEqualTo(26.1553d);
        assertThat(point.get().longitude()).isEqualTo(91.7362d);
    }

    @Test
    @DisplayName("a reading with no location yet is present but incomplete")
    void readingWithoutLocation() {
        long readingId = insertReading(MIGRATED_SCHEMA, null, null);

        assertThat(repository().findReadingLocation(MIGRATED_SCHEMA, readingId))
                .hasValueSatisfying(point -> assertThat(point.isComplete()).isFalse());
    }

    @Test
    @DisplayName("coordinates written onto a placeholder survive onto the row the reading reuses")
    void coordinatesSurviveThePlaceholderHandover() {
        // THE fact the whole deferred-anomaly design rests on. /location writes coordinates onto a
        // placeholder row; the image submission that follows reuses that same row rather than
        // inserting a new one, so the coordinates are still there when the reading is persisted and
        // the boundary check can re-run from stored data with no marker column and no in-memory state.
        // Asserted against a real database because it is a property of the placeholder predicate,
        // not of any one method.
        TelemetryTenantRepository repository = repository();
        long schemeId = insertScheme(MIGRATED_SCHEMA, SCHEME_LAT, SCHEME_LNG);
        long operatorId = 4321L;

        long placeholderId = jdbcTemplate.queryForObject(String.format("""
                INSERT INTO %s.flow_reading_table
                    (scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading,
                     correlation_id, created_by)
                VALUES (?, NOW(), CURRENT_DATE, 0, 0, ?, ?)
                RETURNING id
                """, MIGRATED_SCHEMA), Long.class,
                schemeId, "location-" + java.util.UUID.randomUUID(), operatorId);

        repository.updateReadingLocation(MIGRATED_SCHEMA, placeholderId,
                new java.math.BigDecimal("26.1553"), new java.math.BigDecimal("91.7362"), operatorId);

        Optional<Long> reused = repository.findLatestPlaceholderFlowReadingIdForDate(
                MIGRATED_SCHEMA, schemeId, operatorId, java.time.LocalDate.now());

        assertThat(reused).contains(placeholderId);
        assertThat(repository.findReadingLocation(MIGRATED_SCHEMA, reused.orElseThrow()))
                .hasValueSatisfying(point -> {
                    assertThat(point.latitude()).isEqualTo(26.1553d);
                    assertThat(point.longitude()).isEqualTo(91.7362d);
                });
    }

    @Test
    @DisplayName("the schema name is still validated, so neither lookup widens the injection surface")
    void validatesSchemaName() {
        assertThatThrownBy(() -> repository().findSchemeLocation("tenant_as; DROP TABLE x", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository().findReadingLocation("Tenant_AS", 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
