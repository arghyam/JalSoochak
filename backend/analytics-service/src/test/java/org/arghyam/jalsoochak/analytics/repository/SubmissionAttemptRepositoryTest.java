package org.arghyam.jalsoochak.analytics.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * REPORTED-METRIC bookkeeping: resolving a submitted (government) scheme id back to our internal
 * {@code dim_scheme} row, and persisting a rejected submission so the scheme still counts as having
 * reported that day.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SubmissionAttemptRepository")
class SubmissionAttemptRepositoryTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private SubmissionAttemptRepository repository;

    @BeforeEach
    void setUp() {
        repository = new SubmissionAttemptRepository(jdbcTemplate);
        lenient().when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());
    }

    private Object[] capturedQueryArgs() {
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).query(anyString(), any(RowMapper.class), args.capture());
        return args.getValue();
    }

    @ParameterizedTest(name = "state=\"{0}\" centre=\"{0}\" resolves to nothing")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "not-a-number", "12a"})
    void resolveSchemeIsEmptyWhenNeitherSubmittedIdIsANumber(String value) {
        assertThat(repository.resolveScheme(17, value, value)).isEmpty();

        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void resolveSchemeReturnsTheMatchedSchemeAndTenant() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new int[]{101, 17}));

        assertThat(repository.resolveScheme(17, "5001", "6001"))
                .hasValueSatisfying(row -> assertThat(row).containsExactly(101, 17));
    }

    @Test
    void resolveSchemeIsEmptyWhenNoSchemeMatches() {
        assertThat(repository.resolveScheme(17, "5001", "6001")).isEmpty();
    }

    @Test
    void resolveSchemePassesBothParsedIdsAndThePreferredTenantAsBindParameters() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new int[]{101, 17}));

        repository.resolveScheme(17, " 5001 ", "6001");

        assertThat(capturedQueryArgs()).containsExactly(5001, 5001, 6001, 6001, 17, 17, 5001, 5001);
    }

    @Test
    void resolveSchemeWorksFromTheStateIdAlone() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new int[]{101, 17}));

        assertThat(repository.resolveScheme(17, "5001", null)).isPresent();
        assertThat(capturedQueryArgs()).containsExactly(5001, 5001, null, null, 17, 17, 5001, 5001);
    }

    @Test
    void resolveSchemeWorksFromTheCentreIdAlone() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new int[]{101, 17}));

        assertThat(repository.resolveScheme(17, null, "6001")).isPresent();
        assertThat(capturedQueryArgs()).containsExactly(null, null, 6001, 6001, 17, 17, null, null);
    }

    @Test
    void resolveSchemeToleratesAnAbsentPreferredTenant() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new int[]{101, 17}));

        assertThat(repository.resolveScheme(null, "5001", null)).isPresent();
        assertThat(capturedQueryArgs()).contains((Object) null);
    }

    @Test
    void resolveSchemePrefersTheEventsOwnTenantAndThenTheStateIdMatch() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(new int[]{101, 17}));

        repository.resolveScheme(17, "5001", "6001");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(sql.capture(), any(RowMapper.class), any(Object[].class));
        assertThat(sql.getValue())
                .contains("ORDER BY (CASE WHEN ? IS NOT NULL AND tenant_id = ? THEN 0 ELSE 1 END)")
                .contains("state_scheme_id = ? THEN 0 ELSE 1 END")
                .contains("LIMIT 1");
    }

    @Test
    void insertRecordsTheRejectedSubmission() {
        LocalDateTime attemptedAt = LocalDateTime.of(2026, 3, 1, 6, 30);

        repository.insert(17, 101, "5001", "6001", "hash", "validation failed", attemptedAt);

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertThat(args.getValue())
                .containsExactly(17, 101, "5001", "6001", "hash", "validation failed", attemptedAt);
    }

    @Test
    void insertTargetsTheSubmissionAttemptTable() {
        repository.insert(17, 101, "5001", "6001", "hash", "reason", LocalDateTime.now());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("INSERT INTO analytics_schema.submission_attempt_table");
    }

    @Test
    void insertAcceptsAnUnresolvedSchemeAndTenant() {
        repository.insert(null, null, "5001", null, null, "unresolved", LocalDateTime.now());

        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), args.capture());
        assertThat(args.getValue()[0]).isNull();
        assertThat(args.getValue()[1]).isNull();
    }
}
