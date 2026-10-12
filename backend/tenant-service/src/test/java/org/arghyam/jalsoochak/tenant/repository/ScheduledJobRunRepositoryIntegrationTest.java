package org.arghyam.jalsoochak.tenant.repository;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.arghyam.jalsoochak.tenant.enums.ScheduledJobType;
import org.arghyam.jalsoochak.tenant.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Integration tests for {@link ScheduledJobRunRepository} against real PostgreSQL via Testcontainers.
 *
 * <p>The schema under test is the <b>real V62 migration</b>, read from {@code classpath:db/migration/}
 * and applied to a database that already holds tenants. The unique key is what lets exactly one pod
 * run each job once per period, so the claim rules are asserted against it directly, including under
 * concurrent claims.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@DisplayName("ScheduledJobRunRepository Integration Tests")
class ScheduledJobRunRepositoryIntegrationTest {

    private static final int TENANT_MP = 101;
    private static final int TENANT_TR = 102;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 28);
    private static final LocalDateTime SLOT = DAY.atTime(18, 0);
    private static final String POD_A = "tenant-service-a";
    private static final String POD_B = "tenant-service-b";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withInitScript("sql/tenant-common-test-schema.sql");

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    /** Seeds tenants, then applies V62 on top, as on a production database. */
    @BeforeAll
    static void seedThenMigrate() throws Exception {
        String migration = new String(
                new ClassPathResource("db/migration/V62__create_scheduled_job_run_table.sql")
                        .getInputStream().readAllBytes(), UTF_8);

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("""
                    INSERT INTO common_schema.tenant_master_table (id, state_code, title, status, lgd_code)
                    VALUES (101, 'MP', 'Madhya Pradesh', 3, 23), (102, 'TR', 'Tripura', 3, 16)
                    """);
            statement.execute(migration);
        }
    }

    @MockBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    /** PiiEncryptionService needs key env vars that tests do not provide. */
    @MockBean
    private PiiEncryptionService piiEncryptionService;

    @Autowired
    private ScheduledJobRunRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM common_schema.scheduled_job_run_table");
    }

    @Test
    @DisplayName("the first claim wins and records the run as RUNNING; a repeat returns empty")
    void firstClaimWinsAndARepeatLoses() {
        Optional<Long> first = repository.claim(ScheduledJobType.NUDGE, TENANT_MP, DAY, SLOT, POD_A);
        Optional<Long> repeat = repository.claim(ScheduledJobType.NUDGE, TENANT_MP, DAY, SLOT.plusMinutes(1), POD_B);

        assertThat(first).isPresent();
        assertThat(repeat).isEmpty();
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM common_schema.scheduled_job_run_table WHERE id = ?", first.get());
        assertThat(row.get("job_type")).isEqualTo("NUDGE");
        assertThat(row.get("tenant_id")).isEqualTo(TENANT_MP);
        assertThat(row.get("period_key").toString()).isEqualTo("2026-09-28");
        assertThat(row.get("due_at_ist").toString()).startsWith("2026-09-28 18:00");
        assertThat(row.get("status")).isEqualTo("RUNNING");
        assertThat(row.get("claimed_by")).isEqualTo(POD_A);
        assertThat(row.get("claimed_at")).isNotNull();
        assertThat(row.get("finished_at")).isNull();
    }

    @Test
    @DisplayName("a different job, tenant or period can each be claimed")
    void differentJobTenantOrPeriodIsANewClaim() {
        assertThat(repository.claim(ScheduledJobType.NUDGE, TENANT_MP, DAY, SLOT, POD_A)).isPresent();

        assertThat(repository.claim(ScheduledJobType.ESCALATION, TENANT_MP, DAY, SLOT, POD_A)).isPresent();
        assertThat(repository.claim(ScheduledJobType.NUDGE, TENANT_TR, DAY, SLOT, POD_A)).isPresent();
        assertThat(repository.claim(ScheduledJobType.NUDGE, TENANT_MP, DAY.plusDays(1), SLOT.plusDays(1), POD_A))
                .isPresent();
    }

    @Test
    @DisplayName("concurrent claims of the same run produce exactly one winner")
    void concurrentClaimsHaveOneWinner() throws Exception {
        int pods = 8;
        ExecutorService executor = Executors.newFixedThreadPool(pods);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Optional<Long>>> results = new ArrayList<>();
            for (int i = 0; i < pods; i++) {
                String pod = "tenant-service-" + i;
                Callable<Optional<Long>> claim = () -> {
                    start.await();
                    return repository.claim(ScheduledJobType.DAILY_REPORT, TENANT_MP, DAY, SLOT, pod);
                };
                results.add(executor.submit(claim));
            }
            start.countDown();

            int winners = 0;
            for (Future<Optional<Long>> result : results) {
                if (result.get(30, TimeUnit.SECONDS).isPresent()) {
                    winners++;
                }
            }
            assertThat(winners).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM common_schema.scheduled_job_run_table", Integer.class)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("markSucceeded sets the status and finished_at, with no error")
    void markSucceeded() {
        long id = repository.claim(ScheduledJobType.WEEKLY_REPORT, TENANT_MP, DAY, SLOT, POD_A).orElseThrow();

        repository.markSucceeded(id);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, finished_at, error_message FROM common_schema.scheduled_job_run_table WHERE id = ?",
                id);
        assertThat(row.get("status")).isEqualTo("SUCCEEDED");
        assertThat(row.get("finished_at")).isNotNull();
        assertThat(row.get("error_message")).isNull();
    }

    @Test
    @DisplayName("markFailed sets the status, finished_at and the error")
    void markFailed() {
        long id = repository.claim(ScheduledJobType.ESCALATION, TENANT_MP, DAY, SLOT, POD_A).orElseThrow();

        repository.markFailed(id, "java.lang.IllegalStateException: kafka down");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, finished_at, error_message FROM common_schema.scheduled_job_run_table WHERE id = ?",
                id);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("finished_at")).isNotNull();
        assertThat(row.get("error_message")).isEqualTo("java.lang.IllegalStateException: kafka down");
    }

    @Test
    @DisplayName("the foreign key rejects an unknown tenant")
    void rejectsUnknownTenant() {
        assertThatThrownBy(() -> repository.claim(ScheduledJobType.NUDGE, 999, DAY, SLOT, POD_A))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
