package org.arghyam.jalsoochak.analytics.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SchemeReassignmentRepository} against the real,
 * Flyway-migrated warehouse schema.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(SchemeReassignmentRepository.class)
class SchemeReassignmentRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_scheme_sync_test")
            .withUsername("postgres")
            .withPassword("postgres");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.schemas", () -> "analytics_schema");
    }

    private static final int TENANT = 1;
    private static final int SCHEME = 10;
    private static final int PLACEHOLDER = 99;
    private static final LocalDate D1 = LocalDate.of(2026, 7, 10);
    private static final LocalDate D2 = LocalDate.of(2026, 8, 2);

    @Autowired
    private SchemeReassignmentRepository reassignmentRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.execute("""
                TRUNCATE TABLE analytics_schema.fact_meter_reading_table, analytics_schema.fact_operator_attendance_table,
                    analytics_schema.fact_anomaly_table, analytics_schema.fact_escalation_table,
                    analytics_schema.fact_scheme_daily_table, analytics_schema.dim_scheme_table,
                    analytics_schema.dim_tenant_table RESTART IDENTITY CASCADE
                """);
        jdbc.update("INSERT INTO analytics_schema.dim_tenant_table (tenant_id, state_code, title, country_code, status, "
                + "created_at, updated_at) VALUES (1, 'as', 'Assam', 'IN', 1, NOW(), NOW())");
        for (LocalDate d : List.of(D1, D2)) {
            jdbc.update("INSERT INTO analytics_schema.dim_date_table (date_key, full_date) VALUES (?, ?) ON CONFLICT DO NOTHING",
                    Integer.parseInt(d.toString().replace("-", "")), d);
        }
    }

    private void reading(int schemeId, LocalDate date, Long sourceReadingId) {
        jdbc.update("INSERT INTO analytics_schema.fact_meter_reading_table (tenant_id, scheme_id, user_id, confirmed_reading, "
                        + "reading_at, reading_date, channel, created_at, submission_status, reading_type, source_reading_id) "
                        + "VALUES (1, ?, 7, 100, ?, ?, 1, NOW(), 1, 0, ?)",
                schemeId, date.atTime(8, 0), date, sourceReadingId);
    }

    private void attendance(int schemeId, LocalDate date, int userId) {
        jdbc.update("INSERT INTO analytics_schema.fact_operator_attendance_table (tenant_id, date_key, user_id, scheme_id, "
                        + "attendance, created_at, updated_at) VALUES (1, ?, ?, ?, 1, NOW(), NOW())",
                Integer.parseInt(date.toString().replace("-", "")), userId, schemeId);
    }

    @Test
    void movesLegacyAndIdentifiedReadingsAndReportsTheirDates() {
        reading(PLACEHOLDER, D1, null);       // before V56: no source id
        reading(PLACEHOLDER, D2, 4711L);
        reading(PLACEHOLDER, D2, 4712L);
        reading(SCHEME, D1, 1L);

        List<LocalDate> dates = reassignmentRepository.moveMeterReadings(TENANT, PLACEHOLDER, SCHEME);

        assertThat(dates).containsExactly(D1, D2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analytics_schema.fact_meter_reading_table WHERE scheme_id = ?",
                Integer.class, SCHEME)).isEqualTo(4);
        assertThat(reassignmentRepository.moveMeterReadings(TENANT, PLACEHOLDER, SCHEME)).isEmpty();
    }

    @Test
    void attendanceAlreadyOnTheRealSchemeIsKeptOnce() {
        attendance(PLACEHOLDER, D1, 7);
        attendance(SCHEME, D1, 7);
        attendance(PLACEHOLDER, D2, 7);

        int moved = reassignmentRepository.moveAttendance(TENANT, PLACEHOLDER, SCHEME);

        assertThat(moved).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT date_key FROM analytics_schema.fact_operator_attendance_table "
                + "WHERE scheme_id = ? ORDER BY 1", Integer.class, SCHEME)).containsExactly(20260710, 20260802);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analytics_schema.fact_operator_attendance_table "
                + "WHERE scheme_id = ?", Integer.class, PLACEHOLDER)).isZero();
    }

    @Test
    void dropsThePlaceholdersDimAndDailyRows() {
        jdbc.update("INSERT INTO analytics_schema.dim_scheme_table (scheme_id, tenant_id, scheme_name, state_scheme_id, "
                + "centre_scheme_id, parent_lgd_location_id, operating_status) VALUES (?, 1, 'Auto', 0, 0, 1, 0)", PLACEHOLDER);
        jdbc.update("INSERT INTO analytics_schema.fact_scheme_daily_table (scheme_id, reading_date, tenant_id) VALUES (?, ?, 1)",
                PLACEHOLDER, D1);

        reassignmentRepository.dropScheme(TENANT, PLACEHOLDER);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analytics_schema.dim_scheme_table WHERE scheme_id = ?",
                Integer.class, PLACEHOLDER)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM analytics_schema.fact_scheme_daily_table WHERE scheme_id = ?",
                Integer.class, PLACEHOLDER)).isZero();
    }
}
