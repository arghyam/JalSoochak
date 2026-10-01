package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.dto.event.SchemeDimensionReplacedEvent;
import org.arghyam.jalsoochak.analytics.dto.event.SchemeDimensionReplacedEvent.Row;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SchemeDimensionReplaceRepository} and {@link SchemeReassignmentRepository} against the real,
 * Flyway-migrated warehouse schema.
 */
@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SchemeDimensionReplaceRepository.class, SchemeReassignmentRepository.class})
class SchemeDimensionAndReassignmentRepositoryIntegrationTest {

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
    private SchemeDimensionReplaceRepository replaceRepository;

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

    // ── dim replace ─────────────────────────────────────────────────────────

    private static SchemeDimensionReplacedEvent scheme(String name, Row... rows) {
        SchemeDimensionReplacedEvent e = new SchemeDimensionReplacedEvent();
        e.setTenantId(TENANT);
        e.setSchemeId(SCHEME);
        e.setSchemeName(name);
        e.setStateSchemeId(34123);
        e.setCentreSchemeId(8165607);
        e.setOperatingStatus(1);
        e.setWorkStatus(4);
        e.setFhtcCount(241);
        e.setPlannedFhtc(280);
        e.setRows(Arrays.asList(rows));
        return e;
    }

    private static Row row(int village, Integer subdivision) {
        return Row.builder().parentLgdLocationId(village).lgdLevels(Arrays.asList(1, 2, 3, 4, village, null))
                .parentDepartmentLocationId(subdivision)
                .deptLevels(subdivision == null ? null : Arrays.asList(1, 2, 3, 4, subdivision)).build();
    }

    private List<Map<String, Object>> dimRows() {
        return jdbc.queryForList("SELECT parent_lgd_location_id AS lgd, parent_department_location_id AS dept, scheme_name, "
                + "level_5_lgd_id, level_6_lgd_id, level_5_dept_id, fhtc_count FROM analytics_schema.dim_scheme_table "
                + "WHERE tenant_id = 1 AND scheme_id = ? ORDER BY 1, 2", SCHEME);
    }

    @Test
    void writesOneRowPerVillageAndSubdivisionWithTheirLevels() {
        replaceRepository.replace(scheme("CHAPATOLI", row(501, 61), row(502, 61), row(501, 62)));

        assertThat(dimRows()).hasSize(3).allSatisfy(r -> {
            assertThat(r.get("scheme_name")).isEqualTo("CHAPATOLI");
            assertThat(r.get("level_5_lgd_id")).isEqualTo(r.get("lgd"));
            assertThat(r.get("level_6_lgd_id")).isNull();
            assertThat(r.get("fhtc_count")).isEqualTo(241);
        });
    }

    @Test
    void replacingDropsLocationsTheSchemeLeftAndRealignsTheRest() {
        replaceRepository.replace(scheme("OLD NAME", row(501, 61), row(502, 61)));

        int removed = replaceRepository.replace(scheme("NEW NAME", row(502, 61), row(503, null)));

        assertThat(removed).isEqualTo(1);
        assertThat(dimRows()).extracting(r -> r.get("lgd")).containsExactly(502, 503);
        assertThat(dimRows()).extracting(r -> r.get("scheme_name")).containsOnly("NEW NAME");
        assertThat(dimRows()).filteredOn(r -> r.get("dept") == null).singleElement()
                .satisfies(r -> assertThat(r.get("level_5_dept_id")).isNull());
    }

    @Test
    void anEmptyRowListOnlyRealignsAttributes() {
        replaceRepository.replace(scheme("OLD", row(501, 61), row(502, 61)));

        replaceRepository.replace(scheme("RENAMED"));

        assertThat(dimRows()).hasSize(2).extracting(r -> r.get("scheme_name")).containsOnly("RENAMED");
    }

    @Test
    void aKnownEmptyLocationSetRemovesEveryRowOfTheScheme() {
        replaceRepository.replace(scheme("CHAPATOLI", row(501, 61), row(502, 61)));
        SchemeDimensionReplacedEvent noLocations = scheme("CHAPATOLI");
        noLocations.setLocationsKnown(true);

        int removed = replaceRepository.replace(noLocations);

        assertThat(removed).isEqualTo(2);
        assertThat(dimRows()).isEmpty();
    }

    // ── reassignment ────────────────────────────────────────────────────────

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
