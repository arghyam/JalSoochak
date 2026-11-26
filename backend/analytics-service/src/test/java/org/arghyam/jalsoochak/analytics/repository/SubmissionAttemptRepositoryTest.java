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
    @ValueSource(strings = {"   ", "\t"})
    void resolveSchemeIsEmptyWhenNeitherIdIsSubmitted(String value) {
        assertThat(repository.resolveScheme(17, value, value)).isEmpty();

        verify(jdbcTemplate, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void resolveSchemeIsEmptyWhenTheOnlyCentreIdIsNotANumber() {
        assertThat(repository.resolveScheme(17, null, "12a")).isEmpty();

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
    void resolveSchemeBindsTheTrimmedStateIdAsText_theParsedCentreId_andThePreferredTenant_onceEach() {
        repository.resolveScheme(17, " TW20826 ", " 6001 ");

        assertThat(capturedQueryArgs()).containsExactly("TW20826", 6001, 17);
    }

    @Test
    void resolveSchemeBindsANumericStateIdAsText() {
        repository.resolveScheme(17, "5001", null);

        assertThat(capturedQueryArgs()).containsExactly("5001", null, 17);
    }

    @Test
    void resolveSchemeWorksFromTheStateIdAlone_whenTheCentreIdIsNotANumber() {
        repository.resolveScheme(17, "TW20826", "12a");

        assertThat(capturedQueryArgs()).containsExactly("TW20826", null, 17);
    }

    @Test
    void resolveSchemeWorksFromTheCentreIdAlone() {
        repository.resolveScheme(17, "  ", "6001");

        assertThat(capturedQueryArgs()).containsExactly(null, 6001, 17);
    }

    @Test
    void resolveSchemeToleratesAnAbsentPreferredTenant() {
        repository.resolveScheme(null, "5001", null);

        assertThat(capturedQueryArgs()).containsExactly("5001", null, null);
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
