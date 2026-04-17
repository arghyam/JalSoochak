package org.arghyam.jalsoochak.telemetry.repository;

import org.arghyam.jalsoochak.telemetry.service.PiiEncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelemetryTenantRepositoryCacheTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private PiiEncryptionService piiEncryptionService;

    private TelemetryTenantRepository repository;

    @BeforeEach
    void setUp() {
        repository = new TelemetryTenantRepository(jdbcTemplate, piiEncryptionService);
        ReflectionTestUtils.setField(repository, "metadataCacheEnabled", true);
        ReflectionTestUtils.setField(repository, "tenantSchemaListCacheTtlMs", 300_000L);
        ReflectionTestUtils.setField(repository, "columnExistsCacheTtlMs", 600_000L);
    }

    @Test
    void findUserLanguageIdUsesColumnExistenceCache() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class), eq("tenant_up"), eq("user_table"), eq("language_id")))
                .thenReturn(Boolean.FALSE);

        Optional<Integer> first = repository.findUserLanguageId("tenant_up", 11L);
        Optional<Integer> second = repository.findUserLanguageId("tenant_up", 11L);

        assertTrue(first.isEmpty());
        assertTrue(second.isEmpty());
        verify(jdbcTemplate, times(1))
                .queryForObject(any(String.class), eq(Boolean.class), eq("tenant_up"), eq("user_table"), eq("language_id"));
    }

    @Test
    void findOperatorByPhoneAcrossTenantsUsesSchemaListCache() {
        when(jdbcTemplate.query(contains("FROM pg_namespace"), any(RowMapper.class)))
                .thenReturn(List.of());

        Optional<TelemetryOperatorWithSchema> first = repository.findOperatorByPhoneAcrossTenants("919999999999");
        Optional<TelemetryOperatorWithSchema> second = repository.findOperatorByPhoneAcrossTenants("919999999999");

        assertTrue(first.isEmpty());
        assertTrue(second.isEmpty());
        verify(jdbcTemplate, times(1)).query(contains("FROM pg_namespace"), any(RowMapper.class));
    }

    @Test
    void invalidateMetadataCachesForcesFreshMetadataQueries() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Boolean.class), eq("tenant_up"), eq("user_table"), eq("language_id")))
                .thenReturn(Boolean.FALSE);

        repository.findUserLanguageId("tenant_up", 11L);
        repository.invalidateMetadataCaches();
        repository.findUserLanguageId("tenant_up", 11L);

        verify(jdbcTemplate, times(2))
                .queryForObject(any(String.class), eq(Boolean.class), eq("tenant_up"), eq("user_table"), eq("language_id"));
        assertEquals(Optional.empty(), repository.findUserLanguageId("tenant_up", 11L));
    }
}
