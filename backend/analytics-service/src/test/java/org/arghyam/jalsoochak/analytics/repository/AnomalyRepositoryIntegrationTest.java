package org.arghyam.jalsoochak.analytics.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
class AnomalyRepositoryIntegrationTest {

    private static final String UUID = "anomaly-uuid-1";
    private static final LocalDateTime RAISED_AT = LocalDateTime.of(2026, 1, 1, 18, 0);
    private static final LocalDateTime REDELIVERED_AT = LocalDateTime.of(2026, 1, 2, 9, 0);

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("analytics_anomaly_test")
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
    private AnomalyRepository anomalyRepository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE analytics_schema.fact_anomaly_table RESTART IDENTITY");
        jdbcTemplate.update("""
                INSERT INTO analytics_schema.fact_anomaly_table
                (uuid, type, scheme_id, tenant_id, status, created_at, updated_at)
                VALUES (?, 'UNREADABLE_IMAGE', 10, 1, 1, ?, ?)
                """, UUID, RAISED_AT, RAISED_AT);
    }

    /**
     * A duplicate delivery must not move the anomaly to another day: every dashboard dates anomalies
     * by {@code created_at}, so moving it would drop the anomaly from the day it was raised.
     */
    @Test
    void touchByUuid_updatesOnlyUpdatedAt() {
        int touched = anomalyRepository.touchByUuid(UUID, REDELIVERED_AT);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT created_at, updated_at FROM analytics_schema.fact_anomaly_table WHERE uuid = ?", UUID);
        assertThat(touched).isEqualTo(1);
        assertThat(((Timestamp) row.get("created_at")).toLocalDateTime()).isEqualTo(RAISED_AT);
        assertThat(((Timestamp) row.get("updated_at")).toLocalDateTime()).isEqualTo(REDELIVERED_AT);
    }
}
