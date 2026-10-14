package org.arghyam.jalsoochak.analytics.repository;

import org.arghyam.jalsoochak.analytics.repository.FactNotificationDeliveryRepository.NotificationDeliveryFact;
import org.arghyam.jalsoochak.analytics.service.NotificationDeliveryAggregationService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The V59 tables under real Flyway: the version-guarded fact upsert, and the daily rollups being
 * overwritten (never incremented) from the facts, including NULL tenant / user type keys.
 */
@JdbcTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({FactNotificationDeliveryRepository.class, NotificationDeliveryAggregationRepository.class,
        NotificationDeliveryAggregationService.class})
class NotificationDeliveryRepositoriesIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("notification_delivery_test")
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
    private FactNotificationDeliveryRepository factRepository;
    @Autowired
    private NotificationDeliveryAggregationService aggregationService;

    private static final LocalDate DAY = LocalDate.of(2026, 10, 6);
    private static final LocalDate NEXT_DAY = DAY.plusDays(1);

    private static final String DAILY_ROWS_SQL = """
            SELECT id, tenant_id, stat_date, message_type, channel, provider, user_type, attempted, accepted,
                   suppressed, skipped, dispatch_failed, delivered, read, delivery_failed, pending, unresolved,
                   not_tracked, account_level_failures, distinct_users, avg_latency_ms, max_latency_ms,
                   avg_time_to_deliver_s, total_cost, cost_currency
            FROM analytics_schema.agg_notification_delivery_daily_table
            ORDER BY id
            """;

    private static final String FAILURE_ROWS_SQL = """
            SELECT id, tenant_id, stat_date, message_type, channel, provider, failure_stage,
                   provider_error_code, failures
            FROM analytics_schema.agg_notification_failure_daily_table
            ORDER BY id
            """;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("""
                TRUNCATE analytics_schema.fact_notification_delivery_table,
                         analytics_schema.agg_notification_delivery_daily_table,
                         analytics_schema.agg_notification_failure_daily_table
                RESTART IDENTITY
                """);
    }

    private static NotificationDeliveryFact.NotificationDeliveryFactBuilder fact(String uuid, int version) {
        LocalDateTime dispatchedAt = DAY.atTime(18, 0);
        return NotificationDeliveryFact.builder()
                .notificationUuid(uuid)
                .statusVersion(version)
                .tenantId(12)
                .messageType("DAILY_REPORT")
                .channel("WHATSAPP")
                .provider("provider-a")
                .userId(4411L)
                .userType("SECTION_OFFICER")
                .dispatchStatus("ACCEPTED")
                .deliveryStatus("PENDING")
                .createdAtSource(dispatchedAt.minusSeconds(1))
                .dispatchedAt(dispatchedAt)
                .dispatchDate(DAY)
                .subjectDate(DAY.minusDays(1))
                .latencyMs(400);
    }

    private Map<String, Object> factRow(String uuid) {
        return jdbcTemplate.queryForMap(
                "SELECT * FROM analytics_schema.fact_notification_delivery_table WHERE notification_uuid = ?", uuid);
    }

    private List<Map<String, Object>> dailyRows() {
        return jdbcTemplate.queryForList(DAILY_ROWS_SQL);
    }

    private List<Map<String, Object>> failureRows() {
        return jdbcTemplate.queryForList(FAILURE_ROWS_SQL);
    }

    // ---- fact upsert --------------------------------------------------------------------------

    @Test
    void upsert_insertsANewNotification() {
        boolean written = factRepository.upsert(fact("u-1", 1)
                .costAmount(new BigDecimal("0.3"))
                .costCurrency("INR")
                .build());

        assertThat(written).isTrue();
        Map<String, Object> row = factRow("u-1");
        assertThat(row.get("status_version")).isEqualTo(1);
        assertThat(row.get("tenant_id")).isEqualTo(12);
        assertThat(row.get("delivery_status")).isEqualTo("PENDING");
        assertThat(row.get("dispatch_date")).isEqualTo(java.sql.Date.valueOf(DAY));
        assertThat((BigDecimal) row.get("cost_amount")).isEqualByComparingTo("0.3");
    }

    @Test
    void upsert_aHigherVersionOverwrites() {
        factRepository.upsert(fact("u-1", 1).build());

        boolean written = factRepository.upsert(fact("u-1", 2)
                .deliveryStatus("DELIVERED")
                .deliveredAt(DAY.atTime(18, 0, 30))
                .timeToDeliverS(30)
                .build());

        assertThat(written).isTrue();
        Map<String, Object> row = factRow("u-1");
        assertThat(row.get("status_version")).isEqualTo(2);
        assertThat(row.get("delivery_status")).isEqualTo("DELIVERED");
        assertThat(row.get("time_to_deliver_s")).isEqualTo(30);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM analytics_schema.fact_notification_delivery_table", Integer.class)).isEqualTo(1);
    }

    @Test
    void upsert_aLowerVersionChangesNothing() {
        factRepository.upsert(fact("u-1", 3).deliveryStatus("READ").build());

        boolean written = factRepository.upsert(fact("u-1", 2).deliveryStatus("DELIVERED").build());

        assertThat(written).isFalse();
        Map<String, Object> row = factRow("u-1");
        assertThat(row.get("status_version")).isEqualTo(3);
        assertThat(row.get("delivery_status")).isEqualTo("READ");
    }

    @Test
    void upsert_theSameVersionAgainChangesNothing() {
        factRepository.upsert(fact("u-1", 3).deliveryStatus("READ").build());

        boolean written = factRepository.upsert(fact("u-1", 3).deliveryStatus("FAILED").build());

        assertThat(written).isFalse();
        assertThat(factRow("u-1").get("delivery_status")).isEqualTo("READ");
    }

    // ---- daily rollups ------------------------------------------------------------------------

    @Test
    void recompute_countsEachStatusIntoItsColumn() {
        factRepository.upsert(fact("u-1", 1).deliveryStatus("DELIVERED")
                .deliveredAt(DAY.atTime(18, 0, 10)).timeToDeliverS(10)
                .costAmount(new BigDecimal("0.30")).costCurrency("INR").build());
        factRepository.upsert(fact("u-2", 1).deliveryStatus("READ").userId(4412L)
                .readAt(DAY.atTime(18, 0, 30)).timeToDeliverS(30).latencyMs(800)
                .costAmount(new BigDecimal("0.30")).costCurrency("INR").build());
        factRepository.upsert(fact("u-3", 1).deliveryStatus("FAILED").providerErrorCode("131026").build());
        factRepository.upsert(fact("u-4", 1).deliveryStatus("FAILED").providerErrorCode("9999").build());
        factRepository.upsert(fact("u-5", 1).dispatchStatus("PROVIDER_REJECTED").deliveryStatus("NOT_SENT")
                .failureStage("DISPATCH").providerErrorCode("9999").latencyMs(null).build());
        factRepository.upsert(fact("u-6", 1).dispatchStatus("SKIPPED_NO_CONTACT").deliveryStatus("NOT_SENT")
                .dispatchedAt(null).latencyMs(null).build());
        factRepository.upsert(fact("u-7", 1).dispatchStatus("SUPPRESSED").deliveryStatus("NOT_SENT")
                .dispatchedAt(null).latencyMs(null).build());
        factRepository.upsert(fact("u-8", 1).deliveryStatus("PENDING").build());
        factRepository.upsert(fact("u-9", 1).deliveryStatus("UNRESOLVED").build());
        factRepository.upsert(fact("u-10", 1).deliveryStatus("NOT_TRACKED").build());

        aggregationService.recompute(DAY, DAY);

        List<Map<String, Object>> rows = dailyRows();
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("attempted")).isEqualTo(10);
        assertThat(row.get("accepted")).isEqualTo(7);
        assertThat(row.get("suppressed")).isEqualTo(1);
        assertThat(row.get("skipped")).isEqualTo(1);
        assertThat(row.get("dispatch_failed")).isEqualTo(1);
        assertThat(row.get("delivered")).isEqualTo(2);
        assertThat(row.get("read")).isEqualTo(1);
        assertThat(row.get("delivery_failed")).isEqualTo(2);
        assertThat(row.get("pending")).isEqualTo(1);
        assertThat(row.get("unresolved")).isEqualTo(1);
        assertThat(row.get("not_tracked")).isEqualTo(1);
        assertThat(row.get("account_level_failures")).isEqualTo(2);
        assertThat(row.get("distinct_users")).isEqualTo(2);
        // latency 400 x6, 800 x1
        assertThat(row.get("avg_latency_ms")).isEqualTo(457);
        assertThat(row.get("max_latency_ms")).isEqualTo(800);
        assertThat(row.get("avg_time_to_deliver_s")).isEqualTo(20);
        assertThat((BigDecimal) row.get("total_cost")).isEqualByComparingTo("0.60");
        assertThat(row.get("cost_currency")).isEqualTo("INR");

        List<Map<String, Object>> failures = failureRows();
        assertThat(failures).hasSize(3);
        assertThat(failures).extracting(r -> r.get("provider_error_code") + "/" + r.get("failure_stage")
                        + "=" + r.get("failures"))
                .containsExactlyInAnyOrder("131026/null=1", "9999/null=1", "9999/DISPATCH=1");
    }

    @Test
    void recompute_runTwice_leavesIdenticalRows() {
        factRepository.upsert(fact("u-1", 1).deliveryStatus("DELIVERED").build());
        factRepository.upsert(fact("u-2", 1).deliveryStatus("FAILED").providerErrorCode("131026").build());
        factRepository.upsert(fact("u-3", 1).tenantId(null).userType(null).build());

        aggregationService.recompute(DAY, DAY);
        List<Map<String, Object>> firstDaily = dailyRows();
        List<Map<String, Object>> firstFailures = failureRows();
        aggregationService.recompute(DAY, DAY);

        assertThat(firstDaily).hasSize(2);
        assertThat(firstFailures).hasSize(1);
        assertThat(dailyRows()).isEqualTo(firstDaily);
        assertThat(failureRows()).isEqualTo(firstFailures);
    }

    @Test
    void recompute_afterAStatusChange_correctsTheCounts() {
        factRepository.upsert(fact("u-1", 1).deliveryStatus("PENDING").build());
        factRepository.upsert(fact("u-2", 1).dispatchStatus("DELIVERY_UNCONFIRMED").deliveryStatus("UNRESOLVED")
                .failureStage("DISPATCH").build());
        aggregationService.recompute(DAY, DAY);
        assertThat(failureRows()).hasSize(1);

        factRepository.upsert(fact("u-1", 2).deliveryStatus("DELIVERED").build());
        factRepository.upsert(fact("u-2", 2).dispatchStatus("ACCEPTED").deliveryStatus("DELIVERED")
                .failureStage(null).build());
        aggregationService.recompute(DAY, DAY);

        List<Map<String, Object>> rows = dailyRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("attempted")).isEqualTo(2);
        assertThat(rows.get(0).get("pending")).isEqualTo(0);
        assertThat(rows.get(0).get("unresolved")).isEqualTo(0);
        assertThat(rows.get(0).get("dispatch_failed")).isEqualTo(0);
        assertThat(rows.get(0).get("delivered")).isEqualTo(2);
        // The failure group no longer exists in the facts, so its row goes too.
        assertThat(failureRows()).isEmpty();
    }

    @Test
    void recompute_platformLevelFactsOnOneDay_collapseIntoOneRow() {
        factRepository.upsert(fact("u-1", 1).tenantId(null).messageType("LOGIN_OTP").channel("EMAIL")
                .userId(null).userType(null).deliveryStatus("NOT_TRACKED").build());
        factRepository.upsert(fact("u-2", 1).tenantId(null).messageType("LOGIN_OTP").channel("EMAIL")
                .userId(null).userType(null).deliveryStatus("NOT_TRACKED").build());

        aggregationService.recompute(DAY, DAY);
        aggregationService.recompute(DAY, DAY);

        List<Map<String, Object>> rows = dailyRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("tenant_id")).isNull();
        assertThat(rows.get(0).get("user_type")).isNull();
        assertThat(rows.get(0).get("attempted")).isEqualTo(2);
        assertThat(rows.get(0).get("distinct_users")).isEqualTo(0);
    }

    @Test
    void recompute_onlyTouchesTheWindow() {
        factRepository.upsert(fact("u-1", 1).build());
        factRepository.upsert(fact("u-2", 1).dispatchDate(NEXT_DAY).build());

        aggregationService.recompute(DAY, DAY);

        assertThat(dailyRows()).extracting(r -> r.get("stat_date"))
                .containsExactly(java.sql.Date.valueOf(DAY));
    }
}
