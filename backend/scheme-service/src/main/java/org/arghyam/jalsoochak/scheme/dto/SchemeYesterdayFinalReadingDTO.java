package org.arghyam.jalsoochak.scheme.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SchemeYesterdayFinalReadingDTO {
    private Integer schemeId;
    private String schemeName;
    private BigDecimal yesterdayFinalReading;
}

