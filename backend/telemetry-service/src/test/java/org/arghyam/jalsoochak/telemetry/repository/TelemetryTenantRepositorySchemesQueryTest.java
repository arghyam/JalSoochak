package org.arghyam.jalsoochak.telemetry.repository;

import org.arghyam.jalsoochak.telemetry.service.PiiEncryptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    // LENIENT-INGEST: placeholder schemes must be created inactive so they never inflate active-scheme
    // dashboards, and flagged is_auto_provisioned so they stay discoverable for reconciliation.
    @SuppressWarnings("unchecked")
    @Test
    void getOrCreatePlaceholderSchemeInsertsInactiveAutoProvisionedScheme() {
        TelemetryTenantRepository repository = new TelemetryTenantRepository(jdbcTemplate, piiEncryptionService);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any())).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(anyString(), eq(Number.class), any(), any(), any())).thenReturn(55555L);

        Long id = repository.getOrCreatePlaceholderScheme("tenant_as", "99999999", "88888888");

        assertEquals(55555L, id);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForObject(sqlCaptor.capture(), eq(Number.class), any(), any(), any());
        String insertSql = sqlCaptor.getValue();
        assertTrue(insertSql.contains("is_auto_provisioned"), "placeholder must be flagged is_auto_provisioned");
        assertTrue(insertSql.contains("is_active"), "placeholder insert must set is_active explicitly");
        assertTrue(insertSql.contains("TRUE, FALSE"),
                "placeholder must be is_auto_provisioned=TRUE and is_active=FALSE");
    }

    // LENIENT-INGEST: when two concurrent unknown-scheme submissions race, the unique index (V31) makes
    // the second INSERT fail; getOrCreatePlaceholderScheme must recover by returning the winner's id
    // instead of propagating the constraint violation or fanning out duplicate placeholder schemes.
    @SuppressWarnings("unchecked")
    @Test
    void getOrCreatePlaceholderSchemeRecoversFromUniqueViolationRace() {
        TelemetryTenantRepository repository = new TelemetryTenantRepository(jdbcTemplate, piiEncryptionService);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(), any()))
                .thenReturn(List.of())        // first lookup: nothing yet
                .thenReturn(List.of(77L));    // re-lookup after the race: the winner's row
        when(jdbcTemplate.queryForObject(anyString(), eq(Number.class), any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        Long id = repository.getOrCreatePlaceholderScheme("tenant_as", "99999999", "88888888");

        assertEquals(77L, id);
    }

    // SCHEME-ID-MISMATCH: after a reading resolves on one id, the other submitted id is cross-checked
    // against the matched scheme. The UPDATE must trim the submitted ids, target real schemes only
    // (is_auto_provisioned = FALSE), record only the side that disagrees, and be guarded so it is a
    // no-op once the scheme already carries the same mismatching value.
    @Test
    void recordSchemeIdMismatchIfAnyIssuesGuardedUpdateWithBothSubmittedIds() {
        TelemetryTenantRepository repository = new TelemetryTenantRepository(jdbcTemplate, piiEncryptionService);

        repository.recordSchemeIdMismatchIfAny("tenant_as", 42L, " 30178236 ", "30244993");

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(sqlCaptor.capture(), argsCaptor.capture());

        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("UPDATE tenant_as.scheme_master_table"));
        assertTrue(sql.contains("is_auto_provisioned = FALSE"), "must never flag placeholder schemes");
        assertTrue(sql.contains("IS DISTINCT FROM"));
        assertTrue(sql.contains("id_mismatch_last_seen_at = NOW()"));

        // Submitted ids are trimmed; arg order: state,state,centre,centre,schemeId,state,state,centre,centre.
        assertArrayEquals(
                new Object[]{"30178236", "30178236", "30244993", "30244993",
                        42L, "30178236", "30178236", "30244993", "30244993"},
                argsCaptor.getValue());
    }

    // SCHEME-ID-MISMATCH: a single-id submission (or a missing scheme) has nothing to cross-check, so
    // it must never touch the master table.
    @Test
    void recordSchemeIdMismatchIfAnySkipsWhenAnIdOrSchemeIsMissing() {
        TelemetryTenantRepository repository = new TelemetryTenantRepository(jdbcTemplate, piiEncryptionService);

        repository.recordSchemeIdMismatchIfAny("tenant_as", 42L, "30178236", "   ");
        repository.recordSchemeIdMismatchIfAny("tenant_as", 42L, null, "30244993");
        repository.recordSchemeIdMismatchIfAny("tenant_as", null, "30178236", "30244993");

        verifyNoInteractions(jdbcTemplate);
    }
}
