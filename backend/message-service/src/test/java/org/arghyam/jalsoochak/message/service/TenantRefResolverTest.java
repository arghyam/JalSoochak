package org.arghyam.jalsoochak.message.service;

import org.arghyam.jalsoochak.message.dto.TenantRef;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantRefResolver")
class TenantRefResolverTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private TenantRefResolver resolver;

    @Test
    @DisplayName("an event with no tenant resolves to NONE without touching the database")
    void noTenant() {
        assertThat(resolver.resolve(null, null)).isEqualTo(TenantRef.NONE);
        assertThat(resolver.resolve(null, "  ")).isEqualTo(TenantRef.NONE);
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("an event carrying both halves is used as-is, without a lookup")
    void bothHalvesPresent() {
        TenantRef ref = resolver.resolve(1, "mp");

        assertThat(ref).isEqualTo(new TenantRef(1, "MP"));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("fills in the state code when the event carries only the id")
    void fillsCodeFromId() {
        when(jdbcTemplate.query(contains("SELECT state_code"), any(RowMapper.class), eq(1)))
                .thenReturn(List.of("MP"));

        assertThat(resolver.resolve(1, null)).isEqualTo(new TenantRef(1, "MP"));
    }

    @Test
    @DisplayName("fills in the id when the event carries only the state code")
    void fillsIdFromCode() {
        when(jdbcTemplate.query(contains("SELECT id"), any(RowMapper.class), eq("MP")))
                .thenReturn(List.of(7));

        assertThat(resolver.resolve(null, "mp")).isEqualTo(new TenantRef(7, "MP"));
    }

    @Test
    @DisplayName("a pair carried on the event is not cached, so it cannot poison a later lookup")
    void bothHalvesPresentIsNotCached() {
        // A producer emitting a mismatched pair — a replayed event from before a state-code change,
        // a hand-published test message — would otherwise pin id 1 to "XX" and "XX" to id 1 for the
        // life of the instance, and every later half-populated event for either half would resolve
        // to the wrong tenant and pick the wrong tenant's provider account.
        when(jdbcTemplate.query(contains("SELECT state_code"), any(RowMapper.class), eq(1)))
                .thenReturn(List.of("MP"));

        assertThat(resolver.resolve(1, "XX")).isEqualTo(new TenantRef(1, "XX"));

        assertThat(resolver.resolve(1, null)).isEqualTo(new TenantRef(1, "MP"));
        assertThat(resolver.resolve(null, "XX")).isEqualTo(new TenantRef(null, "XX"));
    }

    @Test
    @DisplayName("caches a resolved mapping, so a second event queries nothing")
    void cachesResolvedMapping() {
        when(jdbcTemplate.query(contains("SELECT state_code"), any(RowMapper.class), eq(1)))
                .thenReturn(List.of("MP"));

        assertThat(resolver.resolve(1, null)).isEqualTo(new TenantRef(1, "MP"));
        assertThat(resolver.resolve(1, null)).isEqualTo(new TenantRef(1, "MP"));

        verify(jdbcTemplate, times(1)).query(anyString(), any(RowMapper.class), any(Object.class));
    }

    @Test
    @DisplayName("caches both directions, so the reverse lookup is free")
    void cachesBothDirections() {
        when(jdbcTemplate.query(contains("SELECT state_code"), any(RowMapper.class), eq(1)))
                .thenReturn(List.of("MP"));

        resolver.resolve(1, null);

        assertThat(resolver.resolve(null, "MP")).isEqualTo(new TenantRef(1, "MP"));
        verify(jdbcTemplate, never()).query(contains("SELECT id"), any(RowMapper.class), any(Object.class));
    }

    @Test
    @DisplayName("an unknown tenant returns the half that was carried, and is not cached")
    void unknownTenantIsNotCached() {
        when(jdbcTemplate.query(contains("SELECT state_code"), any(RowMapper.class), eq(99)))
                .thenReturn(List.of());

        assertThat(resolver.resolve(99, null)).isEqualTo(new TenantRef(99, null));
        assertThat(resolver.resolve(99, null)).isEqualTo(new TenantRef(99, null));

        // A tenant created after startup must resolve on its next event, so misses are re-queried
        verify(jdbcTemplate, times(2)).query(anyString(), any(RowMapper.class), any(Object.class));
    }

    @Test
    @DisplayName("a database failure never propagates — the partial reference is returned")
    void databaseFailureIsSwallowed() {
        when(jdbcTemplate.query(contains("SELECT id"), any(RowMapper.class), eq("MP")))
                .thenThrow(new QueryTimeoutException("pool exhausted"));

        assertThat(resolver.resolve(null, "MP")).isEqualTo(new TenantRef(null, "MP"));
    }
}
