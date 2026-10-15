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
 * Body of {@code POST /api/v1/telemetry/internal/readings/quarantine/release}: which quarantined
 * readings to assess again. The scheme is named by its state or its centre scheme id, as on
 * {@code POST /readings}; leaving both out covers every scheme of the tenant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReleaseQuarantinedReadingsRequest {

    /** The most days one request can cover, both dates included, as on the republish route. */
    public static final int MAX_RANGE_DAYS = RepublishReadingsRequest.MAX_RANGE_DAYS;

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

    /** Assess and count only: nothing is released or published. */
    @JsonAlias("dry_run")
    @JsonProperty("dryRun")
    private boolean dryRun;

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
