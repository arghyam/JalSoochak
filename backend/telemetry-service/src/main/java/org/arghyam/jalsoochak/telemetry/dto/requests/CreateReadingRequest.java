package org.arghyam.jalsoochak.telemetry.dto.requests;

import com.fasterxml.jackson.annotation.JsonFormat;
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

    // LENIENT-INGEST: tracking fields populated only when a submission is recorded through the
    // lenient path (missing scheme / missing operator / operator-not-mapped). Null/0 for normal reads.
    private Integer ingestionSource;
    private String submittedStateSchemeId;
    private String submittedCentreSchemeId;
    private String submittedPhoneHash;
}
