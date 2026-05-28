package org.arghyam.jalsoochak.telemetry.dto.requests;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateYesterdayFinalReadingBySchemeRequest {
    @NotNull
    @DecimalMin(value = "0", inclusive = false)
    private BigDecimal reading;
}

