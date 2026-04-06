package org.arghyam.jalsoochak.analytics.service;

import lombok.RequiredArgsConstructor;
import org.arghyam.jalsoochak.analytics.dto.response.OperatorAttendanceDayItemDto;
import org.arghyam.jalsoochak.analytics.repository.DimOperatorAttendanceRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OperatorAttendanceQueryService {

    private static final int ABSENT = 0;

    private final DimOperatorAttendanceRepository dimOperatorAttendanceRepository;

    public List<OperatorAttendanceDayItemDto> getDayWiseAttendance(UUID userUuid, LocalDate startDate, LocalDate endDate) {
        LocalDate safeEnd = (endDate != null) ? endDate : LocalDate.now();
        LocalDate safeStart = (startDate != null) ? startDate : safeEnd.minusDays(30);

        if (safeStart.isAfter(safeEnd)) {
            throw new IllegalArgumentException("start_date must be on or before end_date");
        }

        List<OperatorAttendanceDayItemDto> rows =
                dimOperatorAttendanceRepository.findDayWiseByUserUuidAndDateRange(userUuid, safeStart, safeEnd);

        Map<LocalDate, Integer> bestAttendanceByDate = new HashMap<>();
        for (OperatorAttendanceDayItemDto row : rows) {
            LocalDate d = row.getDate();
            int v = row.getAttendance() != null ? row.getAttendance() : ABSENT;
            bestAttendanceByDate.merge(d, v, Math::max);
        }

        List<OperatorAttendanceDayItemDto> out = new ArrayList<>();
        for (LocalDate d = safeStart; !d.isAfter(safeEnd); d = d.plusDays(1)) {
            int attendance = bestAttendanceByDate.getOrDefault(d, ABSENT);
            out.add(OperatorAttendanceDayItemDto.builder()
                    .date(d)
                    .attendance(attendance)
                    .build());
        }
        return out;
    }
}
