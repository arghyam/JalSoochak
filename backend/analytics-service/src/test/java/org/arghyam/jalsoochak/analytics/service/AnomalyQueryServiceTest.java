package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.repository.AnomalyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnomalyQueryServiceTest {

    @Mock
    private AnomalyRepository anomalyRepository;

    @InjectMocks
    private AnomalyQueryService anomalyQueryService;

    @Test
    void getAnomaliesForUserSchemes_passesEmptyStringFiltersAndPageableToRepository() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 15);
        Pageable pageable = PageRequest.of(0, 5);

        when(anomalyRepository.findAnomaliesForMappedUserSchemesInRange(
                eq(10),
                eq(42),
                eq(start.atStartOfDay().atOffset(ZoneOffset.UTC)),
                eq(end.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)),
                eq(""),
                eq(""),
                eq(-1),
                eq(pageable)))
                .thenReturn(org.springframework.data.domain.Page.empty(pageable));

        anomalyQueryService.getAnomaliesForUserSchemes(10, 42, start, end, null, null, null, pageable);

        verify(anomalyRepository).findAnomaliesForMappedUserSchemesInRange(
                eq(10),
                eq(42),
                eq(start.atStartOfDay().atOffset(ZoneOffset.UTC)),
                eq(end.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC)),
                eq(""),
                eq(""),
                eq(-1),
                eq(pageable));
    }

    @Test
    void getAnomaliesForUserSchemes_trimsAnomalyTypeAndSchemeName() {
        Pageable pageable = PageRequest.of(0, 10);
        when(anomalyRepository.findAnomaliesForMappedUserSchemesInRange(
                any(), any(), any(), any(), eq("3"), eq("My Scheme"), eq(2), eq(pageable)))
                .thenReturn(org.springframework.data.domain.Page.empty(pageable));

        anomalyQueryService.getAnomaliesForUserSchemes(
                1, 1, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28), "  3  ", "  My Scheme  ", 2, pageable);

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> schemeCaptor = ArgumentCaptor.forClass(String.class);
        verify(anomalyRepository).findAnomaliesForMappedUserSchemesInRange(
                any(), any(), any(), any(), typeCaptor.capture(), schemeCaptor.capture(), eq(2), eq(pageable));
        assertThat(typeCaptor.getValue()).isEqualTo("3");
        assertThat(schemeCaptor.getValue()).isEqualTo("My Scheme");
    }
}
