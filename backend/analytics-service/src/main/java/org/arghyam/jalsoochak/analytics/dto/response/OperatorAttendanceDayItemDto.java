package org.arghyam.jalsoochak.analytics.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperatorAttendanceDayItemDto {

    private LocalDate date;
    /** 0 means absent (no row or explicit absent in source data). */
    private Integer attendance;
}
