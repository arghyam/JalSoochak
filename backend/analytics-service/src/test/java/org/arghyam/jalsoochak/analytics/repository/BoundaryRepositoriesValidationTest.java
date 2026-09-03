package org.arghyam.jalsoochak.analytics.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Argument validation and SQL shape for the boundary repositories.
 *
 * <p>Both build the {@code dim_lgd_location}/{@code dim_department} parent and scheme-scope column
 * names from the caller's level, so an out-of-range level must be rejected before it can be
 * interpolated into SQL rather than silently producing a query against a non-existent column.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Boundary repositories — validation and SQL shape")
class BoundaryRepositoriesValidationTest {

    private static final int TENANT = 1;

    @Mock
    private JdbcTemplate jdbcTemplate;

    private TenantBoundaryRepository lgdRepository;
    private TenantDepartmentBoundaryRepository departmentRepository;

    @BeforeEach
    void setUp() {
        lgdRepository = new TenantBoundaryRepository(jdbcTemplate);
        departmentRepository = new TenantDepartmentBoundaryRepository(jdbcTemplate);

        lenient().when(jdbcTemplate.queryForMap(anyString(), any(Object[].class))).thenReturn(Map.of());
        lenient().when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        lenient().when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
        lenient().when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                .thenReturn(Boolean.FALSE);
    }

    private String capturedListSql() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), any(Object[].class));
        return sql.getValue();
    }

    @Nested
    @DisplayName("TenantBoundaryRepository")
    class LgdBoundaries {

        @ParameterizedTest(name = "tenant_id {0} is rejected")
        @ValueSource(ints = {0, -1})
        void rejectsANonPositiveTenantId(int tenantId) {
            assertThatThrownBy(() -> lgdRepository.getMergedBoundaryForTenant(tenantId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tenant_id must be a positive integer");
        }

        @Test
        void rejectsANullTenantId() {
            assertThatThrownBy(() -> lgdRepository.getMergedBoundaryForTenant(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void mergesOnlyLevelTwoRegionsWithGeometry() {
            lgdRepository.getMergedBoundaryForTenant(TENANT);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).queryForMap(sql.capture(), any(Object[].class));
            assertThat(sql.getValue())
                    .contains("l.lgd_level = 2")
                    .contains("l.geom IS NOT NULL")
                    .contains("ST_UnaryUnion");
        }

        @Test
        void rejectsANonPositiveLgdId() {
            assertThatThrownBy(() -> lgdRepository.getLocationLevel(0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lgd_id must be a positive integer");
            assertThatThrownBy(() -> lgdRepository.getLocationLevel(null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void returnsNullWhenTheLocationLevelIsUnknown() {
            assertThat(lgdRepository.getLocationLevel(999)).isNull();
        }

        @Test
        void returnsTheLocationLevelWhenFound() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                    .thenReturn(List.of(2));

            assertThat(lgdRepository.getLocationLevel(101)).isEqualTo(2);
        }

        @Test
        void rejectsBadArgumentsForTheSingleBoundaryLookup() {
            assertThatThrownBy(() -> lgdRepository.getBoundaryGeoJsonByLgdId(0, 101))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("tenant_id");
            assertThatThrownBy(() -> lgdRepository.getBoundaryGeoJsonByLgdId(TENANT, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("lgd_id");
        }

        @Test
        void returnsNullWhenTheRegionHasNoStoredGeometry() {
            assertThat(lgdRepository.getBoundaryGeoJsonByLgdId(TENANT, 101)).isNull();
        }

        @Test
        void returnsTheStoredGeometryWhenPresent() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                    .thenReturn(List.of("{\"type\":\"Polygon\"}"));

            assertThat(lgdRepository.getBoundaryGeoJsonByLgdId(TENANT, 101))
                    .isEqualTo("{\"type\":\"Polygon\"}");
        }

        @ParameterizedTest(name = "parent level {0} is rejected as having no child level")
        @ValueSource(ints = {6, 7})
        void rejectsAParentLevelWithNoChildLevel(int parentLevel) {
            assertThatThrownBy(() -> lgdRepository.getChildLevelByParent(TENANT, 101, parentLevel))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No child LGD level available");
            assertThatThrownBy(() -> lgdRepository.getMergedBoundaryByParent(TENANT, 101, parentLevel))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No child LGD level available");

            verify(jdbcTemplate, never()).queryForList(anyString(), any(Object[].class));
        }

        @Test
        void rejectsBadArgumentsForTheChildListing() {
            assertThatThrownBy(() -> lgdRepository.getChildLevelByParent(0, 101, 1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenant_id");
            assertThatThrownBy(() -> lgdRepository.getChildLevelByParent(TENANT, 0, 1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parent_lgd_id");
            assertThatThrownBy(() -> lgdRepository.getChildLevelByParent(TENANT, 101, 0))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parent_lgd_level");
            assertThatThrownBy(() -> lgdRepository.getChildLevelByParent(TENANT, 101, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @ParameterizedTest(name = "parent level {0} selects the level-{1} child column")
        @ValueSource(ints = {1, 2, 3, 4, 5})
        void selectsTheChildLevelOneBelowTheParent(int parentLevel) {
            lgdRepository.getChildLevelByParent(TENANT, 101, parentLevel);

            assertThat(capturedListSql())
                    .contains("level_" + (parentLevel + 1) + "_lgd_id")
                    .contains("l.lgd_level = ?");
        }

        @Test
        void countsSchemesScopedToTheChildRegionAndTenant() {
            lgdRepository.getChildLevelByParent(TENANT, 101, 1);

            assertThat(capturedListSql())
                    .contains("FROM analytics_schema.dim_scheme_table s")
                    .contains("s.tenant_id = ?")
                    .contains("AS scheme_count");
        }

        @Test
        void rejectsBadArgumentsForTheMergedChildBoundary() {
            assertThatThrownBy(() -> lgdRepository.getMergedBoundaryByParent(0, 101, 1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("tenant_id");
            assertThatThrownBy(() -> lgdRepository.getMergedBoundaryByParent(TENANT, 0, 1))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parent_lgd_id");
            assertThatThrownBy(() -> lgdRepository.getMergedBoundaryByParent(TENANT, 101, 0))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parent_lgd_level");
        }

        @Test
        void mergesTheChildBoundariesForAValidParent() {
            lgdRepository.getMergedBoundaryByParent(TENANT, 101, 1);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).queryForMap(sql.capture(), any(Object[].class));
            assertThat(sql.getValue()).contains("AS child_count");
        }
    }

    @Nested
    @DisplayName("TenantDepartmentBoundaryRepository")
    class DepartmentBoundaries {

        @Test
        void returnsNullWhenTheDepartmentLevelIsUnknown() {
            assertThat(departmentRepository.getDepartmentLevel(TENANT, 999)).isNull();
        }

        @Test
        void returnsTheDepartmentLevelWhenFound() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                    .thenReturn(List.of(2));

            assertThat(departmentRepository.getDepartmentLevel(TENANT, 501)).isEqualTo(2);
        }

        @Test
        void returnsNullWhenTheDepartmentHasNoStoredGeometry() {
            assertThat(departmentRepository.getBoundaryGeoJsonByDepartmentId(TENANT, 501)).isNull();
        }

        @Test
        void returnsTheStoredDepartmentGeometryWhenPresent() {
            when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                    .thenReturn(List.of("{\"type\":\"Polygon\"}"));

            assertThat(departmentRepository.getBoundaryGeoJsonByDepartmentId(TENANT, 501))
                    .isEqualTo("{\"type\":\"Polygon\"}");
        }

        @ParameterizedTest(name = "parent department level {0} selects the level below it")
        @ValueSource(ints = {1, 2, 3, 4, 5})
        void selectsTheChildLevelOneBelowTheParent(int parentLevel) {
            departmentRepository.getChildDepartmentsByParent(TENANT, 501, parentLevel);

            assertThat(capturedListSql()).contains("level_" + (parentLevel + 1) + "_dept_id");
        }

        @Test
        void mergesTheChildDepartmentBoundariesForAValidParent() {
            departmentRepository.getMergedBoundaryByParentDepartment(TENANT, 501, 1);

            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).queryForMap(sql.capture(), any(Object[].class));
            assertThat(sql.getValue()).contains("AS child_count");
        }

        @Test
        void reportsWhetherATableExists() {
            when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                    .thenReturn(Boolean.TRUE);

            assertThat(departmentRepository.tableExists("analytics_schema", "dim_department_table")).isTrue();
        }

        @Test
        void reportsWhetherAColumnExists() {
            when(jdbcTemplate.queryForObject(anyString(), any(Class.class), any(Object[].class)))
                    .thenReturn(Boolean.TRUE);

            assertThat(departmentRepository.columnExists(
                    "analytics_schema", "dim_department_table", "geom")).isTrue();
        }

        @Test
        void reportsAbsenceForAnUnknownTableOrColumn() {
            assertThat(departmentRepository.tableExists("analytics_schema", "nope")).isFalse();
            assertThat(departmentRepository.columnExists("analytics_schema", "dim_department_table", "nope"))
                    .isFalse();
        }
    }
}
