package org.arghyam.jalsoochak.telemetry.repository;

import org.arghyam.jalsoochak.telemetry.service.PiiEncryptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelemetryTenantRepositorySchemesQueryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PiiEncryptionService piiEncryptionService;

    @Test
    void findSchemesForUserEnforcesUserMappingAndNonDeletedRecords() {
        TelemetryTenantRepository repository = new TelemetryTenantRepository(jdbcTemplate, piiEncryptionService);
        when(jdbcTemplate.query(any(String.class), any(RowMapper.class), eq(21L))).thenReturn(List.of());

        repository.findSchemesForUser("tenant_up", 21L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq(21L));
        String sql = sqlCaptor.getValue();

        assertTrue(sql.contains("WHERE usm.user_id = ?"));
        assertTrue(sql.contains("AND usm.deleted_at IS NULL"));
        assertTrue(sql.contains("AND sm.deleted_at IS NULL"));
        assertTrue(sql.contains("GROUP BY usm.scheme_id, sm.scheme_name"));
        assertTrue(sql.contains("ORDER BY mapping_order"));
    }

    @Test
    void isOperatorMappedToSchemeIgnoresSoftDeletedMappings() {
        TelemetryTenantRepository repository = new TelemetryTenantRepository(jdbcTemplate, piiEncryptionService);
        when(jdbcTemplate.queryForObject(anyString(), eq(Boolean.class), anyLong(), anyLong())).thenReturn(Boolean.FALSE);

        repository.isOperatorMappedToScheme("tenant_up", 21L, 33L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Boolean.class), eq(21L), eq(33L));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("AND deleted_at IS NULL"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void roleBasedUserLookupIgnoresSoftDeletedMappingsAndUsers() {
        TelemetryTenantRepository repository = new TelemetryTenantRepository(jdbcTemplate, piiEncryptionService);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());

        repository.findSectionOfficerUserIdsForScheme("tenant_up", 33L);

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sqlCaptor.capture(), any(RowMapper.class), eq(33L), eq("SECTION_OFFICER"));
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("AND usm.deleted_at IS NULL"));
        assertTrue(sql.contains("AND u.deleted_at IS NULL"));
    }
}
