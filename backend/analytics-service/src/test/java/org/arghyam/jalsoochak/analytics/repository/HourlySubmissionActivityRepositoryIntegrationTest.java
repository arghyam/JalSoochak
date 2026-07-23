package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.repository.HourlySubmissionActivityRepository.HourlyActivityRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers test of the on-the-fly hourly activity reads (from fact_meter_reading_table)
 * at tenant scope and any region level. Requires Docker (real Flyway builds the schema).
 */
@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(HourlySubmissionActivityRepository.class)
class HourlySubmissionActivityRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("hourly_submission_activity_test")
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

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private HourlySubmissionActivityRepository repository;

    private static final LocalDate DAY = LocalDate.of(2026, 1, 1);

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE analytics_schema.fact_meter_reading_table,
                         analytics_schema.dim_scheme_table,
                         analytics_schema.dim_tenant_table CASCADE
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, required_lpcd, person_count_per_household,
                 over_supply_range_percentage, under_supply_range_percentage, created_at, updated_at)
                VALUES (1, 't1', 'Tenant 1', 'IN', 1, 1, 1, 0, 0, NOW(), NOW())
                """);

        // scheme 1 -> LGD level_2 = 10 ; scheme 2 -> LGD level_2 = 20 ; both under level_1 = 1.
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, work_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES
                (1, 1, 'S1', 1001, 2001, 0.0, 0.0, 1, 1, 10, NULL, NULL, NULL, NULL, 1, 1, 1, NULL, NULL, NULL, NULL, 1, 4, 10, 10, 10, NOW(), NOW()),
                (2, 1, 'S2', 1002, 2002, 0.0, 0.0, 1, 1, 20, NULL, NULL, NULL, NULL, 1, 1, 1, NULL, NULL, NULL, NULL, 1, 1, 10, 10, 10, NOW(), NOW())
                """);

        // Hour 09: scheme1 x2 (09:15, 09:45), scheme2 x1 (09:30). Hour 10: scheme1 x1 (10:05).
        insertReading(1, "2026-01-01 09:15:00");
        insertReading(1, "2026-01-01 09:45:00");
        insertReading(2, "2026-01-01 09:30:00");
        insertReading(1, "2026-01-01 10:05:00");
    }

    @Test
    void tenantHourly_sumsAcrossSchemes_perHour() {
        List<HourlyActivityRow> rows = repository.getTenantHourly(1, DAY, DAY);

        assertThat(rows).hasSize(2);
        HourlyActivityRow h9 = rows.get(0);
        assertThat(h9.hourStart()).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 0));
        assertThat(h9.submissionCount()).isEqualTo(3L);      // s1 x2 + s2 x1
        assertThat(h9.distinctSchemeCount()).isEqualTo(2);   // s1, s2

        HourlyActivityRow h10 = rows.get(1);
        assertThat(h10.hourStart()).isEqualTo(LocalDateTime.of(2026, 1, 1, 10, 0));
        assertThat(h10.submissionCount()).isEqualTo(1L);
        assertThat(h10.distinctSchemeCount()).isEqualTo(1);
    }

    @Test
    void regionHourly_lgdLevel2_scopesToThatRegionsSchemes() {
        // region LGD level_2 = 10 -> only scheme 1.
        List<HourlyActivityRow> rows = repository.getRegionHourly(1, "LGD", 10, DAY, DAY);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).hourStart()).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 0));
        assertThat(rows.get(0).submissionCount()).isEqualTo(2L);   // s1's two hour-9 readings only
        assertThat(rows.get(0).distinctSchemeCount()).isEqualTo(1);
        assertThat(rows.get(1).submissionCount()).isEqualTo(1L);   // hour 10
    }

    @Test
    void regionHourly_lgdLevel1_coversBothSchemes() {
        // region LGD level_1 = 1 -> both schemes, so matches the tenant-wide totals.
        List<HourlyActivityRow> rows = repository.getRegionHourly(1, "LGD", 1, DAY, DAY);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).submissionCount()).isEqualTo(3L);
        assertThat(rows.get(0).distinctSchemeCount()).isEqualTo(2);
    }

    @Test
    void regionHourly_unknownRegion_returnsEmpty() {
        assertThat(repository.getRegionHourly(1, "LGD", 999, DAY, DAY)).isEmpty();
    }

    private void insertReading(int schemeId, String readingAt) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url,
                 reading_at, channel, reading_date, created_at, submission_status, reading_type)
                VALUES (1, ?, 11, 10, 10, 90, 'x', ?::timestamp, 1, ?, NOW(), 1, 0)
                """, schemeId, readingAt, DAY);
    }
}
