package org.arghyam.jalsoochak.telemetry.dto.requests;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import org.arghyam.jalsoochak.telemetry.validation.ValidReadingUrl;
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
public class CanonicalReadingRequest {

    @ValidReadingUrl
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

    // PHONE-OPTIONAL: submissions may omit the phone number entirely. The operator is then inferred from
    // the resolved scheme (first mapped pump operator, else the tenant sentinel) — see
    // MeterImageWorkflowService#resolveOperatorFromScheme. A blank phone is treated the same as absent.
    @JsonProperty("phone_number")
    private String phoneNumber;

    /**
     * Optional reading channel declared by the submitting system: one of the canonical codes
     * (BFM, ELM, PDU, IOT, MAN), case-insensitive. Kept as raw text so the controller can answer an
     * unsupported value with {@code CHANNEL_NOT_SUPPORTED} rather than letting bean validation
     * flatten it into a generic failure.
     *
     * <p>Null or blank means "not declared", which leaves the channel to be resolved from the
     * operator's stored preference exactly as before.
     */
    @JsonProperty("channel")
    private String channel;

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
