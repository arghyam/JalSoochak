package org.arghyam.jalsoochak.scheme.repository;

import org.arghyam.jalsoochak.scheme.dto.SchemeDTO;
import org.arghyam.jalsoochak.scheme.dto.SchemeMappingDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemeDbRepositoryTest {

    @Mock
    JdbcTemplate jdbcTemplate;

    private SchemeDbRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SchemeDbRepository(jdbcTemplate);
    }

    @Test
    void listSchemes_andCountSchemes_mapAndReturnData() throws Exception {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    // columnExists() probes information_schema; return "absent" so this test keeps
                    // exercising the pre-existing (unfiltered) query shape.
                    if (invocation.getArgument(0, String.class).contains("information_schema")) {
                        return List.of();
                    }
                    @SuppressWarnings("unchecked")
                    RowMapper<SchemeDTO> mapper = invocation.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getInt("id")).thenReturn(1);
                    when(rs.getString("uuid")).thenReturn("u1");
                    when(rs.getString("state_scheme_id")).thenReturn("SS-1");
                    when(rs.getString("centre_scheme_id")).thenReturn("C-1");
                    when(rs.getString("scheme_name")).thenReturn("Scheme One");
                    when(rs.getInt("fhtc_count")).thenReturn(10);
                    when(rs.getInt("planned_fhtc")).thenReturn(20);
                    when(rs.getInt("house_hold_count")).thenReturn(30);
                    when(rs.getObject("latitude")).thenReturn(12.3d);
                    when(rs.getObject("longitude")).thenReturn(77.6d);
                    when(rs.getObject("channel")).thenReturn(1);
                    when(rs.getObject("work_status")).thenReturn(1);
                    when(rs.getObject("operating_status")).thenReturn(0);
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(4L);

        List<SchemeDTO> rows = repository.listSchemes(
                "tenant_ka", "SS-1", "Scheme", "name", List.of(1), List.of(2),
                "scheme_name", "asc", 0, 10
        );
        long total = repository.countSchemes("tenant_ka", "SS-1", "Scheme", "name", List.of(1), List.of(2));

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getSchemeName()).isEqualTo("Scheme One");
        assertThat(rows.getFirst().getWorkStatus()).isEqualTo("Ongoing");
        assertThat(rows.getFirst().getOperatingStatus()).isEqualTo("Non-Operative");
        assertThat(total).isEqualTo(4);
    }

    @Test
    void listAndCountSchemeMappings_handleDepartmentTablePresence() throws Exception {
        when(jdbcTemplate.queryForObject(eq("SELECT to_regclass(?)"), eq(String.class), anyString()))
                .thenReturn("ok", "ok");
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    RowMapper<SchemeMappingDTO> mapper = invocation.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getLong("id")).thenReturn(7L);
                    when(rs.getObject("scheme_id")).thenReturn(1);
                    when(rs.getString("state_scheme_id")).thenReturn("SS-1");
                    when(rs.getString("scheme_name")).thenReturn("Scheme One");
                    when(rs.getString("village_lgd_code")).thenReturn("V001");
                    when(rs.getString("village_name")).thenReturn("Village");
                    when(rs.getString("sub_division_name")).thenReturn("North");
                    return List.of(mapper.mapRow(rs, 0));
                });
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);

        List<SchemeMappingDTO> rows = repository.listSchemeMappings(
                "tenant_ka", "scheme", List.of(1), List.of(2), "V", "North", "sub_division_name", "desc", 0, 10
        );
        long total = repository.countSchemeMappings("tenant_ka", "scheme", List.of(1), List.of(2), "V", "North");

        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().subDivisionName()).isEqualTo("North");
        assertThat(total).isEqualTo(2);
    }

    @Test
    void listSchemeMappings_returnsEmptyWhenSubDivisionFilterUsedWithoutDepartmentTables() {
        when(jdbcTemplate.queryForObject(eq("SELECT to_regclass(?)"), eq(String.class), anyString()))
                .thenReturn(null, null);

        List<SchemeMappingDTO> rows = repository.listSchemeMappings(
                "tenant_ka", null, null, null, null, "North", "id", "desc", 0, 10
        );
        long total = repository.countSchemeMappings("tenant_ka", null, null, null, null, "North");

        assertThat(rows).isEmpty();
        assertThat(total).isZero();
    }

    @Test
    void findSchemeById_returnsNullWhenNoRow() {
        when(jdbcTemplate.queryForObject(anyString(), any(RowMapper.class), eq(99)))
                .thenThrow(new EmptyResultDataAccessException(1));

        SchemeDTO scheme = repository.findSchemeById("tenant_ka", 99);

        assertThat(scheme).isNull();
    }

    @Test
    void existenceAndLookupHelpers_coverMainBranches() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(10))).thenReturn(true);
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), eq(20))).thenReturn(false);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(11))).thenReturn(1001);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("a@b.com"))).thenReturn(2002);

        assertThat(repository.existsSchemeById("tenant_ka", 10)).isTrue();
        assertThat(repository.existsLgdLocationById("tenant_ka", 20)).isFalse();
        assertThat(repository.existsDepartmentLocationById("tenant_ka", 20)).isFalse();
        assertThat(repository.findTenantIdByUserId("tenant_ka", 11)).isEqualTo(1001);
        assertThat(repository.findUserIdByEmail("tenant_ka", "a@b.com")).isEqualTo(2002);
        assertThat(repository.findUserIdByEmail("tenant_ka", " ")).isNull();
        assertThat(repository.findTenantIdByUserId("tenant_ka", null)).isNull();
        assertThat(repository.isUserStateAdmin("tenant_ka", null)).isFalse();
    }

    @Test
    void mappingAndIdHelpers_returnEmptyOnEmptyInput() {
        assertThat(repository.findExistingSchemeIds("tenant_ka", List.of())).isEmpty();
        assertThat(repository.findExistingLgdLocationIds("tenant_ka", List.of())).isEmpty();
        assertThat(repository.findExistingDepartmentLocationIds("tenant_ka", List.of())).isEmpty();
        assertThat(repository.findSchemeIdsByStateSchemeIds("tenant_ka", List.of())).isEmpty();
        assertThat(repository.findLgdIdsByCodes("tenant_ka", List.of())).isEmpty();
        assertThat(repository.findDepartmentIdsByTitles("tenant_ka", List.of())).isEmpty();
        assertThat(repository.findSchemeLgdMappingsBySchemeIds("tenant_ka", List.of())).isEmpty();
        assertThat(repository.findSchemeDepartmentMappingsBySchemeIds("tenant_ka", List.of())).isEmpty();
        assertThat(repository.findExistingSchemeLgdMappingKeys("tenant_ka", List.of(), List.of(1))).isEqualTo(Set.of());
        assertThat(repository.findExistingSchemeDepartmentMappingKeys("tenant_ka", List.of(1), List.of())).isEqualTo(Set.of());
        assertThat(repository.findSchemeSnapshotsByStateSchemeIds("tenant_ka", List.of())).isEqualTo(Map.of());
        assertThat(repository.findSchemeAnalyticsRowsByStateSchemeIds("tenant_ka", List.of())).isEqualTo(List.of());
        assertThat(repository.findSchemeAnalyticsRowsBySchemeIds("tenant_ka", List.of())).isEqualTo(List.of());
    }

    @Test
    void clearSchemeMappingsForSchemes_returnsUpdatedRowCount() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(2, 3);

        int updated = repository.clearSchemeMappingsForSchemes("tenant_ka", List.of(10, 11), 9);

        assertThat(updated).isEqualTo(5);
    }

    @Test
    void clearSchemeMappingsForSchemes_returnsZeroForEmptyInput() {
        assertThat(repository.clearSchemeMappingsForSchemes("tenant_ka", List.of(), 9)).isZero();
    }

    @Test
    void updateSchemeStatusesById_updatesProvidedStatuses() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        boolean updated = repository.updateSchemeStatusesById("tenant_ka", 11, 2, null, 9);

        assertThat(updated).isTrue();
    }

    @Test
    void guardsRejectInvalidSchema() {
        assertThatThrownBy(() -> repository.findAllSchemes("tenant-ka"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(jdbcTemplate, org.mockito.Mockito.never()).query(anyString(), any(RowMapper.class));
    }

    @Test
    void snapshotLookup_mapsRowsFromJdbcCallback() throws Exception {
        doAnswer(invocation -> {
                    RowCallbackHandler handler = invocation.getArgument(1);
                    ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                    when(rs.getString("state_scheme_id")).thenReturn("SS-1");
                    when(rs.getInt("id")).thenReturn(10);
                    when(rs.getString("centre_scheme_id")).thenReturn("C-1");
                    when(rs.getString("scheme_name")).thenReturn("Scheme");
                    when(rs.getObject("fhtc_count")).thenReturn(1);
                    when(rs.getObject("planned_fhtc")).thenReturn(2);
                    when(rs.getObject("house_hold_count")).thenReturn(3);
                    when(rs.getObject("latitude")).thenReturn(11.1d);
                    when(rs.getObject("longitude")).thenReturn(22.2d);
                    when(rs.getObject("work_status")).thenReturn(1);
                    when(rs.getObject("operating_status")).thenReturn(0);
                    handler.processRow(rs);
                    return null;
                }).when(jdbcTemplate).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));

        Map<String, SchemeDbRepository.SchemeSnapshot> snapshots =
                repository.findSchemeSnapshotsByStateSchemeIds("tenant_ka", List.of("SS-1"));

        assertThat(snapshots).containsKey("ss-1");
        assertThat(snapshots.get("ss-1").schemeName()).isEqualTo("Scheme");
    }

    // LENIENT-INGEST: auto-provisioned placeholder schemes must be excluded from registry counts so
    // they don't inflate scheme totals. The exclusion is applied only when the is_auto_provisioned
    // column is present (post-V31), otherwise it is a safe no-op.
    @Test
    @SuppressWarnings("unchecked")
    void countSchemesTotal_excludesAutoProvisionedWhenColumnPresent() {
        stubColumnExists(true);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(3L);

        long total = repository.countSchemesTotal("tenant_as");

        assertThat(total).isEqualTo(3L);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Long.class));
        assertThat(sqlCaptor.getValue()).contains("is_auto_provisioned = FALSE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void countSchemesTotal_countsAllWhenColumnAbsent() {
        stubColumnExists(false);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class))).thenReturn(9L);

        long total = repository.countSchemesTotal("tenant_ka");

        assertThat(total).isEqualTo(9L);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Long.class));
        assertThat(sqlCaptor.getValue()).doesNotContain("is_auto_provisioned");
    }

    @Test
    @SuppressWarnings("unchecked")
    void countSchemes_excludesAutoProvisionedWhenColumnPresent() {
        stubColumnExists(true);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);

        long total = repository.countSchemes("tenant_as", null, null, null, List.of(), List.of());

        assertThat(total).isEqualTo(2L);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Long.class), any(Object[].class));
        assertThat(sqlCaptor.getValue()).contains("is_auto_provisioned = FALSE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void countSchemes_rendersOneInClausePerStatusFilter() {
        stubColumnExists(false);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);

        repository.countSchemes("tenant_as", null, null, null, List.of(1, 2, 4), List.of(0, 2));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Long.class), argsCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("work_status IN (?, ?, ?)")
                .contains("operating_status IN (?, ?)");
        assertThat(argsCaptor.getValue()).containsExactly(1, 2, 4, 0, 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void countSchemes_omitsStatusFiltersWhenCodeListsAreEmpty() {
        stubColumnExists(false);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(2L);

        repository.countSchemes("tenant_as", null, null, null, List.of(), null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Long.class), any(Object[].class));
        assertThat(sqlCaptor.getValue())
                .doesNotContain("work_status IN")
                .doesNotContain("operating_status IN");
    }

    @Test
    @SuppressWarnings("unchecked")
    void countSchemeMappings_rendersQualifiedInClausesPerStatusFilter() {
        // Department tables absent, so the count takes the two-join branch.
        when(jdbcTemplate.queryForObject(eq("SELECT to_regclass(?)"), eq(String.class), anyString()))
                .thenReturn(null, null);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(4L);

        repository.countSchemeMappings("tenant_as", null, List.of(2), List.of(1, 2), null, null);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Long.class), argsCaptor.capture());
        assertThat(sqlCaptor.getValue())
                .contains("sm.work_status IN (?)")
                .contains("sm.operating_status IN (?, ?)");
        assertThat(argsCaptor.getValue()).containsExactly(2, 1, 2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void listSchemes_excludesAutoProvisionedWhenColumnPresent() {
        // listSchemes probes columnExists and runs the main query through the same varargs overload,
        // so distinguish them by SQL content (the probe hits information_schema).
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> invocation.getArgument(0, String.class).contains("information_schema")
                        ? List.of(1)   // is_auto_provisioned column present
                        : List.of());

        repository.listSchemes("tenant_as", null, null, null, null, null, null, null, 0, 10);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).query(sqlCaptor.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sqlCaptor.getAllValues())
                .anyMatch(sql -> !sql.contains("information_schema") && sql.contains("is_auto_provisioned = FALSE"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void countSchemesByWorkStatus_excludesAutoProvisionedWhenColumnPresent() {
        stubColumnExists(true);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

        repository.countSchemesByWorkStatus("tenant_as");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class));
        assertThat(sqlCaptor.getValue()).contains("is_auto_provisioned = FALSE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void countSchemesByOperatingStatus_excludesAutoProvisionedWhenColumnPresent() {
        stubColumnExists(true);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class))).thenReturn(List.of());

        repository.countSchemesByOperatingStatus("tenant_as");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class));
        assertThat(sqlCaptor.getValue()).contains("is_auto_provisioned = FALSE");
    }

    // Makes SchemeDbRepository.columnExists("...","scheme_master_table","is_auto_provisioned") resolve
    // to the desired value by stubbing its information_schema lookup (a 3-arg query call).
    @SuppressWarnings("unchecked")
    private void stubColumnExists(boolean present) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any(), any()))
                .thenReturn(present ? List.of(1) : List.of());
    }
}
