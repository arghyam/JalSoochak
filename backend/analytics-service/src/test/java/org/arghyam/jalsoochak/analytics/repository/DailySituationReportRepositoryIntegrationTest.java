package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.dto.DailyReportKpiDTO;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers integration tests for the officer-scoped KPI queries backing the
 * Daily Water Service Situation Report. Uses the real Flyway migrations against Postgres.
 */
@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(DailySituationReportRepository.class)
class DailySituationReportRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_test")
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
    private DailySituationReportRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final int TENANT = 1;
    private static final long OFFICER = 500L;
    /** A supervising officer (SDO) used to exercise the shared-scheme narrowing. */
    private static final long SUPERVISOR = 900L;
    private static final LocalDate DAY = LocalDate.of(2026, 6, 10);
    private static final LocalDate PREV = DAY.minusDays(1);

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE analytics_schema.anomaly_table, "
                + "analytics_schema.fact_meter_reading_table, "
                + "analytics_schema.fact_water_quantity_table, "
                + "analytics_schema.dim_user_scheme_mapping_table, "
                + "analytics_schema.dim_scheme_table, "
                + "analytics_schema.dim_tenant_table RESTART IDENTITY CASCADE");
        seed();
    }

    @Test
    void countSchemesSupplyingOnDay_countsOnlySchemesWithPositiveConfirmedReading() {
        // Scheme 1 supplies (confirmed 120); scheme 2 submits 0; scheme 3 no reading.
        assertThat(repository.countSchemesSupplyingOnDay(TENANT, OFFICER, DAY, null)).isEqualTo(1);
    }

    @Test
    void countSchemesSubmittingOnDay_countsSchemesWithAnyReading() {
        // Scheme 1 and scheme 2 both submitted; scheme 3 did not.
        assertThat(repository.countSchemesSubmittingOnDay(TENANT, OFFICER, DAY, null)).isEqualTo(2);
    }

    @Test
    void sumSupplyDaysInRange_countsSupplyDaysAcrossWeekWindow() {
        // Scheme 1 supplies on DAY and PREV → 2 supply-days over the 7-day window.
        assertThat(repository.sumSupplyDaysInRange(TENANT, OFFICER, DAY.minusDays(6), DAY, null)).isEqualTo(2);
    }

    @Test
    void sumWaterSuppliedOnDay_sumsLitresAcrossOfficerSchemes() {
        assertThat(repository.sumWaterSuppliedOnDay(TENANT, OFFICER, DAY, null)).isEqualTo(500_000L);
    }

    @Test
    void populationServed_usesFhtcTimesPersonsPerHousehold() {
        // fhtc 100 + 100 + 0 = 200; person_count_per_household = 5 → 1000.
        assertThat(repository.populationServed(TENANT, OFFICER, null)).isEqualTo(1000L);
    }

    @Test
    void countAnomaliesByType_groupsByTypeForTheDay() {
        List<DailyReportKpiDTO.TypeCount> counts = repository.countAnomaliesByType(
                TENANT, OFFICER, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay(), null);

        assertThat(counts).hasSize(2);
        assertThat(counts).anySatisfy(c -> {
            assertThat(c.getType()).isEqualTo("4");
            assertThat(c.getCount()).isEqualTo(1);
        }).anySatisfy(c -> {
            assertThat(c.getType()).isEqualTo("5");
            assertThat(c.getCount()).isEqualTo(1);
        });
    }

    @Test
    void countAnomaliesByType_scopesToQueriedDayWindow() {
        // The PREV window must return only the PREV anomaly (a4, type 5) and none of
        // DAY's anomalies (a1, a2) — confirming the window boundary is applied per day.
        List<DailyReportKpiDTO.TypeCount> prev = repository.countAnomaliesByType(
                TENANT, OFFICER, PREV.atStartOfDay(), PREV.plusDays(1).atStartOfDay(), null);
        assertThat(prev).singleElement().satisfies(c -> {
            assertThat(c.getType()).isEqualTo("5");
            assertThat(c.getCount()).isEqualTo(1);
        });
    }

    @Test
    void listNoSupplyByScheme_returnsOutageSchemesWithLastSupplyDate() {
        // scheme 2: a prior positive supply 3 days before + an outage reason recorded on DAY
        insertReading(2, 50, 50, DAY.minusDays(3));
        // a positive reading AFTER the report day must not count as the last supply date
        insertReading(2, 60, 60, DAY.plusDays(1));
        insertOutage(2, "Pump Failure", DAY);
        // scheme 3: outage reason on DAY, but never supplied → last supply date null
        insertOutage(3, "Pipeline Break", DAY);

        List<DailySituationReportRepository.NoSupplyScheme> rows =
                repository.listNoSupplyByScheme(TENANT, OFFICER, DAY, null);

        assertThat(rows).hasSize(2);
        assertThat(rows)
                .anySatisfy(s -> {
                    assertThat(s.schemeId()).isEqualTo(2);
                    assertThat(s.outageReason()).isEqualTo("Pump Failure");
                    assertThat(s.lastSupplyDate()).isEqualTo(DAY.minusDays(3));
                })
                .anySatisfy(s -> {
                    assertThat(s.schemeId()).isEqualTo(3);
                    assertThat(s.outageReason()).isEqualTo("Pipeline Break");
                    assertThat(s.lastSupplyDate()).isNull();
                });
        // scheme 1 has readings but no outage reason on DAY → excluded
        assertThat(rows).noneSatisfy(s -> assertThat(s.schemeId()).isEqualTo(1));
    }

    @Test
    void supervisorScoping_narrowsEveryKpiToTheSchemesSharedWithTheSupervisor() {
        // The SDO is mapped to schemes 1 and 3; the officer (a Section Officer under them) is mapped
        // to 1, 2 and 3. Scheme 2 is the officer's alone, so it must not reach the SDO's breakdown row.
        mapUser(SUPERVISOR, 1);
        mapUser(SUPERVISOR, 3);
        insertWaterQuantity(2, 250_000, DAY);   // scheme-2 litres, visible unscoped only
        insertOutage(2, "Pump Failure", DAY);

        // Scheme 1 supplies and submits; scheme 2 only submits — dropping scheme 2 costs one submission.
        assertThat(repository.countSchemesSubmittingOnDay(TENANT, OFFICER, DAY, null)).isEqualTo(2);
        assertThat(repository.countSchemesSubmittingOnDay(TENANT, OFFICER, DAY, SUPERVISOR)).isEqualTo(1);

        // Scheme 1 is shared, so the supplying count is unchanged by the narrowing.
        assertThat(repository.countSchemesSupplyingOnDay(TENANT, OFFICER, DAY, SUPERVISOR)).isEqualTo(1);
        assertThat(repository.sumSupplyDaysInRange(TENANT, OFFICER, DAY.minusDays(6), DAY, SUPERVISOR))
                .isEqualTo(2);

        // Litres and population lose scheme 2's contribution (250,000 litres; fhtc 100 × 5 people).
        assertThat(repository.sumWaterSuppliedOnDay(TENANT, OFFICER, DAY, null)).isEqualTo(750_000L);
        assertThat(repository.sumWaterSuppliedOnDay(TENANT, OFFICER, DAY, SUPERVISOR)).isEqualTo(500_000L);
        assertThat(repository.populationServed(TENANT, OFFICER, SUPERVISOR)).isEqualTo(500L);

        // Anomaly a2 sits on scheme 2 and drops out; a1 on the shared scheme 1 stays.
        assertThat(repository.countAnomaliesByType(
                TENANT, OFFICER, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay(), SUPERVISOR))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.getType()).isEqualTo("5");
                    assertThat(c.getCount()).isEqualTo(1);
                });

        // Scheme 2's outage is likewise out of scope.
        assertThat(repository.listNoSupplyByScheme(TENANT, OFFICER, DAY, null)).hasSize(1);
        assertThat(repository.listNoSupplyByScheme(TENANT, OFFICER, DAY, SUPERVISOR)).isEmpty();
    }

    @Test
    void supervisorScoping_withNoSharedSchemes_yieldsZeroes() {
        // A supervisor mapped to none of the officer's schemes contributes no rows at all.
        insertScheme(4, 100);
        mapUser(SUPERVISOR, 4);

        assertThat(repository.countSchemesSupplyingOnDay(TENANT, OFFICER, DAY, SUPERVISOR)).isZero();
        assertThat(repository.countSchemesSubmittingOnDay(TENANT, OFFICER, DAY, SUPERVISOR)).isZero();
        assertThat(repository.sumWaterSuppliedOnDay(TENANT, OFFICER, DAY, SUPERVISOR)).isZero();
        assertThat(repository.populationServed(TENANT, OFFICER, SUPERVISOR)).isZero();
        assertThat(repository.countAnomaliesByType(
                TENANT, OFFICER, DAY.atStartOfDay(), DAY.plusDays(1).atStartOfDay(), SUPERVISOR)).isEmpty();
    }

    // ---- seed helpers ----------------------------------------------------

    private void seed() {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_tenant_table
                (tenant_id, state_code, title, country_code, status, required_lpcd, person_count_per_household,
                 created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """, TENANT, "mp", "Madhya Pradesh", "IN", 1, 55, 5);

        insertScheme(1, 100);
        insertScheme(2, 100);
        insertScheme(3, 0);

        mapOfficer(1);
        mapOfficer(2);
        mapOfficer(3);

        // Date dimension — fact_water_quantity_table.date has an FK to dim_date_table.full_date.
        // Seed the full week window so any fact row in range satisfies the constraint.
        for (int i = 0; i <= 6; i++) {
            insertDate(DAY.minusDays(i));
        }

        // Meter readings — DAY
        insertReading(1, 110, 120, DAY);   // supplies + submits
        insertReading(2, 0, 0, DAY);        // submits, no supply
        // scheme 3: no reading on DAY
        // Meter readings — PREV (for week window: scheme 1 supplies again)
        insertReading(1, 100, 100, PREV);

        // Water quantity — DAY
        insertWaterQuantity(1, 500_000, DAY);
        insertWaterQuantity(2, 0, DAY);

        // Anomalies — DAY (types 5 and 4), one soft-deleted, one on PREV
        insertAnomaly("a1", "5", 1, DAY, false);
        insertAnomaly("a2", "4", 2, DAY, false);
        insertAnomaly("a3", "6", 1, DAY, true);      // soft-deleted → excluded
        insertAnomaly("a4", "5", 1, PREV, false);    // out of DAY window
    }

    private void insertScheme(int schemeId, int fhtc) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_scheme_table
                (scheme_id, tenant_id, scheme_name, state_scheme_id, centre_scheme_id, longitude, latitude,
                 parent_lgd_location_id, level_1_lgd_id, level_2_lgd_id, level_3_lgd_id, level_4_lgd_id, level_5_lgd_id, level_6_lgd_id,
                 parent_department_location_id, level_1_dept_id, level_2_dept_id, level_3_dept_id, level_4_dept_id, level_5_dept_id, level_6_dept_id,
                 operating_status, fhtc_count, planned_fhtc, house_hold_count, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 0.0, 0.0, 100, 100, 0, 0, 0, 0, 0, 200, 200, 0, 0, 0, 0, 0,
                        1, ?, ?, ?, NOW(), NOW())
                """, schemeId, TENANT, "Scheme " + schemeId, 1000 + schemeId, 2000 + schemeId,
                fhtc, fhtc, fhtc);
    }

    private void mapOfficer(int schemeId) {
        mapUser(OFFICER, schemeId);
    }

    private void mapUser(long userId, int schemeId) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_user_scheme_mapping_table
                (uuid, tenant_id, user_id, scheme_id, ai_reading, created_at, updated_at, status)
                VALUES (gen_random_uuid(), ?, ?, ?, NULL, NOW(), NOW(), 1)
                """, TENANT, userId, schemeId);
    }

    private void insertReading(int schemeId, Integer extracted, int confirmed, LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_meter_reading_table
                (tenant_id, scheme_id, user_id, extracted_reading, confirmed_reading, confidence, image_url,
                 reading_at, channel, reading_date, created_at)
                VALUES (?, ?, ?, ?, ?, 90, 'x', ?, 1, ?, NOW())
                """, TENANT, schemeId, OFFICER, extracted, confirmed, date.atTime(10, 0), date);
    }

    private void insertDate(LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.dim_date_table
                (date_key, full_date, day, month, month_name, quarter, year, week, is_weekend, fiscal_year)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (date_key) DO NOTHING
                """,
                Integer.parseInt(date.toString().replace("-", "")), date,
                date.getDayOfMonth(), date.getMonthValue(), date.getMonth().name(),
                (date.getMonthValue() - 1) / 3 + 1, date.getYear(),
                date.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear()),
                date.getDayOfWeek().getValue() >= 6, date.getYear());
    }

    private void insertWaterQuantity(int schemeId, int litres, LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at, submission_status)
                VALUES (?, ?, ?, ?, ?, NOW(), NOW(), 1)
                """, TENANT, schemeId, OFFICER, litres, date);
    }

    private void insertOutage(int schemeId, String reason, LocalDate date) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_water_quantity_table
                (tenant_id, scheme_id, user_id, water_quantity, date, created_at, updated_at,
                 submission_status, outage_reason)
                VALUES (?, ?, ?, 0, ?, NOW(), NOW(), 0, ?)
                """, TENANT, schemeId, OFFICER, date, reason);
    }

    private void insertAnomaly(String uuid, String type, int schemeId, LocalDate date, boolean deleted) {
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.anomaly_table
                (uuid, type, scheme_id, tenant_id, status, created_at, deleted_at)
                VALUES (?, ?, ?, ?, 1, ?, ?)
                """, uuid, type, schemeId, TENANT, date.atTime(10, 0),
                deleted ? date.atTime(11, 0) : null);
    }
}
