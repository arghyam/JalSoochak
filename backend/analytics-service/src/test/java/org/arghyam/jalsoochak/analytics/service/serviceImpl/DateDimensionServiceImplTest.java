package org.arghyam.jalsoochak.analytics.service.serviceImpl;

import org.arghyam.jalsoochak.analytics.entity.DimDate;
import org.arghyam.jalsoochak.analytics.repository.DimDateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DateDimensionServiceImplTest {

    @Mock
    private DimDateRepository dimDateRepository;

    @InjectMocks
    private DateDimensionServiceImpl service;

    @Test
    void ensureDateExists_whenMissing_savesBuiltDimDate() {
        LocalDate date = LocalDate.of(2026, 1, 1);
        when(dimDateRepository.existsById(20260101)).thenReturn(false);

        service.ensureDateExists(date);

        ArgumentCaptor<DimDate> captor = ArgumentCaptor.forClass(DimDate.class);
        verify(dimDateRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getDateKey()).isEqualTo(20260101);
        assertThat(captor.getValue().getFullDate()).isEqualTo(date);
        assertThat(captor.getValue().getFiscalYear()).isEqualTo(2025);
    }

    @Test
    void ensureDateExists_setsSundayToSaturdayWeekBounds() {
        // 2026-01-01 is a Thursday; 2026-01-04 is a Sunday and starts its own week.
        when(dimDateRepository.existsById(20260101)).thenReturn(false);
        when(dimDateRepository.existsById(20260104)).thenReturn(false);

        service.ensureDateExists(LocalDate.of(2026, 1, 1));
        service.ensureDateExists(LocalDate.of(2026, 1, 4));

        ArgumentCaptor<DimDate> captor = ArgumentCaptor.forClass(DimDate.class);
        verify(dimDateRepository, times(2)).save(captor.capture());
        DimDate thursday = captor.getAllValues().get(0);
        assertThat(thursday.getWeekStartDate()).isEqualTo(LocalDate.of(2025, 12, 28));
        assertThat(thursday.getWeekEndDate()).isEqualTo(LocalDate.of(2026, 1, 3));
        DimDate sunday = captor.getAllValues().get(1);
        assertThat(sunday.getWeekStartDate()).isEqualTo(LocalDate.of(2026, 1, 4));
        assertThat(sunday.getWeekEndDate()).isEqualTo(LocalDate.of(2026, 1, 10));
    }

    @Test
    void ensureDateExists_whenAlreadyPresent_doesNotSave() {
        when(dimDateRepository.existsById(20260101)).thenReturn(true);

        service.ensureDateExists(LocalDate.of(2026, 1, 1));

        verify(dimDateRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void getDateKey_returnsComputedDateKey() {
        assertThat(service.getDateKey(LocalDate.of(2026, 12, 31))).isEqualTo(20261231);
    }

    @Test
    void populateDateRange_savesOnlyMissingDates() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 3);
        when(dimDateRepository.existsById(20260101)).thenReturn(true);
        when(dimDateRepository.existsById(20260102)).thenReturn(false);
        when(dimDateRepository.existsById(20260103)).thenReturn(false);

        service.populateDateRange(start, end);

        verify(dimDateRepository, times(2)).save(org.mockito.ArgumentMatchers.any(DimDate.class));
    }
}
