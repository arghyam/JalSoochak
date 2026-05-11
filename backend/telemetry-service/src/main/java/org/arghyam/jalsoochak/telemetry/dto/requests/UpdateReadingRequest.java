package org.arghyam.jalsoochak.telemetry.dto.requests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class UpdateReadingRequest {
    @NotBlank(message = "correlationId must be provided")
    private String correlationId;

    private String imageId;
    private BigDecimal confirmedReading;

    @AssertTrue(message = "Either imageId or confirmedReading must be provided")
    public boolean isAnyUpdatableFieldPresent() {
        return (imageId != null && !imageId.isBlank()) || confirmedReading != null;
    }
}
