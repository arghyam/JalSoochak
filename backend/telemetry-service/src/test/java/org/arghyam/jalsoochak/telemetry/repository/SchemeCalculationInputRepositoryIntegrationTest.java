package org.arghyam.jalsoochak.telemetry.repository;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A scheme's ELM and PDU calculation inputs against a real PostgreSQL instance: only pumps in service
 * and not deleted count, and each value comes back as the decimal it was entered as.
 */
@Testcontainers
class SchemeCalculationInputRepositoryIntegrationTest {

    private static final String SCHEMA = "tenant_as";
    private static final long SCHEME = 1L;
    private static final long OTHER_SCHEME = 2L;
    private static final int ACTIVE = 1;
    private static final int INACTIVE = 0;

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    private static JdbcTemplate jdbcTemplate;

    private SchemeCalculationInputRepository repository;

    @BeforeAll
    static void connect() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void clean() {
        jdbcTemplate.execute("DELETE FROM " + SCHEMA + ".asset_pump_registry_table");
        jdbcTemplate.execute("DELETE FROM " + SCHEMA + ".scheme_master_table");
        repository = new SchemeCalculationInputRepository(jdbcTemplate);
    }

    private void insertScheme(long id, Double kFactor) {
        jdbcTemplate.update("INSERT INTO " + SCHEMA + ".scheme_master_table (id, k_factor) VALUES (?, ?)",
                id, kFactor);
    }

    /** A pump with every rating set, so a test only varies what it is about. */
    private long insertPump(long schemeId, int status, LocalDateTime deletedAt) {
        return jdbcTemplate.queryForObject("INSERT INTO " + SCHEMA + ".asset_pump_registry_table "
                        + "(scheme_id, status, pump_discharge_capacity, pump_efficiency, pump_head, motor_power, "
                        + " motor_power_unit, motor_efficiency, units_consumed_per_hour, power_factor, deleted_at) "
                        + "VALUES (?, ?, 500, 0.7, 40, 7.5, 'HP', 0.85, 5, 0.9, ?) RETURNING id",
                Long.class, schemeId, status, deletedAt);
    }

    private List<Long> activePumpIds() {
        return repository.findActivePumps(SCHEMA, SCHEME).stream().map(ActivePump::id).toList();
    }

    @Test
    void onlyActivePumpsCount() {
        long active = insertPump(SCHEME, ACTIVE, null);
        insertPump(SCHEME, INACTIVE, null);

        assertThat(activePumpIds()).containsExactly(active);
    }

    @Test
    void aSoftDeletedPumpDoesNotCountEvenWhenActive() {
        long kept = insertPump(SCHEME, ACTIVE, null);
        insertPump(SCHEME, ACTIVE, LocalDateTime.of(2026, 9, 1, 10, 0));

        assertThat(activePumpIds()).containsExactly(kept);
    }

    @Test
    void anotherSchemesPumpsDoNotCount() {
        insertPump(OTHER_SCHEME, ACTIVE, null);

        assertThat(repository.findActivePumps(SCHEMA, SCHEME)).isEmpty();
    }

    @Test
    void severalActivePumpsComeBackInRegistrationOrder() {
        long first = insertPump(SCHEME, ACTIVE, null);
        long second = insertPump(SCHEME, ACTIVE, null);

        assertThat(activePumpIds()).containsExactly(first, second);
    }

    /** FLOAT columns hold binary doubles; 0.7 must not reach the formulas as 0.69999…. */
    @Test
    void readsEachRatingAsTheDecimalItWasEnteredAs() {
        long id = insertPump(SCHEME, ACTIVE, null);

        ActivePump pump = repository.findActivePumps(SCHEMA, SCHEME).get(0);

        assertThat(pump.id()).isEqualTo(id);
        assertThat(pump.pumpDischargeCapacity()).isEqualByComparingTo("500");
        assertThat(pump.pumpEfficiency()).isEqualTo(new BigDecimal("0.7"));
        assertThat(pump.pumpHead()).isEqualByComparingTo("40");
        assertThat(pump.motorPower()).isEqualTo(new BigDecimal("7.5"));
        assertThat(pump.motorPowerUnit()).isEqualTo("HP");
        assertThat(pump.motorEfficiency()).isEqualTo(new BigDecimal("0.85"));
        assertThat(pump.unitsConsumedPerHour()).isEqualByComparingTo("5");
        assertThat(pump.powerFactor()).isEqualTo(new BigDecimal("0.9"));
    }

    @Test
    void aRatingThatIsNotRecordedStaysNull() {
        jdbcTemplate.update("INSERT INTO " + SCHEMA + ".asset_pump_registry_table (scheme_id, status) VALUES (?, ?)",
                SCHEME, ACTIVE);

        ActivePump pump = repository.findActivePumps(SCHEMA, SCHEME).get(0);

        assertThat(pump.pumpDischargeCapacity()).isNull();
        assertThat(pump.pumpEfficiency()).isNull();
        assertThat(pump.pumpHead()).isNull();
        assertThat(pump.motorPower()).isNull();
        assertThat(pump.motorPowerUnit()).isNull();
        assertThat(pump.motorEfficiency()).isNull();
        assertThat(pump.unitsConsumedPerHour()).isNull();
        assertThat(pump.powerFactor()).isNull();
    }

    @Test
    void readsTheSchemesKFactor() {
        insertScheme(SCHEME, 0.95);

        assertThat(repository.findKFactor(SCHEMA, SCHEME)).contains(new BigDecimal("0.95"));
    }

    @Test
    void aNullKFactorIsEmpty() {
        insertScheme(SCHEME, null);

        assertThat(repository.findKFactor(SCHEMA, SCHEME)).isEmpty();
    }

    @Test
    void anUnknownSchemeHasNoKFactor() {
        assertThat(repository.findKFactor(SCHEMA, SCHEME)).isEmpty();
    }
}
