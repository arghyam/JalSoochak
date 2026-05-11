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
}
