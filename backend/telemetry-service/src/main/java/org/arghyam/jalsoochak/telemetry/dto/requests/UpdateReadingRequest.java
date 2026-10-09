package org.arghyam.jalsoochak.telemetry.dto.requests;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
    @JsonAlias("correlationId")
    @JsonProperty("correlation_id")
    private String correlationId;

    @JsonAlias("phoneNumber")
    @JsonProperty("phone_number")
    private String phoneNumber;

    @JsonAlias({"imageId", "image_id"})
    @JsonProperty("reading_url")
    private String imageId;

    @JsonAlias("confirmedReading")
    @JsonProperty("confirmed_reading")
    private BigDecimal confirmedReading;

    /**
     * Optional unit of {@link #confirmedReading}, as a UCUM code accepted by the corrected reading's
     * channel, case-insensitive. Null or blank means the unit the corrected reading is stored in: kVAh
     * for a kVAh reading, the channel's standard unit otherwise. Kept as raw text and checked once the
     * reading, and so its channel, has been found.
     */
    @JsonAlias("readingUnit")
    @JsonProperty("reading_unit")
    private String readingUnit;
}
