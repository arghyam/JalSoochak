package org.arghyam.jalsoochak.telemetry.repository;

import org.arghyam.jalsoochak.telemetry.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for V46 renaming {@code flow_reading_table}'s OCR correlation column while
 * telemetry-service is serving, against real PostgreSQL via Testcontainers.
 *
 * <p>The repository checks the column's name in the catalogue before each statement that uses it. A
 * rename committing between that check and the statement would leave the statement naming a column
 * that no longer exists. Each test therefore holds the rename uncommitted, calls the repository, and
 * commits once the call is queued behind the rename's lock. The repository is called through its
 * Spring proxy outside any transaction, as the services call it, so each method under test also has to
 * open the transaction its own lock needs.
 */
@Testcontainers
@SpringJUnitConfig
@TestPropertySource(properties = "telemetry.cache.metadata.enabled=false")
class TelemetryTenantRepositoryOcrColumnRenameIntegrationTest {

    private static final String SCHEMA = "tenant_rn";
    private static final String PRE_V46_COLUMN = "flowvision_correlation_id";
    private static final String COLUMN = "ocr_correlation_id";
    private static final long SCHEME = 1L;
    private static final long OPERATOR = 9L;
    private static final LocalDateTime READING_AT = LocalDateTime.of(2026, 3, 1, 6, 0);
    private static final Duration AWAIT = Duration.ofSeconds(10);

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Configuration
    @EnableTransactionManagement
    @Import(TelemetryTenantRepository.class)
    static class Config {

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PiiEncryptionService piiEncryptionService() {
            String key = Base64.getEncoder().encodeToString(new byte[32]);
            return new PiiEncryptionService(key, key);
        }
    }

    @Autowired
    private TelemetryTenantRepository repository;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void createPreV46Table() {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        jdbcTemplate.execute("CREATE SCHEMA " + SCHEMA);
        jdbcTemplate.execute("""
                CREATE TABLE %s.flow_reading_table (
                    id                  SERIAL       PRIMARY KEY,
                    scheme_id           INTEGER      NOT NULL,
                    reading_at          TIMESTAMP    NOT NULL,
                    reading_date        DATE         NOT NULL,
                    extracted_reading   NUMERIC      NOT NULL,
                    confirmed_reading   NUMERIC      NOT NULL,
                    correlation_id      VARCHAR(255) NOT NULL,
                    %s                  VARCHAR(255),
                    quantity            NUMERIC,
                    channel             VARCHAR(64),
                    meter_change_reason TEXT,
                    issue_report_reason TEXT,
                    image_url           TEXT,
                    created_by          INTEGER      NOT NULL,
                    created_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
                    updated_by          INTEGER,
                    updated_at          TIMESTAMP    NOT NULL DEFAULT NOW(),
                    deleted_at          TIMESTAMP
                )
                """.formatted(SCHEMA, PRE_V46_COLUMN));
    }

    @Test
    void createFlowReadingWritesTheRenamedColumnWhenTheRenameCommitsMidCall() throws Exception {
        Long id = whileRenameIsPending(() -> repository.createFlowReading(SCHEMA, SCHEME, OPERATOR, READING_AT,
                BigDecimal.ONE, BigDecimal.ONE, "corr-1", "ocr-1", "", null));

        assertThat(ocrCorrelationIdOf(id)).isEqualTo("ocr-1");
    }

    @Test
    void updateFlowReadingFromIngestionWritesTheRenamedColumnWhenTheRenameCommitsMidCall() throws Exception {
        long id = insertReading("corr-1", null);

        whileRenameIsPending(() -> {
            repository.updateFlowReadingFromIngestion(SCHEMA, id, READING_AT, BigDecimal.ONE, BigDecimal.ONE,
                    "corr-1", "ocr-1", "", null, OPERATOR);
            return null;
        });

        assertThat(ocrCorrelationIdOf(id)).isEqualTo("ocr-1");
    }

    @Test
    void findFlowReadingDetailsByCorrelationIdMatchesTheRenamedColumnWhenTheRenameCommitsMidCall()
            throws Exception {
        long id = insertReading("corr-1", "ocr-1");

        assertThat(whileRenameIsPending(() -> repository.findFlowReadingDetailsByCorrelationId(SCHEMA, "ocr-1")))
                .map(TelemetryLatestFlowReadingRecord::id)
                .contains(id);
    }

    @Test
    void findReadingByCorrelationIdMatchesTheRenamedColumnWhenTheRenameCommitsMidCall() throws Exception {
        long id = insertReading("corr-1", "ocr-1");

        assertThat(whileRenameIsPending(() -> repository.findReadingByCorrelationId(SCHEMA, "ocr-1")))
                .map(TelemetryReadingRecord::id)
                .contains(id);
    }

    @Test
    void overloadsWithoutAnOcrIdOpenTheTransactionTheirLockNeeds() {
        // Each delegates to its longer overload on this, past the proxy, so needs @Transactional itself.
        Long id = repository.createFlowReading(SCHEMA, SCHEME, OPERATOR, READING_AT,
                BigDecimal.ONE, BigDecimal.ONE, "corr-1", "", null);
        repository.updateFlowReadingFromIngestion(SCHEMA, id, READING_AT, BigDecimal.TEN, BigDecimal.TEN,
                "corr-1", "", null, OPERATOR);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT confirmed_reading FROM " + SCHEMA + ".flow_reading_table WHERE id = ?", BigDecimal.class, id))
                .isEqualByComparingTo(BigDecimal.TEN);
    }

    /**
     * Runs {@code call} while another session holds V46's rename uncommitted, and commits the rename
     * once {@code call} is queued behind its lock, so the call's column check and statement straddle the
     * commit.
     */
    private <T> T whileRenameIsPending(Supplier<T> call) throws Exception {
        try (Connection renamer = dataSource.getConnection(); Statement statement = renamer.createStatement()) {
            renamer.setAutoCommit(false);
            statement.execute("ALTER TABLE %s.flow_reading_table RENAME COLUMN %s TO %s"
                    .formatted(SCHEMA, PRE_V46_COLUMN, COLUMN));

            CompletableFuture<T> result = CompletableFuture.supplyAsync(call);
            awaitLockWaiter(result);
            renamer.commit();
            return result.get(AWAIT.toSeconds(), TimeUnit.SECONDS);
        }
    }

    private void awaitLockWaiter(CompletableFuture<?> call) throws InterruptedException {
        long deadline = System.nanoTime() + AWAIT.toNanos();
        while (!call.isDone()) {
            if (Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                    SELECT EXISTS (
                        SELECT 1 FROM pg_locks
                        WHERE relation = to_regclass(?) AND NOT granted)
                    """, Boolean.class, SCHEMA + ".flow_reading_table"))) {
                return;
            }
            assertThat(System.nanoTime()).as("call queued behind the rename").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    private long insertReading(String correlationId, String ocrCorrelationId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO %s.flow_reading_table
                    (scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading,
                     correlation_id, %s, created_by)
                VALUES (?, ?, ?, 0, 0, ?, ?, ?)
                RETURNING id
                """.formatted(SCHEMA, PRE_V46_COLUMN), Long.class,
                SCHEME, READING_AT, READING_AT.toLocalDate(), correlationId, ocrCorrelationId, OPERATOR);
    }

    private String ocrCorrelationIdOf(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT " + COLUMN + " FROM " + SCHEMA + ".flow_reading_table WHERE id = ?", String.class, id);
    }
}
