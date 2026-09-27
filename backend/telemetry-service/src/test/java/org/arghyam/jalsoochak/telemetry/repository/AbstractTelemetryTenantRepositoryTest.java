package org.arghyam.jalsoochak.telemetry.repository;

import org.arghyam.jalsoochak.telemetry.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Shared harness for the {@link TelemetryTenantRepository} unit tests.
 *
 * <p>{@code TelemetryTenantRepository} is a raw-JDBC repository: almost all of its behaviour is in the
 * SQL it assembles per tenant schema and in the inline {@link RowMapper} lambdas that turn a
 * {@link ResultSet} into a projection record. Mocking {@link JdbcTemplate} alone would leave those
 * mappers unexecuted, so this harness routes every stubbed {@code query(...)} call back through the
 * caller's own {@code RowMapper} against a stub {@code ResultSet} built from a plain column map.</p>
 *
 * <p>That gives each test two assertions for free: the SQL/parameters the repository sent, and the
 * record it produced from the row it got back.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
abstract class AbstractTelemetryTenantRepositoryTest {

    protected static final String SCHEMA = "tenant_as";

    @Mock
    protected JdbcTemplate jdbcTemplate;

    @Mock
    protected PiiEncryptionService piiEncryptionService;

    protected TelemetryTenantRepository repository;

    /** SQL-fragment → rows, consulted in registration order by the {@code query(...)} stubs. */
    private final List<QueryRule> queryRules = new ArrayList<>();

    /** SQL-fragment + requested type → scalar, consulted by the {@code queryForObject(...)} stub. */
    private final List<ScalarRule> scalarRules = new ArrayList<>();

    @BeforeEach
    void initRepository() {
        queryRules.clear();
        scalarRules.clear();

        repository = new TelemetryTenantRepository(jdbcTemplate, piiEncryptionService);
        ReflectionTestUtils.setField(repository, "metadataCacheEnabled", true);
        ReflectionTestUtils.setField(repository, "tenantSchemaListCacheTtlMs", 300_000L);
        ReflectionTestUtils.setField(repository, "columnExistsCacheTtlMs", 600_000L);

        // PII helpers are pass-through by default; individual tests can override.
        lenient().when(piiEncryptionService.safeDecrypt(anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(piiEncryptionService.hmac(anyString()))
                .thenAnswer(inv -> "hmac(" + inv.getArgument(0, String.class) + ")");

        // query(sql, rowMapper) — the no-parameter overload.
        lenient().when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenAnswer(inv -> mapRows(inv.getArgument(0), inv.getArgument(1)));

        // query(sql, rowMapper, args...) — the varargs overload.
        lenient().when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> mapRows(inv.getArgument(0), inv.getArgument(1)));

        // queryForObject(sql, requiredType, args...)
        lenient().when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                .thenAnswer(inv -> scalarFor(inv.getArgument(0), inv.getArgument(1), varargsOf(inv, 2)));

        lenient().when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    // ------------------------------------------------------------------
    // Stub registration
    // ------------------------------------------------------------------

    /** Rows returned for any SQL containing {@code fragment}. Later rules win over earlier ones. */
    @SafeVarargs
    protected final void onQuery(String fragment, Map<String, Object>... rows) {
        List<Map<String, Object>> fixed = List.of(rows);
        queryRules.add(0, new QueryRule(sql -> sql.contains(fragment), () -> fixed));
    }

    /** Rows returned for any SQL containing every one of {@code fragments}. */
    protected final void onQueryMatching(List<String> fragments, List<Map<String, Object>> rows) {
        queryRules.add(0, new QueryRule(sql -> fragments.stream().allMatch(sql::contains), () -> rows));
    }

    /**
     * Rows that differ per call, for exercising read-insert-reread races: the first invocation gets
     * {@code perCall[0]}, the second {@code perCall[1]}, and so on, with the last entry repeating.
     */
    @SafeVarargs
    protected final void onQuerySequence(String fragment, List<Map<String, Object>>... perCall) {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        queryRules.add(0, new QueryRule(sql -> sql.contains(fragment), () -> {
            int index = Math.min(calls.getAndIncrement(), perCall.length - 1);
            return perCall[index];
        }));
    }

    /** Scalar returned from {@code queryForObject} for any SQL containing {@code fragment}. */
    protected final void onScalar(String fragment, Class<?> type, Object value) {
        scalarRules.add(0, new ScalarRule(sql -> sql.contains(fragment), type, value));
    }

    /**
     * Answer for the {@code information_schema.columns} probe behind every {@code columnExists} guard,
     * which decides whether the repository emits the modern or the legacy SQL for a tenant schema.
     * Applies to every column, so the repository takes the fully-migrated (or fully-legacy) path.
     */
    protected final void onColumnExists(boolean exists) {
        presentColumns = exists ? ALL_COLUMNS : List.of();
    }

    /**
     * Makes only the named columns exist; every other {@code columnExists} probe returns false.
     * The probed column arrives as the third bind parameter, not in the SQL text, so this is matched
     * on parameters rather than on the (constant) {@code information_schema} query.
     */
    protected final void onColumnsExisting(String... columnNames) {
        presentColumns = List.of(columnNames);
    }

    /** Columns the repository should believe exist; {@code null} means "none configured". */
    private List<String> presentColumns;

    /** Sentinel list meaning "every probed column exists". */
    private static final List<String> ALL_COLUMNS = List.of("*");

    // ------------------------------------------------------------------
    // Verification helpers
    // ------------------------------------------------------------------

    /** Captures the SQL of the single {@code update(...)} the repository issued. */
    protected String capturedUpdateSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        return sql.getValue();
    }

    /** Captures the parameters of the single {@code update(...)} the repository issued. */
    protected Object[] capturedUpdateArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        return args.getValue();
    }

    /** All {@code update(...)} SQL statements, in call order. */
    protected List<String> allUpdateSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce()).update(sql.capture(), any(Object[].class));
        return sql.getAllValues();
    }

    /** SQL of the {@code queryForObject(..., Number.class, ...)} call behind a RETURNING-id insert. */
    protected String capturedInsertSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce())
                .queryForObject(sql.capture(), org.mockito.ArgumentMatchers.eq(Number.class), any(Object[].class));
        return sql.getAllValues().get(sql.getAllValues().size() - 1);
    }

    /** Parameters of the {@code queryForObject(..., Number.class, ...)} call behind a RETURNING-id insert. */
    protected Object[] capturedInsertArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce())
                .queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Number.class), args.capture());
        return args.getAllValues().get(args.getAllValues().size() - 1);
    }

    /** All {@code query(...)} SQL statements issued through the varargs overload, in call order. */
    protected List<String> allQuerySql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeastOnce())
                .query(sql.capture(), any(RowMapper.class), any(Object[].class));
        return sql.getAllValues();
    }

    // ------------------------------------------------------------------
    // Row building
    // ------------------------------------------------------------------

    /** Builds a column map; keys are column labels as the repository's row mappers read them. */
    protected static Map<String, Object> row(Object... keyValuePairs) {
        if (keyValuePairs.length % 2 != 0) {
            throw new IllegalArgumentException("row() needs alternating key/value arguments");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            values.put((String) keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return values;
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private List<Object> mapRows(String sql, RowMapper<?> mapper) throws Exception {
        List<Map<String, Object>> rows = queryRules.stream()
                .filter(r -> r.match.test(sql))
                .findFirst()
                .map(rule -> rule.rows.get())
                .orElse(List.of());

        List<Object> mapped = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            mapped.add(mapper.mapRow(stubResultSet(rows.get(i)), i));
        }
        return mapped;
    }

    private Object scalarFor(String sql, Class<?> type, Object[] args) {
        // columnExists(schema, table, column) probes information_schema with the column as a parameter.
        if (sql.contains("information_schema.columns")) {
            if (presentColumns == null) {
                return Boolean.FALSE;
            }
            if (presentColumns == ALL_COLUMNS) {
                return Boolean.TRUE;
            }
            String probedColumn = args.length >= 3 ? String.valueOf(args[2]) : "";
            return presentColumns.contains(probedColumn);
        }

        for (ScalarRule rule : scalarRules) {
            if (!rule.match.test(sql)) continue;
            if (rule.type != null && !rule.type.equals(type)) continue;
            return rule.value;
        }
        return defaultScalar(type);
    }

    /** Flattens Mockito's varargs capture, which may arrive as one array argument or as separate ones. */
    private static Object[] varargsOf(org.mockito.invocation.InvocationOnMock inv, int fromIndex) {
        Object[] all = inv.getArguments();
        if (all.length == fromIndex + 1 && all[fromIndex] instanceof Object[] nested) {
            return nested;
        }
        Object[] tail = new Object[Math.max(0, all.length - fromIndex)];
        for (int i = 0; i < tail.length; i++) {
            tail[i] = all[fromIndex + i];
        }
        return tail;
    }

    private Object defaultScalar(Class<?> type) {
        if (type == Boolean.class) return Boolean.FALSE;
        if (type == Integer.class) return 0;
        return null;
    }

    /**
     * A {@link ResultSet} whose accessors read from a column map, so the repository's real row-mapper
     * lambdas run against it exactly as they would against a JDBC row.
     */
    protected static ResultSet stubResultSet(Map<String, Object> values) throws Exception {
        ResultSet rs = mock(ResultSet.class);

        lenient().when(rs.getObject(anyString())).thenAnswer(inv -> values.get(key(inv)));
        lenient().when(rs.getObject(anyString(), any(Class.class))).thenAnswer(inv -> {
            Object raw = values.get(key(inv));
            Class<?> target = inv.getArgument(1);
            return coerce(raw, target);
        });
        lenient().when(rs.getString(anyString())).thenAnswer(inv -> {
            Object raw = values.get(key(inv));
            return raw == null ? null : String.valueOf(raw);
        });
        lenient().when(rs.getBigDecimal(anyString())).thenAnswer(inv -> toBigDecimal(values.get(key(inv))));
        lenient().when(rs.getTimestamp(anyString())).thenAnswer(inv -> {
            Object raw = values.get(key(inv));
            if (raw == null) return null;
            if (raw instanceof Timestamp ts) return ts;
            if (raw instanceof LocalDateTime ldt) return Timestamp.valueOf(ldt);
            return null;
        });
        lenient().when(rs.getDate(anyString())).thenAnswer(inv -> {
            Object raw = values.get(key(inv));
            if (raw == null) return null;
            if (raw instanceof Date d) return d;
            if (raw instanceof LocalDate ld) return Date.valueOf(ld);
            return null;
        });
        lenient().when(rs.getInt(anyString())).thenAnswer(inv -> {
            Object raw = values.get(key(inv));
            return raw instanceof Number n ? n.intValue() : 0;
        });
        lenient().when(rs.getLong(anyString())).thenAnswer(inv -> {
            Object raw = values.get(key(inv));
            return raw instanceof Number n ? n.longValue() : 0L;
        });
        lenient().when(rs.getBoolean(anyString())).thenAnswer(inv -> {
            Object raw = values.get(key(inv));
            return Boolean.TRUE.equals(raw);
        });
        return rs;
    }

    private static String key(org.mockito.invocation.InvocationOnMock inv) {
        return inv.getArgument(0, String.class);
    }

    private static Object coerce(Object raw, Class<?> target) {
        if (raw == null) return null;
        if (target.isInstance(raw)) return raw;
        if (target == LocalDate.class) {
            if (raw instanceof Date d) return d.toLocalDate();
            if (raw instanceof LocalDateTime ldt) return ldt.toLocalDate();
        }
        if (target == LocalDateTime.class) {
            if (raw instanceof Timestamp ts) return ts.toLocalDateTime();
            if (raw instanceof LocalDate ld) return ld.atStartOfDay();
        }
        if (target == BigDecimal.class) return toBigDecimal(raw);
        return raw;
    }

    private static BigDecimal toBigDecimal(Object raw) {
        if (raw == null) return null;
        if (raw instanceof BigDecimal bd) return bd;
        if (raw instanceof Number n) return new BigDecimal(n.toString());
        if (raw instanceof String s) return new BigDecimal(s);
        return null;
    }

    private record QueryRule(Predicate<String> match,
                             java.util.function.Supplier<List<Map<String, Object>>> rows) {
    }

    private static class ScalarRule {
        private final Predicate<String> match;
        private final Class<?> type;
        private final Object value;

        ScalarRule(Predicate<String> match, Class<?> type, Object value) {
            this.match = match;
            this.type = type;
            this.value = value;
        }
    }
}
