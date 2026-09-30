package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.arghyam.jalsoochak.analytics.dto.response.HourlySubmissionActivityResponse;
import org.arghyam.jalsoochak.analytics.repository.HourlySubmissionActivityRepository;
import org.arghyam.jalsoochak.analytics.repository.HourlySubmissionActivityRepository.HourlyActivityRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubmissionActivityServiceImplTest {

    private static final int TENANT = 7;
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 1);

    @Mock
    private HourlySubmissionActivityRepository repository;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;

    private SubmissionActivityServiceImpl service;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        service = new SubmissionActivityServiceImpl(repository, redisTemplate, objectMapper);
        ReflectionTestUtils.setField(service, "cacheTtlHours", 1L);
    }

    /** Cache miss: opsForValue().get(...) returns null so the repo is queried. */
    private void stubCacheMiss() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(valueOperations.get(anyString())).thenReturn(null);
    }

    @Test
    void tenantWide_whenNoRegion_queriesTenantHourly_andMapsBuckets() {
        stubCacheMiss();
        when(repository.getTenantHourly(TENANT, START, END)).thenReturn(List.of(
                new HourlyActivityRow(LocalDateTime.of(2026, 1, 1, 9, 0), 3L, 2),
                new HourlyActivityRow(LocalDateTime.of(2026, 1, 1, 10, 0), 1L, 1)));

        HourlySubmissionActivityResponse response =
                service.getHourlySubmissionActivity(TENANT, null, null, START, END);

        assertThat(response.getTenantId()).isEqualTo(TENANT);
        assertThat(response.getLgdId()).isNull();
        assertThat(response.getDepartmentId()).isNull();
        assertThat(response.getHourlyActivity()).hasSize(2);
        HourlySubmissionActivityResponse.HourlyBucket first = response.getHourlyActivity().get(0);
        assertThat(first.getHourStart()).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 0));
        assertThat(first.getSubmissionCount()).isEqualTo(3L);
        assertThat(first.getDistinctSchemeCount()).isEqualTo(2);
        verify(repository).getTenantHourly(TENANT, START, END);
    }

    @Test
    void lgdScope_routesToRegionHourly_withLgdHierarchy() {
        stubCacheMiss();
        when(repository.getRegionHourly(TENANT, "LGD", 101, START, END)).thenReturn(List.of(
                new HourlyActivityRow(LocalDateTime.of(2026, 1, 1, 9, 0), 2L, 1)));

        HourlySubmissionActivityResponse response =
                service.getHourlySubmissionActivity(TENANT, 101, null, START, END);

        assertThat(response.getLgdId()).isEqualTo(101);
        assertThat(response.getHourlyActivity()).hasSize(1);
        verify(repository).getRegionHourly(TENANT, "LGD", 101, START, END);
    }

    @Test
    void departmentScope_routesToRegionHourly_withDeptHierarchy() {
        stubCacheMiss();
        when(repository.getRegionHourly(TENANT, "DEPT", 201, START, END)).thenReturn(List.of());

        HourlySubmissionActivityResponse response =
                service.getHourlySubmissionActivity(TENANT, null, 201, START, END);

        assertThat(response.getDepartmentId()).isEqualTo(201);
        assertThat(response.getHourlyActivity()).isEmpty();
        verify(repository).getRegionHourly(TENANT, "DEPT", 201, START, END);
    }

    @Test
    void bothRegionIds_throws_withoutTouchingRepo() {
        assertThatThrownBy(() -> service.getHourlySubmissionActivity(TENANT, 101, 201, START, END))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void invalidTenant_throws() {
        assertThatThrownBy(() -> service.getHourlySubmissionActivity(0, null, null, START, END))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void endBeforeStart_throws() {
        assertThatThrownBy(() -> service.getHourlySubmissionActivity(
                TENANT, null, null, LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }
}
