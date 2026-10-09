package org.arghyam.jalsoochak.message.ledger;

import org.arghyam.jalsoochak.message.channel.provider.DeliveryReceipt;
import org.arghyam.jalsoochak.message.channel.provider.DeliveryState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * SQL for the delivery ledger: {@code <schema>.notification_table} in every tenant schema and in
 * {@code common_schema} (V63), and {@code common_schema.notification_status_sync_state}.
 *
 * <p>Every statement is schema-qualified, the schema checked against {@value #SCHEMA_PATTERN} before
 * it is spliced in. Timestamps are written with the database's own {@code NOW()} — the convention of
 * every other table here — and an instant a provider reports is converted in SQL
 * ({@code to_timestamp(ms)::timestamp}) so it lands in the same session time zone as {@code NOW()}.
 * Instants read back are converted the same way in reverse.</p>
 *
 * <p>Two {@link JdbcTemplate}s over the shared {@link DataSource}, built here rather than declared as
 * beans — a {@code JdbcTemplate} bean would replace Spring Boot's auto-configured one for every other
 * component. Writes on the send path time out after {@value #SEND_PATH_TIMEOUT_SECONDS}s, so a stalled
 * database delays a send by seconds rather than by Hikari's 30s; the background jobs get longer.</p>
 */
@Repository
public class NotificationLedgerRepository {

    private static final String SCHEMA_PATTERN = "^[a-z0-9_]+$";
    private static final int SEND_PATH_TIMEOUT_SECONDS = 2;
    private static final int BACKGROUND_TIMEOUT_SECONDS = 60;
    static final int ERROR_MESSAGE_MAX = 500;
    /** Bind parameters per {@code IN} list, well inside PostgreSQL's 32 767 limit. */
    private static final int IN_CHUNK = 500;

    /** The columns a {@link LedgerSnapshot} is built from, instants as epoch milliseconds. */
    private static final String SNAPSHOT_COLUMNS = """
            uuid, status_version, user_id, user_type, message_type, channel, provider, dispatch_status,
            failure_stage, delivery_status, provider_error_code, latency_ms, subject_date, provider_cost,
            provider_cost_currency,
            (EXTRACT(EPOCH FROM created_at::timestamptz) * 1000)::BIGINT          AS created_ms,
            (EXTRACT(EPOCH FROM dispatched_at::timestamptz) * 1000)::BIGINT       AS dispatched_ms,
            (EXTRACT(EPOCH FROM delivered_at::timestamptz) * 1000)::BIGINT        AS delivered_ms,
            (EXTRACT(EPOCH FROM read_at::timestamptz) * 1000)::BIGINT             AS read_ms,
            (EXTRACT(EPOCH FROM delivery_settled_at::timestamptz) * 1000)::BIGINT AS settled_ms""";

    /** A provider-reported instant in the session time zone, or now when the provider gave none. */
    private static final String REPORTED_AT = "COALESCE(to_timestamp(CAST(? AS BIGINT) / 1000.0)::timestamp, NOW())";

    private final JdbcTemplate sendPath;
    private final JdbcTemplate background;

    public NotificationLedgerRepository(DataSource dataSource) {
        this.sendPath = new JdbcTemplate(dataSource);
        this.sendPath.setQueryTimeout(SEND_PATH_TIMEOUT_SECONDS);
        this.background = new JdbcTemplate(dataSource);
        this.background.setQueryTimeout(BACKGROUND_TIMEOUT_SECONDS);
    }

    /** A row as {@link NotificationLedger#open} writes it. */
    record NewRow(String uuid, Long userId, Long adminUserId, String userType, String recipientHash,
                  int channelId, String messageType, String eventType, String provider, String contactRef,
                  String templateRef, String correlationId, LocalDate subjectDate, String dedupeKey,
                  String metadataJson) {}

    /** How a send ended, as {@link #close} writes it. */
    record Closing(String dispatchStatus, String deliveryStatus, String failureStage, String providerMessageId,
                   String providerStatus, String errorCode, String errorMessage, String templateRef,
                   BigDecimal cost, String costCurrency, Integer latencyMs, boolean dispatched) {}

    /** A pending row the status sweep should ask its provider about. */
    public record PendingRow(String uuid, String providerMessageId, Instant createdAt) {}

    /** One day's counts for one message type and channel in one schema. */
    public record DailyStats(String messageType, int channelId, String provider, long total, long accepted,
                             long delivered, long read, long failed, long pending, long unresolved,
                             long notTracked, long notSent) {}

    /**
     * Inserts an open row ({@code DISPATCHING}). {@code user_id} is taken only if it names a real
     * {@code user_table} row — a stale id would otherwise fail the foreign key and lose the whole row —
     * and is always {@code NULL} in {@code common_schema}, which has no {@code user_table}.
     */
    public void open(String schema, NewRow row) {
        String s = checked(schema);
        String userIdExpr = "common_schema".equals(s)
                ? "(SELECT CAST(? AS INTEGER) WHERE FALSE)"
                : "(SELECT u.id FROM " + s + ".user_table u WHERE u.id = CAST(? AS BIGINT))";
        sendPath.update("INSERT INTO " + s + ".notification_table ("
                        + " uuid, user_id, admin_user_id, user_type, recipient_hash, channel, message_type,"
                        + " event_type, provider, provider_contact_ref, template_ref, correlation_id, subject_date,"
                        + " dedupe_key, dispatch_status, delivery_status, message_blob)"
                        + " VALUES (?, " + userIdExpr + ", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DISPATCHING', 'PENDING',"
                        + " CAST(? AS JSONB))",
                row.uuid(), row.userId(), row.adminUserId(), row.userType(), row.recipientHash(), row.channelId(),
                row.messageType(), truncate(row.eventType(), 60), truncate(row.provider(), 40),
                truncate(row.contactRef(), 64), truncate(row.templateRef(), 64), truncate(row.correlationId(), 64),
                row.subjectDate() == null ? null : Date.valueOf(row.subjectDate()), truncate(row.dedupeKey(), 160),
                row.metadataJson());
    }

    /** Records how the send ended and returns the row as it now stands. */
    public Optional<LedgerSnapshot> close(String schema, String uuid, Closing c) {
        String s = checked(schema);
        List<LedgerSnapshot> rows = sendPath.query("UPDATE " + s + ".notification_table SET"
                        + " dispatch_status = ?, delivery_status = ?, failure_stage = ?, provider_message_id = ?,"
                        + " provider_status = ?, provider_error_code = ?, provider_error_message = ?,"
                        + " template_ref = COALESCE(?, template_ref), provider_cost = ?, provider_cost_currency = ?,"
                        + " latency_ms = ?, dispatched_at = CASE WHEN ? THEN NOW() ELSE dispatched_at END,"
                        + " status_updated_at = NOW(), updated_at = NOW(), status_version = status_version + 1"
                        + " WHERE uuid = ? RETURNING " + SNAPSHOT_COLUMNS,
                snapshotMapper(s),
                c.dispatchStatus(), c.deliveryStatus(), truncate(c.failureStage(), 32),
                truncate(c.providerMessageId(), 128), truncate(c.providerStatus(), 40), truncate(c.errorCode(), 64),
                truncate(c.errorMessage(), ERROR_MESSAGE_MAX), truncate(c.templateRef(), 64), c.cost(),
                truncate(c.costCurrency(), 8), c.latencyMs(), c.dispatched(), uuid);
        return rows.stream().findFirst();
    }

    /**
     * Applies a provider report to the row it is about — by our uuid when the provider echoed our
     * reference, otherwise by the provider's message id. Either way only a row that went out through the
     * reporting provider matches, so one vendor's reports can never change another vendor's rows, however
     * the report came to name them. Moves the row forward only (see
     * {@link DeliveryState#canAdvanceTo}); a pending row also takes a new provider word, so its
     * {@code provider_status} follows the provider while it waits. A report that changes nothing
     * matches nothing, so a status read again on the next pass is not a change.
     *
     * @return the rows changed, as they now stand: none, or one
     */
    public List<LedgerSnapshot> applyStatus(String schema, String uuid, DeliveryReceipt r) {
        String s = checked(schema);
        String key = uuid != null
                ? "uuid = ? AND provider = ?"
                : "provider = ? AND provider_message_id = ?";
        String next = r.state().name();
        Long reportedAtMs = r.occurredAt() == null ? null : r.occurredAt().toEpochMilli();
        String sql = "UPDATE " + s + ".notification_table SET"
                + " delivery_status = CAST(? AS VARCHAR),"
                + " provider_status = COALESCE(?, provider_status),"
                + " provider_error_code = COALESCE(?, provider_error_code),"
                + " provider_error_message = COALESCE(?, provider_error_message),"
                + " delivered_at = CASE WHEN CAST(? AS VARCHAR) IN ('DELIVERED', 'READ')"
                + "     THEN COALESCE(delivered_at, " + REPORTED_AT + ") ELSE delivered_at END,"
                + " read_at = CASE WHEN CAST(? AS VARCHAR) = 'READ'"
                + "     THEN COALESCE(read_at, " + REPORTED_AT + ") ELSE read_at END,"
                + " delivery_settled_at = CASE WHEN CAST(? AS VARCHAR) IN ('DELIVERED', 'READ', 'FAILED')"
                + "     THEN COALESCE(delivery_settled_at, " + REPORTED_AT + ") ELSE delivery_settled_at END,"
                + " provider_cost = COALESCE(?, provider_cost),"
                + " provider_cost_currency = COALESCE(?, provider_cost_currency),"
                + " status_check_count = LEAST(status_check_count + 1, 32767),"
                + " status_updated_at = NOW(), updated_at = NOW(), status_version = status_version + 1"
                + " WHERE " + key
                + " AND ((delivery_status IN ('PENDING', 'UNRESOLVED', 'NOT_TRACKED')"
                + "        AND CAST(? AS VARCHAR) IN ('DELIVERED', 'READ', 'FAILED'))"
                + "   OR (delivery_status = 'DELIVERED' AND CAST(? AS VARCHAR) = 'READ')"
                + "   OR (delivery_status = 'PENDING' AND CAST(? AS VARCHAR) = 'PENDING'"
                + "        AND provider_status IS DISTINCT FROM CAST(? AS VARCHAR)))"
                + " RETURNING " + SNAPSHOT_COLUMNS;
        String providerStatus = truncate(r.providerStatus(), 40);
        Object[] keyArgs = uuid != null
                ? new Object[]{uuid, r.providerId()}
                : new Object[]{r.providerId(), r.providerMessageId()};
        Object[] head = {
                next, providerStatus, truncate(r.errorCode(), 64), truncate(r.errorReason(), ERROR_MESSAGE_MAX),
                next, reportedAtMs, next, reportedAtMs, next, reportedAtMs,
                r.cost(), truncate(r.costCurrency(), 8)};
        Object[] tail = {next, next, next, providerStatus};
        Object[] args = new Object[head.length + keyArgs.length + tail.length];
        System.arraycopy(head, 0, args, 0, head.length);
        System.arraycopy(keyArgs, 0, args, head.length, keyArgs.length);
        System.arraycopy(tail, 0, args, head.length + keyArgs.length, tail.length);
        return background.query(sql, snapshotMapper(s), args);
    }

    /**
     * Gives up on rows still pending after {@code olderThanHours}: marks up to {@code limit} of them
     * {@code UNRESOLVED}, oldest first. A later report can still settle them.
     */
    public List<LedgerSnapshot> markUnresolved(String schema, int olderThanHours, int limit) {
        String s = checked(schema);
        return background.query("UPDATE " + s + ".notification_table SET delivery_status = 'UNRESOLVED',"
                        + " status_updated_at = NOW(), updated_at = NOW(), status_version = status_version + 1"
                        + " WHERE id IN (SELECT id FROM " + s + ".notification_table"
                        + "   WHERE delivery_status = 'PENDING' AND created_at < NOW() - make_interval(hours => ?)"
                        + "   ORDER BY created_at LIMIT ?)"
                        + " RETURNING " + SNAPSHOT_COLUMNS,
                snapshotMapper(s), olderThanHours, limit);
    }

    /**
     * Pending rows on one channel and provider that the provider can be asked about, aged between
     * {@code minAgeMinutes} and {@code maxAgeHours}, oldest first.
     */
    public List<PendingRow> pendingForSweep(String schema, int channelId, String provider, int minAgeMinutes,
                                            int maxAgeHours, int limit) {
        String s = checked(schema);
        return background.query("SELECT uuid, provider_message_id,"
                        + " (EXTRACT(EPOCH FROM created_at::timestamptz) * 1000)::BIGINT AS created_ms"
                        + " FROM " + s + ".notification_table"
                        + " WHERE delivery_status = 'PENDING' AND channel = ? AND provider = ?"
                        + "   AND provider_message_id IS NOT NULL"
                        + "   AND created_at < NOW() - make_interval(mins => ?)"
                        + "   AND created_at > NOW() - make_interval(hours => ?)"
                        + " ORDER BY created_at LIMIT ?",
                (rs, n) -> new PendingRow(rs.getString("uuid"), rs.getString("provider_message_id"),
                        Instant.ofEpochMilli(rs.getLong("created_ms"))),
                channelId, provider, minAgeMinutes, maxAgeHours, limit);
    }

    /**
     * Which of {@code providerMessageIds} this schema holds in a state a report can still change. Lets a
     * status pull find each message's schema with one query per schema, and skip messages already
     * settled, instead of attempting an update per message per schema.
     */
    public List<String> openMessageIds(String schema, String provider, Collection<String> providerMessageIds) {
        String s = checked(schema);
        if (providerMessageIds == null || providerMessageIds.isEmpty()) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        List<String> ids = new ArrayList<>(providerMessageIds);
        for (int start = 0; start < ids.size(); start += IN_CHUNK) {
            List<String> chunk = ids.subList(start, Math.min(ids.size(), start + IN_CHUNK));
            Object[] args = new Object[chunk.size() + 1];
            args[0] = provider;
            for (int i = 0; i < chunk.size(); i++) {
                args[i + 1] = chunk.get(i);
            }
            found.addAll(background.queryForList("SELECT provider_message_id FROM " + s + ".notification_table"
                            + " WHERE provider = ? AND provider_message_id IN ("
                            + String.join(", ", Collections.nCopies(chunk.size(), "?")) + ")"
                            + "   AND delivery_status IN ('PENDING', 'UNRESOLVED', 'NOT_TRACKED', 'DELIVERED')",
                    String.class, args));
        }
        return found;
    }

    /** The providers with pending rows on a channel in a schema, so a sweep asks only those. */
    public List<String> pendingProviders(String schema, int channelId, int maxAgeHours) {
        String s = checked(schema);
        return background.queryForList("SELECT DISTINCT provider FROM " + s + ".notification_table"
                        + " WHERE delivery_status = 'PENDING' AND channel = ? AND provider_message_id IS NOT NULL"
                        + "   AND created_at > NOW() - make_interval(hours => ?)",
                String.class, channelId, maxAgeHours);
    }

    /** Every schema whose {@code notification_table} has the ledger shape: the tenants and {@code common_schema}. */
    public List<String> ledgerSchemas() {
        return background.queryForList("SELECT table_schema FROM information_schema.columns"
                        + " WHERE table_name = 'notification_table' AND column_name = 'status_version'"
                        + "   AND (table_schema = 'common_schema' OR table_schema LIKE 'tenant\\_%')"
                        + " ORDER BY table_schema",
                String.class);
    }

    /** Deletes up to {@code batchSize} rows older than {@code retentionDays}; returns how many. */
    public int purgeOlderThan(String schema, int retentionDays, int batchSize) {
        String s = checked(schema);
        return background.update("DELETE FROM " + s + ".notification_table WHERE id IN ("
                        + " SELECT id FROM " + s + ".notification_table"
                        + " WHERE created_at < NOW() - make_interval(days => ?) LIMIT ?)",
                retentionDays, batchSize);
    }

    /** Counts for the rows created on {@code day} in IST, by message type, channel and provider. */
    public List<DailyStats> dailyStats(String schema, LocalDate day) {
        String s = checked(schema);
        return background.query("SELECT message_type, channel, provider, COUNT(*) AS total,"
                        + " COUNT(*) FILTER (WHERE dispatch_status = 'ACCEPTED') AS accepted,"
                        + " COUNT(*) FILTER (WHERE delivery_status IN ('DELIVERED', 'READ')) AS delivered,"
                        + " COUNT(*) FILTER (WHERE delivery_status = 'READ') AS read,"
                        + " COUNT(*) FILTER (WHERE delivery_status = 'FAILED') AS failed,"
                        + " COUNT(*) FILTER (WHERE delivery_status = 'PENDING') AS pending,"
                        + " COUNT(*) FILTER (WHERE delivery_status = 'UNRESOLVED') AS unresolved,"
                        + " COUNT(*) FILTER (WHERE delivery_status = 'NOT_TRACKED') AS not_tracked,"
                        + " COUNT(*) FILTER (WHERE delivery_status = 'NOT_SENT') AS not_sent"
                        + " FROM " + s + ".notification_table"
                        + " WHERE created_at >= (CAST(? AS DATE)::timestamp AT TIME ZONE 'Asia/Kolkata')::timestamp"
                        + "   AND created_at < ((CAST(? AS DATE) + 1)::timestamp AT TIME ZONE 'Asia/Kolkata')::timestamp"
                        + " GROUP BY message_type, channel, provider ORDER BY message_type, channel, provider",
                (rs, n) -> new DailyStats(rs.getString("message_type"), rs.getInt("channel"),
                        rs.getString("provider"), rs.getLong("total"), rs.getLong("accepted"),
                        rs.getLong("delivered"), rs.getLong("read"), rs.getLong("failed"), rs.getLong("pending"),
                        rs.getLong("unresolved"), rs.getLong("not_tracked"), rs.getLong("not_sent")),
                Date.valueOf(day), Date.valueOf(day));
    }

    /** Where a status pull last got to, or empty on its first run. */
    public Optional<Instant> readCursor(String source) {
        List<Long> rows = background.queryForList("SELECT (EXTRACT(EPOCH FROM cursor_at::timestamptz) * 1000)::BIGINT"
                        + " FROM common_schema.notification_status_sync_state WHERE source = ?",
                Long.class, source);
        return rows.isEmpty() || rows.get(0) == null ? Optional.empty() : Optional.of(Instant.ofEpochMilli(rows.get(0)));
    }

    /** Records where a status pull got to. */
    public void writeCursor(String source, Instant cursor) {
        background.update("INSERT INTO common_schema.notification_status_sync_state (source, cursor_at, updated_at)"
                        + " VALUES (?, to_timestamp(CAST(? AS BIGINT) / 1000.0)::timestamp, NOW())"
                        + " ON CONFLICT (source) DO UPDATE SET cursor_at = EXCLUDED.cursor_at, updated_at = NOW()",
                source, cursor.toEpochMilli());
    }

    private static RowMapper<LedgerSnapshot> snapshotMapper(String schema) {
        return (rs, n) -> new LedgerSnapshot(schema,
                rs.getString("uuid"),
                rs.getInt("status_version"),
                rs.getObject("user_id") == null ? null : rs.getLong("user_id"),
                rs.getString("user_type"),
                rs.getString("message_type"),
                rs.getObject("channel") == null ? null : rs.getInt("channel"),
                rs.getString("provider"),
                rs.getString("dispatch_status"),
                rs.getString("failure_stage"),
                rs.getString("delivery_status"),
                rs.getString("provider_error_code"),
                rs.getObject("latency_ms") == null ? null : rs.getInt("latency_ms"),
                rs.getDate("subject_date") == null ? null : rs.getDate("subject_date").toLocalDate(),
                rs.getBigDecimal("provider_cost"),
                rs.getString("provider_cost_currency"),
                instant(rs, "created_ms"),
                instant(rs, "dispatched_ms"),
                instant(rs, "delivered_ms"),
                instant(rs, "read_ms"),
                instant(rs, "settled_ms"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        long ms = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(ms);
    }

    private static String checked(String schema) {
        if (schema == null || !schema.matches(SCHEMA_PATTERN)) {
            throw new IllegalArgumentException("Not a valid schema name: " + schema);
        }
        return schema;
    }

    static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
