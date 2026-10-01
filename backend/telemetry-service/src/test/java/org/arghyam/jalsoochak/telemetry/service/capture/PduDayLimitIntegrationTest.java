package org.arghyam.jalsoochak.telemetry.service.capture;

import org.arghyam.jalsoochak.telemetry.repository.TelemetryTenantRepository;
import org.arghyam.jalsoochak.telemetry.service.PiiEncryptionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two PDU runs for the same scheme and day, sent at the same moment, against a real PostgreSQL
 * instance: the second waits for the first to commit, then counts it.
 */
@Testcontainers
class PduDayLimitIntegrationTest {

    private static final String SCHEMA = "tenant_as";
    private static final long SCHEME = 1L;
    private static final long OPERATOR = 9L;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 30);

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/test-schema.sql");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;

    private TelemetryTenantRepository repository;
    private PduDayLimit limit;
    private ExecutorService pool;
    /** Holds the first run's transaction open, with its row written, until the test releases it. */
    private CountDownLatch releaseFirst;

    @BeforeAll
    static void connect() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        dataSource.setDriverClassName("org.postgresql.Driver");
        jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM " + SCHEMA + ".flow_reading_table");
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        repository = new TelemetryTenantRepository(jdbcTemplate, new PiiEncryptionService(key, key));
        limit = new PduDayLimit(repository, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        pool = Executors.newFixedThreadPool(2);
        releaseFirst = new CountDownLatch(1);
    }

    @AfterEach
    void tearDown() {
        releaseFirst.countDown();
        pool.shutdownNow();
    }

    private long insertRun(String minutes, LocalDate day) {
        return jdbcTemplate.queryForObject("INSERT INTO " + SCHEMA + ".flow_reading_table "
                        + "(scheme_id, reading_at, reading_date, extracted_reading, confirmed_reading, "
                        + " correlation_id, channel_id, created_by) "
                        + "VALUES (?, ?, ?, 0, ?, 'corr-1', 3, ?) RETURNING id",
                Long.class, SCHEME, day.atTime(6, 0), day, new BigDecimal(minutes), OPERATOR);
    }

    /** Starts a run that writes its row, then keeps its transaction open until {@link #releaseFirst}. */
    private Future<Optional<Long>> firstRunHeldOpen(String minutes, LocalDate day) throws InterruptedException {
        CountDownLatch written = new CountDownLatch(1);
        Future<Optional<Long>> first = pool.submit(() -> limit.writeWithinLimit(
                SCHEMA, SCHEME, day, new BigDecimal(minutes), () -> null, () -> {
                    long id = insertRun(minutes, day);
                    written.countDown();
                    try {
                        releaseFirst.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return id;
                }));
        assertThat(written.await(10, SECONDS)).isTrue();
        return first;
    }

    private Future<Optional<Long>> run(String minutes, LocalDate day) {
        return pool.submit(() -> limit.writeWithinLimit(
                SCHEMA, SCHEME, day, new BigDecimal(minutes), () -> null, () -> insertRun(minutes, day)));
    }

    private static void awaitAWaitingAdvisoryLock() throws InterruptedException {
        long deadline = System.nanoTime() + SECONDS.toNanos(10);
        while (jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted", Long.class) == 0) {
            assertThat(System.nanoTime()).as("a run waiting on the day's lock").isLessThan(deadline);
            Thread.sleep(20);
        }
    }

    @Test
    void aRunSentWhileAnotherIsBeingWrittenWaitsForItAndCountsIt() throws Exception {
        insertRun("1000", DAY);
        Future<Optional<Long>> first = firstRunHeldOpen("300", DAY);

        Future<Optional<Long>> second = run("300", DAY);
        awaitAWaitingAdvisoryLock();
        releaseFirst.countDown();

        // Without the lock, the second would have summed 1,000 and been written too: 1,600 minutes.
        assertThat(first.get(10, SECONDS)).isPresent();
        assertThat(second.get(10, SECONDS)).isEmpty();
        assertThat(repository.sumPduMinutesForDay(SCHEMA, SCHEME, DAY, null)).isEqualByComparingTo("1300");
    }

    @Test
    void aRunOnAnotherDayDoesNotWait() throws Exception {
        Future<Optional<Long>> first = firstRunHeldOpen("300", DAY);

        assertThat(run("300", DAY.plusDays(1)).get(10, SECONDS)).isPresent();

        releaseFirst.countDown();
        assertThat(first.get(10, SECONDS)).isPresent();
    }
}
