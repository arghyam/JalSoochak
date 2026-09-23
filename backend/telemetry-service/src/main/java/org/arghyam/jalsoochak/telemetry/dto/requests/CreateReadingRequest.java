package org.arghyam.jalsoochak.telemetry.dto.requests;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.arghyam.jalsoochak.telemetry.channel.ReadingChannel;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateReadingRequest {

    @NotNull(message = "SchemeId is required")
    private Long schemeId;

    @NotNull(message = "Operator Id is required")
    private Long operatorId;

    private BigDecimal readingValue;

    private String readingUrl;

    private String meterChangeReason;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime readingTime;

    /**
     * READING-PROVENANCE: true when {@link #readingValue} was supplied by an integrating system through
     * {@code confirmed_reading} rather than extracted from a meter photo. Set only by the
     * server-to-server ingestion path. It changes nothing about how the reading is processed or
     * answered — it only selects the {@code confirmed_reading_source = EXTERNALLY_ASSERTED} marker
     * written on the stored row.
     */
    private boolean externallyAsserted;

    /**
     * SUPPLY-PLAUSIBILITY: opt-in to the implausible-daily-supply check. True only on the canonical
     * reading APIs.
     *
     * <p>An opt-in rather than an opt-out because {@code createReading} is shared with the
     * WhatsApp image path, which the check was deliberately scoped out of: a rejection there
     * would land inside a live conversation with no way for the operator to correct it, whereas an
     * API caller gets a 400 and can resubmit. Defaulting to false keeps every other caller
     * byte-identical.
     */
    private boolean supplyPlausibilityChecked;

    /**
     * Channel declared by the submitting system, already validated. Null means the submission did not
     * declare one, in which case the channel is resolved from the operator's stored preference — the
     * behaviour every caller had before this field existed.
     */
    private ReadingChannel declaredChannel;

    /**
     * LOCATION-AFFINITY: where the submission says it was made, when the coordinates arrive
     * <em>with</em> the reading. Set only by the state-IT reading APIs, which carry a
     * {@code geolocation} object on the request — the same scoping as
     * {@link #supplyPlausibilityChecked}.
     *
     * <p>Null on the WhatsApp paths, and that is not an omission: there the operator shares a
     * location in a separate message minutes earlier, {@code POST /location} writes it onto a
     * placeholder row, and the reading reuses that row. {@code createReading} reads the coordinates
     * back off the row in that case.
     *
     * <p>Carrying them on the request rather than applying them afterwards is what lets the boundary
     * check live at a single point for both channels, and it means a malformed {@code geolocation}
     * is rejected before anything is stored instead of after.
     */
    private BigDecimal latitude;

    /** LOCATION-AFFINITY: see {@link #latitude}. */
    private BigDecimal longitude;

    // LENIENT-INGEST: tracking fields populated only when a submission is recorded through the
    // lenient path (missing scheme / missing operator / operator-not-mapped). Null/0 for normal reads.
    private Integer ingestionSource;
    private String submittedStateSchemeId;
    private String submittedCentreSchemeId;
    private String submittedPhoneHash;
}
