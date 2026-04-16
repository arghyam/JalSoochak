package org.arghyam.jalsoochak.telemetry.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantConfigRepositoryCacheTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private TenantConfigRepository repository;

    @BeforeEach
    void setUp() {
        repository = new TenantConfigRepository(jdbcTemplate);
        ReflectionTestUtils.setField(repository, "tenantConfigCacheEnabled", true);
        ReflectionTestUtils.setField(repository, "tenantConfigCacheTtlMs", 120_000L);
    }

    @Test
    void findConfigValueUsesTenantConfigCache() {
        when(jdbcTemplate.query(contains("SELECT config_key, config_value"), any(RowMapper.class), eq(7)))
                .thenReturn(List.of(Map.entry("intro_message", "Welcome")));

        Optional<String> first = repository.findConfigValue(7, "intro_message");
        Optional<String> second = repository.findConfigValue(7, "intro_message");

        assertEquals(Optional.of("Welcome"), first);
        assertEquals(Optional.of("Welcome"), second);
        verify(jdbcTemplate, times(1)).query(contains("SELECT config_key, config_value"), any(RowMapper.class), eq(7));
    }

    @Test
    void findChannelOptionsPrefersLocalizedThenFallsBackToGeneric() {
        when(jdbcTemplate.query(contains("SELECT config_key, config_value"), any(RowMapper.class), eq(9)))
                .thenReturn(List.of(
                        Map.entry("channel_2_en", "Localized 2"),
                        Map.entry("channel_1_en", "Localized 1"),
                        Map.entry("channel_2", "Generic 2"),
                        Map.entry("channel_1", "Generic 1")
                ));

        List<String> localized = repository.findChannelOptions(9, "en");
        List<String> fallback = repository.findChannelOptions(9, "hi");

        assertEquals(List.of("Localized 1", "Localized 2"), localized);
        assertEquals(List.of("Generic 1", "Generic 2"), fallback);
        verify(jdbcTemplate, times(1)).query(contains("SELECT config_key, config_value"), any(RowMapper.class), eq(9));
    }

    @Test
    void invalidateTenantConfigCacheForcesReload() {
        when(jdbcTemplate.query(contains("SELECT config_key, config_value"), any(RowMapper.class), eq(11)))
                .thenReturn(List.of(Map.entry("closing_message", "Done")));

        repository.findConfigValue(11, "closing_message");
        repository.invalidateTenantConfigCache(11);
        repository.findConfigValue(11, "closing_message");

        verify(jdbcTemplate, times(2)).query(contains("SELECT config_key, config_value"), any(RowMapper.class), eq(11));
    }
}
