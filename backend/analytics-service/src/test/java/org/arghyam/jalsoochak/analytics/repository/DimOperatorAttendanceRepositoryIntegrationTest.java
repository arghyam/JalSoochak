package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.dto.response.OperatorAttendanceDayItemDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class DimOperatorAttendanceRepositoryIntegrationTest {

    private static final UUID USER_A = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11");
    private static final UUID USER_B = UUID.fromString("b1eebc99-9c0b-4ef8-bb6d-6bb9bd380a12");

    private static final LocalDate D1 = LocalDate.of(2026, 1, 1);
    private static final LocalDate D2 = LocalDate.of(2026, 1, 2);
    private static final LocalDate D3 = LocalDate.of(2026, 1, 3);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_attendance_test")
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
    private DimOperatorAttendanceRepository dimOperatorAttendanceRepository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE
                    analytics_schema.dim_operator_attendance_table,
                    analytics_schema.fact_meter_reading_table,
                    analytics_schema.fact_water_quantity_table,
                    analytics_schema.fact_escalation_table,
                    analytics_schema.fact_scheme_performance_table,
                    analytics_schema.dim_scheme_table,
                    analytics_schema.dim_lgd_location_table,
                    analytics_schema.dim_department_location_table,
                    analytics_schema.dim_user_table,
                    analytics_schema.dim_date_table,
                    analytics_schema.dim_tenant_table
                RESTART IDENTITY CASCADE
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, created_at, updated_at)
                VALUES (1, 'mp', 'Madhya Pradesh', 'IN', 1, NOW(), NOW())
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_table
                (user_id, tenant_id, email, user_type, uuid, created_at, updated_at, title)
                VALUES
                (11, 1, 'u11@test.local', 1, ?, NOW(), NOW(), 'User 11'),
                (12, 1, 'u12@test.local', 1, ?, NOW(), NOW(), 'User 12')
                """, USER_A, USER_B);

        insertDate(20260101, D1);
        insertDate(20260102, D2);
        insertDate(20260103, D3);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_lgd_location_table
                (lgd_id, tenant_id, lgd_code, lgd_c_name, title, lgd_level,
                 level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 geom, created_at, updated_at)
                VALUES
                (100, 1, 'L100', 'Parent', 'Parent LGD', 1, 100, NULL, NULL, NULL, NULL, NULL, NULL, NOW(), NOW())
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_department_location_table
                (department_id, tenant_id, department_c_name, title, department_level,
                 level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 created_at, updated_at, geom)
                VALUES
                (200, 1, 'Parent Dept', 'Parent Dept', 1, 200, NULL, NULL, NULL, NULL, NULL, NOW(), NOW(), NULL)
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES
                (1, 1, 'Scheme A', 1001, 2001, 0.0, 0.0, 100, 100, NULL, NULL, NULL, NULL, NULL, 200, 200, NULL, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW()),
                (2, 1, 'Scheme B', 1002, 2002, 0.0, 0.0, 100, 100, NULL, NULL, NULL, NULL, NULL, 200, 200, NULL, NULL, NULL, NULL, NULL, 1, 10, 10, 10, NOW(), NOW())
                """);

        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_operator_attendance_table
                (tenant_id, date_key, user_id, scheme_id, attendance, remark, remark_by, created_at, updated_at)
                VALUES
                (1, 20260101, 11, 1, 1, 'present', NULL, NOW(), NOW()),
                (1, 20260102, 11, 1, 0, NULL, NULL, NOW(), NOW()),
                (1, 20260103, 11, 2, 1, 'other scheme', NULL, NOW(), NOW()),
                (1, 20260101, 12, 1, 1, 'user12', NULL, NOW(), NOW())
                """);
    }

    @Test
    void findDayWiseByUserUuidAndDateRange_filtersByUuidAndRange_ordersByDateAndScheme() {
        List<OperatorAttendanceDayItemDto> jan1Only =
                dimOperatorAttendanceRepository.findDayWiseByUserUuidAndDateRange(USER_A, D1, D1);

        assertThat(jan1Only).hasSize(1);
        assertThat(jan1Only.getFirst().getDate()).isEqualTo(D1);
        assertThat(jan1Only.getFirst().getAttendance()).isEqualTo(1);

        List<OperatorAttendanceDayItemDto> jan1to2 =
                dimOperatorAttendanceRepository.findDayWiseByUserUuidAndDateRange(USER_A, D1, D2);
        assertThat(jan1to2).hasSize(2);
        assertThat(jan1to2.get(0).getDate()).isEqualTo(D1);
        assertThat(jan1to2.get(1).getDate()).isEqualTo(D2);

        List<OperatorAttendanceDayItemDto> full =
                dimOperatorAttendanceRepository.findDayWiseByUserUuidAndDateRange(USER_A, D1, D3);
        assertThat(full).hasSize(3);
        assertThat(full.get(2).getDate()).isEqualTo(D3);
        assertThat(full.get(2).getAttendance()).isEqualTo(1);
    }

    @Test
    void findDayWiseByUserUuidAndDateRange_otherUser_doesNotSeePeerRows() {
        List<OperatorAttendanceDayItemDto> b =
                dimOperatorAttendanceRepository.findDayWiseByUserUuidAndDateRange(USER_B, D1, D3);
        assertThat(b).hasSize(1);
        assertThat(b.getFirst().getDate()).isEqualTo(D1);
        assertThat(b.getFirst().getAttendance()).isEqualTo(1);
    }

    private void insertDate(int dateKey, LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_date_table
                (date_key, full_date, day, month, month_name, quarter, year, week, is_weekend, fiscal_year)
                VALUES (?, ?, EXTRACT(DAY FROM ?::date), EXTRACT(MONTH FROM ?::date), TO_CHAR(?::date, 'FMMonth'),
                        EXTRACT(QUARTER FROM ?::date), EXTRACT(YEAR FROM ?::date), EXTRACT(WEEK FROM ?::date),
                        EXTRACT(ISODOW FROM ?::date) IN (6,7), EXTRACT(YEAR FROM ?::date))
                """, dateKey, date, date, date, date, date, date, date, date, date);
    }
}
