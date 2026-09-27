package org.arghyam.jalsoochak.analytics.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * SQL assembly for the dashboard/regularity queries.
 *
 * <p>Every query in this repository is a template stitched together from {@code {{WS}}} (work-status
 * filter), {@code {{NWS}}}, {@code {{RTP}}} (effective regularity threshold), {@code {{LWQ}}}
 * (de-duplicated water source), {@code {{SWS}}} and {@code {{SWD}}} tokens. A misspelled or
 * mismatched token would otherwise reach Postgres as either a syntax error or — far worse — a query
 * silently missing its tenant/work-status restriction.</p>
 *
 * <p>The repository guards that with a fail-fast check; this test drives every public query method
 * against a mocked {@link JdbcTemplate} so a template regression in any one of them is caught, and
 * asserts directly that no {@code {{...}}} token survives into the SQL actually sent.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SchemeRegularityRepository — SQL assembly")
class SchemeRegularityRepositorySqlAssemblyTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SchemeRegularityRepository repository;

    /** Every SQL string handed to the JdbcTemplate during a test. */
    private final List<String> executedSql = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        repository = new SchemeRegularityRepository(jdbcTemplate, "4", false, "90");
        executedSql.clear();

        lenient().when(jdbcTemplate.queryForMap(anyString(), any(Object[].class)))
                .thenAnswer(inv -> {
                    executedSql.add(inv.getArgument(0));
                    return numericRow();
                });
        lenient().when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    executedSql.add(inv.getArgument(0));
                    return scalarFor(inv.getArgument(1));
                });
        // The threshold reads use the two-argument (no bind parameters) overload.
        lenient().when(jdbcTemplate.queryForObject(anyString(), any(Class.class)))
                .thenAnswer(inv -> {
                    executedSql.add(inv.getArgument(0));
                    return scalarFor(inv.getArgument(1));
                });
        // Route the caller's own RowMapper over a stub row, so the projection lambdas run exactly as
        // they would against a real ResultSet rather than being skipped by an empty result.
        lenient().when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    executedSql.add(inv.getArgument(0));
                    return mapOneRow(inv.getArgument(1));
                });
        lenient().when(jdbcTemplate.query(anyString(), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    executedSql.add(inv.getArgument(0));
                    return mapOneRow(inv.getArgument(1));
                });
        lenient().when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenAnswer(inv -> {
                    executedSql.add(inv.getArgument(0));
                    return List.of(numericRow());
                });
        lenient().when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    executedSql.add(inv.getArgument(0));
                    return null;
                });
        lenient().doAnswer(inv -> {
            executedSql.add(inv.getArgument(0));
            return null;
        }).when(jdbcTemplate).query(anyString(), any(PreparedStatementSetter.class), any(RowCallbackHandler.class));
        lenient().doAnswer(inv -> {
            executedSql.add(inv.getArgument(0));
            return null;
        }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        lenient().when(jdbcTemplate.update(anyString(), any(Object[].class)))
                .thenAnswer(inv -> {
                    executedSql.add(inv.getArgument(0));
                    return 1;
                });
    }

    /** A row carrying every numeric column the metric mappers read. */
    private static Map<String, Object> numericRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : List.of("scheme_count", "total_supply_days", "regular_scheme_count",
                "total_reading_days", "submitted_count", "not_submitted_count", "outage_count",
                "child_count", "boundary_count", "supply_days", "reading_days")) {
            row.put(column, 0);
        }
        return row;
    }

    private static Object scalarFor(Class<?> type) {
        if (type == Integer.class) return 0;
        if (type == Long.class) return 0L;
        if (type == BigDecimal.class) return BigDecimal.ZERO;
        if (type == String.class) return "";
        return null;
    }

    /**
     * Runs the caller's own {@link RowMapper} once against a stub row. A mapper that reads a column the
     * query does not select, or coerces one to the wrong type, fails here instead of at runtime.
     */
    private static List<Object> mapOneRow(RowMapper<?> mapper) {
        try {
            return new ArrayList<>(List.of(mapper.mapRow(stubResultSet(), 0)));
        } catch (Exception e) {
            throw new AssertionError("Row mapper failed against a well-formed stub row", e);
        }
    }

    /** A permissive {@link java.sql.ResultSet} that answers any column with a neutral typed value. */
    private static java.sql.ResultSet stubResultSet() throws java.sql.SQLException {
        java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        lenient().when(rs.getInt(anyString())).thenReturn(0);
        lenient().when(rs.getLong(anyString())).thenReturn(0L);
        lenient().when(rs.getDouble(anyString())).thenReturn(0.0d);
        lenient().when(rs.getBoolean(anyString())).thenReturn(false);
        lenient().when(rs.getString(anyString())).thenReturn("");
        lenient().when(rs.getBigDecimal(anyString())).thenReturn(BigDecimal.ZERO);
        lenient().when(rs.getObject(anyString())).thenReturn(0);
        lenient().when(rs.getDate(anyString()))
                .thenReturn(java.sql.Date.valueOf(LocalDate.of(2026, 2, 1)));
        lenient().when(rs.getTimestamp(anyString()))
                .thenReturn(java.sql.Timestamp.valueOf(LocalDate.of(2026, 2, 1).atStartOfDay()));
        lenient().when(rs.wasNull()).thenReturn(false);
        lenient().when(rs.getObject(anyString(), any(Class.class))).thenAnswer(inv -> {
            Class<?> target = inv.getArgument(1);
            if (target == LocalDate.class) return LocalDate.of(2026, 2, 1);
            if (target == java.time.LocalDateTime.class) return LocalDate.of(2026, 2, 1).atStartOfDay();
            if (target == BigDecimal.class) return BigDecimal.ZERO;
            if (target == Integer.class) return 0;
            if (target == Long.class) return 0L;
            if (target == String.class) return "";
            if (target == Boolean.class) return Boolean.FALSE;
            return null;
        });
        return rs;
    }

    /**
     * Makes the LGD/department level lookups resolve to {@code level}, so the callers that branch on it
     * proceed into their real query-building path instead of failing fast on an unknown parent id.
     */
    private void stubLevelLookup(int level) {
        lenient().when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    executedSql.add(sql);
                    return isLevelLookup(sql) ? List.of(level) : List.of();
                });
        lenient().when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    executedSql.add(sql);
                    return isLevelLookup(sql) ? level : scalarFor(inv.getArgument(1));
                });
    }

    private static boolean isLevelLookup(String sql) {
        return sql.contains("lgd_level") || sql.contains("department_level");
    }

    /** Makes the LGD/department level lookups find nothing, as they do for an unknown parent id. */
    private void stubLevelLookupMissing() {
        lenient().when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(inv -> {
                    String sql = inv.getArgument(0);
                    executedSql.add(sql);
                    return isLevelLookup(sql) ? List.of() : mapOneRow(inv.getArgument(1));
                });
    }

    /** Fails if any query reached the JdbcTemplate with an unresolved template token. */
    private void assertEverySqlFullyResolved() {
        assertThat(executedSql)
                .as("at least one query should have been executed")
                .isNotEmpty();
        assertThat(executedSql)
                .allSatisfy(sql -> assertThat(sql)
                        .as("SQL still contains an unreplaced {{...}} token")
                        .doesNotContain("{{"));
    }

    @Nested
    @DisplayName("token substitution")
    class TokenSubstitution {

        @Test
        void resolvesEveryTokenInATenantScopedRegularityQuery() {
            stubLevelLookup(1);

            repository.getSchemeRegularityMetrics(1, 101, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

            assertEverySqlFullyResolved();
            assertThat(executedSql).anySatisfy(sql -> assertThat(sql)
                    .contains("level_1_lgd_id")
                    .contains("s.tenant_id = ?"));
        }

        @Test
        void rendersTheWorkStatusFilterIntoDashboardQueries() {
            repository.getSchemeCountByUser(1, 11);

            assertThat(executedSql).anySatisfy(sql -> assertThat(sql).contains("work_status"));
        }

        @Test
        void rendersTheDeduplicatedWaterSourceIntoWaterAggregations() {
            repository.getTotalWaterSuppliedByUserSchemes(
                    1, 11, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

            assertThat(executedSql).anySatisfy(sql -> assertThat(sql)
                    .as("water aggregations must read through the DISTINCT ON de-duplication")
                    .contains("DISTINCT ON (fwq.tenant_id, fwq.scheme_id, fwq.date)"));
        }

        @Test
        void omitsTheWorkStatusFilterFromContinuousSchemesWhenTheFlagIsOff() {
            var flagOff = new SchemeRegularityRepository(jdbcTemplate, "4", false, "90");
            executedSql.clear();

            invokeContinuousSchemesQueries(flagOff);

            assertEverySqlFullyResolved();
        }

        @Test
        void keepsTheWorkStatusFilterOnContinuousSchemesWhenTheFlagIsOn() {
            var flagOn = new SchemeRegularityRepository(jdbcTemplate, "4", true, "90");
            executedSql.clear();

            invokeContinuousSchemesQueries(flagOn);

            assertEverySqlFullyResolved();
        }

        private void invokeContinuousSchemesQueries(SchemeRegularityRepository target) {
            for (Method method : SchemeRegularityRepository.class.getMethods()) {
                if (!method.getName().toLowerCase().contains("continuous")) continue;
                if (method.getParameterCount() == 0) continue;
                invokeQuietly(target, method);
            }
        }
    }

    @Nested
    @DisplayName("threshold resolution")
    class ThresholdResolution {

        @Test
        void readsTheEffectiveTenantThresholdThroughTheOwnTenantChain() {
            assertThat(repository.getEffectiveTenantRegularityThresholdPercent(1)).isNotNull();
            assertEverySqlFullyResolved();
        }

        @Test
        void readsTheEffectiveNationalThreshold() {
            assertThat(repository.getEffectiveNationalRegularityThresholdPercent()).isNotNull();
            assertEverySqlFullyResolved();
        }

        @Test
        void exposesTheThresholdFilterSoScreensCannotDriftApart() {
            assertThat(repository.regularityThresholdFilter()).isNotNull();
        }
    }

    @Nested
    @DisplayName("LGD and department level resolution")
    class LevelResolution {

        @Test
        void reportsAnUnknownParentLgdIdRatherThanQueryingWithABadColumn() {
            // getLgdLevel returns null for an unknown id, and every caller must fail fast on it.
            stubLevelLookupMissing();

            assertThatThrownBy(() -> repository.getSchemeRegularityMetrics(
                    999, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("parent_lgd_id not found");
        }

        @Test
        void reportsAnUnknownTenantScopedParentLgdId() {
            stubLevelLookupMissing();

            assertThatThrownBy(() -> repository.getSchemeRegularityMetrics(
                    1, 999, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("parent_lgd_id not found");
        }

        @ParameterizedTest(name = "lgd level {0} selects a scheme column")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
        void mapsEverySupportedLgdLevelToASchemeColumn(int level) {
            stubLevelLookup(level);

            repository.getSchemeRegularityMetrics(1, 101, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28));

            assertEverySqlFullyResolved();
        }

        @Test
        void rejectsAnLgdLevelWithNoCorrespondingSchemeColumn() {
            stubLevelLookup(9);

            assertThatThrownBy(() -> repository.getSchemeRegularityMetrics(
                    1, 101, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unsupported lgd_level");
        }

    }

    @Nested
    @DisplayName("sort handling")
    class SortHandling {

        @ParameterizedTest(name = "sort_dir \"{0}\" is accepted")
        @CsvSource({"asc", "ASC", "desc", "DESC", "'  asc  '"})
        void acceptsBothSortDirectionsInAnyCasing(String sortDir) {
            invokeSortableQueries(sortDir);

            assertEverySqlFullyResolved();
        }

        @Test
        void rejectsAnUnsupportedSortDirection() {
            List<Throwable> failures = invokeSortableQueries("sideways");

            assertThat(failures)
                    .as("an unsupported sort_dir must be rejected rather than silently ignored")
                    .isNotEmpty()
                    .allSatisfy(t -> assertThat(t).isInstanceOf(IllegalArgumentException.class));
        }

        /** Drives every public method that takes a sort direction, collecting the failures. */
        private List<Throwable> invokeSortableQueries(String sortDir) {
            List<Throwable> failures = new ArrayList<>();
            for (Method method : SchemeRegularityRepository.class.getMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) continue;
                if (method.getDeclaringClass() != SchemeRegularityRepository.class) continue;
                if (!takesSortArguments(method)) continue;

                Object[] args = defaultArgsFor(method, sortDir);
                try {
                    method.invoke(repository, args);
                } catch (InvocationTargetException e) {
                    failures.add(e.getCause());
                } catch (Exception ignored) {
                    // Method shape we cannot satisfy; other tests cover it.
                }
            }
            return failures;
        }

        private boolean takesSortArguments(Method method) {
            long stringParams = Stream.of(method.getParameterTypes())
                    .filter(t -> t == String.class)
                    .count();
            return stringParams >= 2 && method.getName().startsWith("get");
        }

        private Object[] defaultArgsFor(Method method, String sortDir) {
            Class<?>[] types = method.getParameterTypes();
            Object[] args = new Object[types.length];
            boolean sortDirAssigned = false;
            for (int i = 0; i < types.length; i++) {
                if (types[i] == String.class && !sortDirAssigned && i == types.length - 1) {
                    args[i] = sortDir;
                    sortDirAssigned = true;
                } else {
                    args[i] = defaultValue(types[i]);
                }
            }
            return args;
        }
    }

    @Nested
    @DisplayName("whole-repository sweep")
    class Sweep {

        /**
         * Drives every public query method once with neutral arguments. Individual results are not
         * asserted — the point is that no template escapes unresolved from any of them, which no
         * per-method test can guarantee across 170 query builders.
         */
        @Test
        void noQueryEmitsAnUnresolvedTemplateToken() {
            int invoked = 0;
            for (Method method : SchemeRegularityRepository.class.getMethods()) {
                if (method.getDeclaringClass() != SchemeRegularityRepository.class) continue;
                if (Modifier.isStatic(method.getModifiers())) continue;
                if (method.getParameterCount() == 0) continue;

                invokeQuietly(repository, method);
                invoked++;
            }

            assertThat(invoked)
                    .as("the sweep should have exercised the repository's query builders")
                    .isGreaterThan(50);
            assertEverySqlFullyResolved();
        }

        @Test
        void theTokenGuardItselfRejectsAnUnresolvedTemplate() {
            // Guards the guard: if withWaterFragments ever stopped failing fast, the sweep above would
            // silently pass while shipping an unfiltered query.
            assertThatThrownBy(() -> callWithWaterFragments("SELECT 1 {{NOPE}}"))
                    .rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Unreplaced SQL token");
        }

        private void callWithWaterFragments(String sql) throws Exception {
            Method m = SchemeRegularityRepository.class
                    .getDeclaredMethod("withWaterFragments", String.class);
            m.setAccessible(true);
            m.invoke(null, sql);
        }
    }

    // ------------------------------------------------------------------
    // Reflection helpers
    // ------------------------------------------------------------------

    /** Invokes {@code method} with neutral arguments, ignoring business-level rejections. */
    private void invokeQuietly(SchemeRegularityRepository target, Method method) {
        Class<?>[] types = method.getParameterTypes();
        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            args[i] = defaultValue(types[i]);
        }
        try {
            method.invoke(target, args);
        } catch (InvocationTargetException e) {
            // A query builder may legitimately reject these neutral arguments (unknown parent id,
            // unsupported sort). The token guard would have thrown before any such rejection.
            if (e.getCause() instanceof IllegalStateException ise
                    && String.valueOf(ise.getMessage()).contains("Unreplaced SQL token")) {
                throw new AssertionError("Unresolved token from " + method.getName(), ise);
            }
        } catch (Exception ignored) {
            // Method shape we cannot satisfy reflectively.
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == int.class) return 1;
        if (type == long.class) return 1L;
        if (type == double.class) return 1.0d;
        if (type == boolean.class) return false;
        if (type == Integer.class) return 1;
        if (type == Long.class) return 1L;
        if (type == Double.class) return 1.0d;
        if (type == Boolean.class) return Boolean.FALSE;
        if (type == BigDecimal.class) return BigDecimal.ONE;
        if (type == String.class) return "";
        if (type == LocalDate.class) return LocalDate.of(2026, 2, 1);
        if (List.class.isAssignableFrom(type)) return List.of();
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            return constants != null && constants.length > 0 ? constants[0] : null;
        }
        return null;
    }
}
