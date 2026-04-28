package org.arghyam.jalsoochak.telemetry.dto.requests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AssamReadingRequest {

    @NotBlank
    @JsonProperty("reading_url")
    private String readingUrl;

    @DecimalMin(value = "0.0", inclusive = false)
    @JsonProperty("confirmed_reading")
    private BigDecimal confirmedReading;

    @NotBlank
    @JsonProperty("state_scheme_id")
    private String stateSchemeId;

    @NotBlank
    @JsonProperty("centre_scheme_id")
    private String centreSchemeId;

    @NotBlank
    @JsonProperty("phone_number")
    private String phoneNumber;

    @NotNull
    @JsonProperty("reading_date_time")
    private OffsetDateTime readingDateTime;

    @Valid
    private Geolocation geolocation;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Geolocation {
        private String type;
        private List<BigDecimal> coordinates;
    }
}
