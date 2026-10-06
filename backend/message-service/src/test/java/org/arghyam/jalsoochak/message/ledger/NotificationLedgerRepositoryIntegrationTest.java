package org.arghyam.jalsoochak.message.ledger;

import org.arghyam.jalsoochak.message.channel.provider.DeliveryReceipt;
import org.arghyam.jalsoochak.message.channel.provider.DeliveryState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The delivery ledger's SQL against a real PostgreSQL, on tables shaped by the <em>real</em> V63
 * migration: {@code apply_notification_ledger_shape} is read out of
 * {@code backend/database/V63__…sql} and applied to the pre-V63 tables in {@code test-schema.sql}, so a
 * column renamed on one side only fails here rather than in production.
 *
 * <p>No Spring context: the repository needs only a {@code DataSource}, and what is under test is SQL —
 * the forward-only transition rule, the user-id guard, truncation, the session-time-zone round trip —
 * none of which a mocked {@code JdbcTemplate} could fail.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("NotificationLedgerRepository against the V63 shape")
class NotificationLedgerRepositoryIntegrationTest {

    private static final Path V63 = Path.of("../database/V63__repurpose_notification_table_as_delivery_ledger.sql");
    private static final String TENANT = "tenant_test";
    private static final String PLATFORM = "common_schema";
    private static final String PROVIDER = "provider-x";

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine").withInitScript("sql/test-schema.sql");

    private static JdbcTemplate jdbc;
    private static NotificationLedgerRepository repository;
    private static int userId;

    @BeforeAll
    static void applyV63Shape() throws Exception {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        jdbc = new JdbcTemplate(ds);
        repository = new NotificationLedgerRepository(ds);

        Matcher fn = Pattern.compile(
                "(CREATE OR REPLACE FUNCTION common_schema\\.apply_notification_ledger_shape.*?\\$func\\$;)",
                Pattern.DOTALL).matcher(Files.readString(V63));
        assertThat(fn.find()).as("V63 defines apply_notification_ledger_shape").isTrue();
        jdbc.execute(fn.group(1));
        jdbc.execute("SELECT common_schema.apply_notification_ledger_shape('" + TENANT + "')");
        jdbc.execute("SELECT common_schema.apply_notification_ledger_shape('" + PLATFORM + "')");
        userId = jdbc.queryForObject("INSERT INTO tenant_test.user_table (title, phone_number, user_type)"
                + " VALUES ('t', 'p', 1) RETURNING id", Integer.class);
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM tenant_test.notification_table");
        jdbc.update("DELETE FROM common_schema.notification_table");
        jdbc.update("DELETE FROM common_schema.notification_status_sync_state");
    }

    @Test
    void theShapeFunctionIsIdempotent() {
        jdbc.execute("SELECT common_schema.apply_notification_ledger_shape('" + TENANT + "')");
        assertThat(repository.ledgerSchemas()).containsExactly(PLATFORM, TENANT);
    }

    @Test
    void opensARowAsDispatchingAndKeepsARealUserId() {
        String uuid = open(TENANT, (long) userId, "msg-ignored");

        Map<String, Object> row = row(TENANT, uuid);
        assertThat(row).containsEntry("dispatch_status", "DISPATCHING")
                .containsEntry("delivery_status", "PENDING")
                .containsEntry("user_id", userId)
                .containsEntry("channel", LedgerChannel.WHATSAPP.id())
                .containsEntry("status_version", 0);
        assertThat(row.get("message_blob").toString()).contains("reportDate");
    }

    @Test
    void anIdWithNoUserRowIsDroppedRatherThanLosingTheRow() {
        String uuid = open(TENANT, 999_999L, null);

        assertThat(row(TENANT, uuid)).containsEntry("user_id", null);
    }

    @Test
    void platformRowsNeverCarryAUserId() {
        String uuid = open(PLATFORM, 5L, null);

        assertThat(row(PLATFORM, uuid)).containsEntry("user_id", null);
    }

    @Test
    void closesWithTheProvidersAnswerAndReturnsTheSnapshot() {
        String uuid = open(TENANT, (long) userId, null);

        Optional<LedgerSnapshot> snapshot = repository.close(TENANT, uuid, accepted("msg-1"));

        assertThat(snapshot).isPresent();
        LedgerSnapshot s = snapshot.get();
        assertThat(s.dispatchStatus()).isEqualTo("ACCEPTED");
        assertThat(s.deliveryStatus()).isEqualTo("PENDING");
        assertThat(s.statusVersion()).isEqualTo(1);
        assertThat(s.userId()).isEqualTo((long) userId);
        assertThat(s.subjectDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(s.dispatchedAt()).isCloseTo(Instant.now(), within60s());
        assertThat(s.createdAt()).isCloseTo(Instant.now(), within60s());
        assertThat(row(TENANT, uuid)).containsEntry("provider_message_id", "msg-1");
    }

    @Test
    void aLongProviderErrorIsTruncatedNotRefused() {
        String uuid = open(TENANT, null, null);

        repository.close(TENANT, uuid, new NotificationLedgerRepository.Closing("FAILED_DELIVERY", "NOT_SENT",
                "SEND", null, null, "E", "x".repeat(900), null, null, null, 10, false));

        assertThat(row(TENANT, uuid).get("provider_error_message").toString()).hasSize(500);
    }

    @Test
    void anUnknownStatusIsRefusedByTheCheckConstraint() {
        String uuid = open(TENANT, null, null);

        assertThatThrownBy(() -> repository.close(TENANT, uuid, new NotificationLedgerRepository.Closing(
                "SENT", "PENDING", null, null, null, null, null, null, null, null, 1, true)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void statusMovesForwardOnly() {
        String uuid = open(TENANT, null, null);
        repository.close(TENANT, uuid, accepted("msg-2"));

        assertThat(apply(TENANT, "msg-2", DeliveryState.DELIVERED, "DELIVERED")).hasSize(1);
        assertThat(apply(TENANT, "msg-2", DeliveryState.READ, "READ")).hasSize(1);
        assertThat(apply(TENANT, "msg-2", DeliveryState.DELIVERED, "DELIVERED")).as("READ is not undone").isEmpty();
        assertThat(apply(TENANT, "msg-2", DeliveryState.FAILED, "ERROR")).as("READ is final").isEmpty();

        Map<String, Object> row = row(TENANT, uuid);
        assertThat(row).containsEntry("delivery_status", "READ")
                .containsEntry("provider_status", "READ")
                .containsEntry("status_version", 3);
        assertThat(row.get("delivered_at")).isNotNull();
        assertThat(row.get("read_at")).isNotNull();
        assertThat(row.get("delivery_settled_at")).isNotNull();
    }

    @Test
    void aRepeatedPendingWordIsNotAChange_butANewOneIs() {
        String uuid = open(TENANT, null, null);
        repository.close(TENANT, uuid, accepted("msg-3"));

        assertThat(apply(TENANT, "msg-3", DeliveryState.PENDING, "ENQUEUED")).hasSize(1);
        assertThat(apply(TENANT, "msg-3", DeliveryState.PENDING, "ENQUEUED")).isEmpty();
        assertThat(apply(TENANT, "msg-3", DeliveryState.PENDING, "SENT")).hasSize(1);
        assertThat(row(TENANT, uuid)).containsEntry("provider_status", "SENT");
    }

    @Test
    void aFailureKeepsTheProvidersCodeAndTheReportedTime() {
        String uuid = open(TENANT, null, null);
        repository.close(TENANT, uuid, accepted("msg-4"));
        Instant reported = Instant.parse("2026-10-05T11:00:00Z");

        List<LedgerSnapshot> changed = repository.applyStatus(TENANT, null, new DeliveryReceipt(PROVIDER, "msg-4",
                null, DeliveryState.FAILED, "ERROR", "131026", "undeliverable", reported, new BigDecimal("0.3"), "INR"));

        assertThat(changed).singleElement().satisfies(s -> {
            assertThat(s.deliveryStatus()).isEqualTo("FAILED");
            assertThat(s.providerErrorCode()).isEqualTo("131026");
            assertThat(s.settledAt()).isEqualTo(reported);
            assertThat(s.cost()).isEqualByComparingTo("0.3");
        });
    }

    @Test
    void aReceiptCanBeMatchedByOurUuid() {
        String uuid = open(PLATFORM, null, null);
        repository.close(PLATFORM, uuid, accepted("sg-1"));

        List<LedgerSnapshot> changed = repository.applyStatus(PLATFORM, uuid, receipt("unrelated-id",
                DeliveryState.DELIVERED, "delivered"));

        assertThat(changed).singleElement().extracting(LedgerSnapshot::deliveryStatus).isEqualTo("DELIVERED");
    }

    @Test
    void aProviderMessageIdIsUniquePerProvider() {
        repository.close(TENANT, open(TENANT, null, null), accepted("dup-1"));
        String second = open(TENANT, null, null);

        assertThatThrownBy(() -> repository.close(TENANT, second, accepted("dup-1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void findsOnlyTheMessagesItHoldsThatCanStillChange() {
        repository.close(TENANT, open(TENANT, null, null), accepted("open-1"));
        repository.close(TENANT, open(TENANT, null, null), accepted("done-1"));
        apply(TENANT, "done-1", DeliveryState.FAILED, "ERROR");

        assertThat(repository.openMessageIds(TENANT, PROVIDER, List.of("open-1", "done-1", "elsewhere")))
                .containsExactly("open-1");
        assertThat(repository.openMessageIds(TENANT, "other-provider", List.of("open-1"))).isEmpty();
    }

    @Test
    void sweepsOnlyPendingRowsInsideTheAgeWindow() {
        repository.close(TENANT, open(TENANT, null, null), accepted("fresh"));
        String old = open(TENANT, null, null);
        repository.close(TENANT, old, accepted("old"));
        String ancient = open(TENANT, null, null);
        repository.close(TENANT, ancient, accepted("ancient"));
        age(TENANT, old, "3 hours");
        age(TENANT, ancient, "4 days");

        assertThat(repository.pendingForSweep(TENANT, LedgerChannel.WHATSAPP.id(), PROVIDER, 120, 72, 10))
                .extracting(NotificationLedgerRepository.PendingRow::providerMessageId).containsExactly("old");
        assertThat(repository.pendingProviders(TENANT, LedgerChannel.WHATSAPP.id(), 72)).containsExactly(PROVIDER);
    }

    @Test
    void marksLongPendingRowsUnresolved() {
        String old = open(TENANT, null, null);
        repository.close(TENANT, old, accepted("stale"));
        age(TENANT, old, "4 days");
        repository.close(TENANT, open(TENANT, null, null), accepted("recent"));

        List<LedgerSnapshot> changed = repository.markUnresolved(TENANT, 72, 100);

        assertThat(changed).singleElement().extracting(LedgerSnapshot::uuid).isEqualTo(old);
        assertThat(row(TENANT, old)).containsEntry("delivery_status", "UNRESOLVED");
        assertThat(apply(TENANT, "stale", DeliveryState.DELIVERED, "DELIVERED"))
                .as("a late report still settles it").hasSize(1);
    }

    @Test
    void purgesInBatches() {
        for (int i = 0; i < 3; i++) {
            age(TENANT, open(TENANT, null, null), "200 days");
        }
        open(TENANT, null, null);

        assertThat(repository.purgeOlderThan(TENANT, 180, 2)).isEqualTo(2);
        assertThat(repository.purgeOlderThan(TENANT, 180, 2)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_test.notification_table", Integer.class)).isOne();
    }

    @Test
    void countsADaysRowsByTypeChannelAndProvider() {
        repository.close(TENANT, open(TENANT, null, null), accepted("s1"));
        repository.close(TENANT, open(TENANT, null, null), accepted("s2"));
        apply(TENANT, "s2", DeliveryState.READ, "READ");
        repository.close(TENANT, open(TENANT, null, null), new NotificationLedgerRepository.Closing(
                "SKIPPED_NO_CONTACT", "NOT_SENT", null, null, null, null, null, null, null, null, 1, false));

        List<NotificationLedgerRepository.DailyStats> stats =
                repository.dailyStats(TENANT, LocalDate.now(ZoneId.of("Asia/Kolkata")));

        assertThat(stats).singleElement().satisfies(s -> {
            assertThat(s.total()).isEqualTo(3);
            assertThat(s.accepted()).isEqualTo(2);
            assertThat(s.delivered()).isEqualTo(1);
            assertThat(s.read()).isEqualTo(1);
            assertThat(s.pending()).isEqualTo(1);
            assertThat(s.notSent()).isEqualTo(1);
        });
    }

    @Test
    void keepsAPullCursor() {
        assertThat(repository.readCursor("src")).isEmpty();
        Instant first = Instant.parse("2026-10-05T10:15:30Z");
        repository.writeCursor("src", first);
        repository.writeCursor("src", first.plusSeconds(60));

        assertThat(repository.readCursor("src")).contains(first.plusSeconds(60));
    }

    @Test
    void refusesASchemaNameThatIsNotAnIdentifier() {
        assertThatThrownBy(() -> repository.ledgerSchemas().forEach(s -> repository.purgeOlderThan(s + "; DROP", 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String open(String schema, Long user, String ignored) {
        String uuid = UUID.randomUUID().toString();
        repository.open(schema, new NotificationLedgerRepository.NewRow(uuid, user, null, "SECTION_OFFICER",
                "hash", LedgerChannel.WHATSAPP.id(), "DAILY_REPORT", "DAILY_REPORT_KPIS", PROVIDER, "42",
                null, "corr", LocalDate.of(2026, 10, 5), "DAILY_REPORT:u1:2026-10-05",
                "{\"reportDate\":\"2026-10-05\"}"));
        return uuid;
    }

    private static NotificationLedgerRepository.Closing accepted(String providerMessageId) {
        return new NotificationLedgerRepository.Closing("ACCEPTED", "PENDING", null, providerMessageId, "accepted",
                null, null, "tpl", null, null, 120, true);
    }

    private static DeliveryReceipt receipt(String id, DeliveryState state, String word) {
        return new DeliveryReceipt(PROVIDER, id, null, state, word, null, null, null, null, null);
    }

    private static List<LedgerSnapshot> apply(String schema, String id, DeliveryState state, String word) {
        return repository.applyStatus(schema, null, receipt(id, state, word));
    }

    private static void age(String schema, String uuid, String interval) {
        jdbc.update("UPDATE " + schema + ".notification_table SET created_at = NOW() - INTERVAL '" + interval
                + "' WHERE uuid = ?", uuid);
    }

    private static Map<String, Object> row(String schema, String uuid) {
        return jdbc.queryForMap("SELECT * FROM " + schema + ".notification_table WHERE uuid = ?", uuid);
    }

    private static org.assertj.core.data.TemporalUnitOffset within60s() {
        return org.assertj.core.api.Assertions.within(60, java.time.temporal.ChronoUnit.SECONDS);
    }
}
