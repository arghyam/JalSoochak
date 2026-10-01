package org.arghyam.jalsoochak.telemetry.dto.requests;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Body of {@code POST /api/v1/telemetry/internal/readings/republish}: which stored readings to send
 * to analytics again. The scheme is named by its state or its centre scheme id, as on
 * {@code POST /readings}; leaving both out covers every scheme of the tenant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RepublishReadingsRequest {

    /** The most days one request can cover, both dates included. */
    public static final int MAX_RANGE_DAYS = 31;

    @NotNull(message = "fromDate must be provided")
    @JsonAlias("from_date")
    @JsonProperty("fromDate")
    private LocalDate fromDate;

    @NotNull(message = "toDate must be provided")
    @JsonAlias("to_date")
    @JsonProperty("toDate")
    private LocalDate toDate;

    @JsonAlias("state_scheme_id")
    @JsonProperty("stateSchemeId")
    private String stateSchemeId;

    @JsonAlias({"centre_scheme_id", "center_scheme_id", "centerSchemeId"})
    @JsonProperty("centreSchemeId")
    private String centreSchemeId;

    /**
     * ELM or PDU, case-insensitive; null or blank means both. Kept as raw text, as on
     * {@code POST /readings}, so the controller can answer another value with
     * {@code CHANNEL_NOT_SUPPORTED}.
     */
    @JsonProperty("channel")
    private String channel;

    @AssertTrue(message = "toDate must not be before fromDate")
    private boolean isRangeOrdered() {
        return fromDate == null || toDate == null || !toDate.isBefore(fromDate);
    }

    @AssertTrue(message = "The date range can't be more than " + MAX_RANGE_DAYS + " days")
    private boolean isRangeWithinLimit() {
        return fromDate == null || toDate == null || toDate.isBefore(fromDate)
                || ChronoUnit.DAYS.between(fromDate, toDate) < MAX_RANGE_DAYS;
    }
}
