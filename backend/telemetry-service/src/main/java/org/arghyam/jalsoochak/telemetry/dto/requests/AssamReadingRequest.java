package org.arghyam.jalsoochak.telemetry.dto.requests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
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

    @JsonProperty("reading_url")
    private String readingUrl;

    @DecimalMin(value = "0.0", inclusive = false)
    @JsonAlias("confirmed_readings")
    @JsonProperty("confirmed_reading")
    private BigDecimal confirmedReading;

    @JsonProperty("state_scheme_id")
    private String stateSchemeId;

    @JsonAlias({"center_scheme_id", "centerSchemeId", "centreSchemeId"})
    @JsonProperty("centre_scheme_id")
    private String centreSchemeId;

    @NotBlank
    @JsonProperty("phone_number")
    private String phoneNumber;

    @JsonProperty("reading_date_time")
    private OffsetDateTime readingDateTime;

    @Valid
    private Geolocation geolocation;

    @AssertTrue(message = "Either stateSchemeId or centreSchemeId must be provided")
    private boolean isSchemeIdPresent() {
        return (stateSchemeId != null && !stateSchemeId.isBlank())
                || (centreSchemeId != null && !centreSchemeId.isBlank());
    }

    @AssertTrue(message = "Either readingUrl or confirmedReading must be provided")
    private boolean isReadingPresent() {
        return (readingUrl != null && !readingUrl.isBlank()) || confirmedReading != null;
    }

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
