package org.arghyam.jalsoochak.analytics.service;

import org.arghyam.jalsoochak.analytics.dto.response.OperatorAttendanceDayItemDto;
import org.arghyam.jalsoochak.analytics.repository.DimOperatorAttendanceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperatorAttendanceQueryServiceTest {

    private static final UUID USER = UUID.fromString("a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11");

    @Mock
    private DimOperatorAttendanceRepository dimOperatorAttendanceRepository;

    @InjectMocks
    private OperatorAttendanceQueryService operatorAttendanceQueryService;

    @Test
    void getDayWiseAttendance_fillsMissingDaysWithAbsent() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 3);
        when(dimOperatorAttendanceRepository.findDayWiseByUserUuidAndDateRange(USER, start, end))
                .thenReturn(List.of(
                        OperatorAttendanceDayItemDto.builder()
                                .date(LocalDate.of(2026, 1, 2))
                                .attendance(1)
                                .build()
                ));

        List<OperatorAttendanceDayItemDto> result =
                operatorAttendanceQueryService.getDayWiseAttendance(USER, start, end);

        assertThat(result).hasSize(3);
        assertThat(result.get(0)).isEqualTo(OperatorAttendanceDayItemDto.builder()
                .date(LocalDate.of(2026, 1, 1)).attendance(0).build());
        assertThat(result.get(1)).isEqualTo(OperatorAttendanceDayItemDto.builder()
                .date(LocalDate.of(2026, 1, 2)).attendance(1).build());
        assertThat(result.get(2)).isEqualTo(OperatorAttendanceDayItemDto.builder()
                .date(LocalDate.of(2026, 1, 3)).attendance(0).build());
    }

    @Test
    void getDayWiseAttendance_multipleRowsSameDay_usesMaxAttendance() {
        LocalDate d = LocalDate.of(2026, 2, 10);
        when(dimOperatorAttendanceRepository.findDayWiseByUserUuidAndDateRange(USER, d, d))
                .thenReturn(List.of(
                        OperatorAttendanceDayItemDto.builder().date(d).attendance(0).build(),
                        OperatorAttendanceDayItemDto.builder().date(d).attendance(1).build()
                ));

        List<OperatorAttendanceDayItemDto> result =
                operatorAttendanceQueryService.getDayWiseAttendance(USER, d, d);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getAttendance()).isEqualTo(1);
    }

    @Test
    void getDayWiseAttendance_whenStartAfterEnd_throwsIllegalArgumentException() {
        LocalDate start = LocalDate.of(2026, 2, 1);
        LocalDate end = LocalDate.of(2026, 1, 1);

        assertThatThrownBy(() -> operatorAttendanceQueryService.getDayWiseAttendance(USER, start, end))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("start_date");
    }

    @Test
    void getDayWiseAttendance_sameStartAndEnd_emptyRepo_returnsSingleAbsentDay() {
        LocalDate d = LocalDate.of(2026, 3, 15);
        when(dimOperatorAttendanceRepository.findDayWiseByUserUuidAndDateRange(USER, d, d))
                .thenReturn(List.of());

        List<OperatorAttendanceDayItemDto> result =
                operatorAttendanceQueryService.getDayWiseAttendance(USER, d, d);

        assertThat(result).containsExactly(
                OperatorAttendanceDayItemDto.builder().date(d).attendance(0).build());
        verify(dimOperatorAttendanceRepository).findDayWiseByUserUuidAndDateRange(eq(USER), eq(d), eq(d));
    }
}
