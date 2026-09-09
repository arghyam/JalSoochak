package org.arghyam.jalsoochak.telemetry.dto.requests;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IssueReportRequest {

    /** Matches the narrowest downstream column, {@code fact_water_quantity_table.outage_reason}. */
    public static final int MAX_ISSUE_REASON_LENGTH = 255;

    private String contactId;

    /**
     * Free-text or menu-selected issue reason.
     *
     * <p>Capped at {@link #MAX_ISSUE_REASON_LENGTH} rather than the 500 characters a security audit
     * suggested, because the narrowest downstream sink is {@code VARCHAR(255)} —
     * {@code analytics_schema.fact_water_quantity_table.outage_reason}. A longer value is accepted
     * by every column on this side (all {@code TEXT}) and then fails the analytics consumer's
     * insert, so 500 would have left that data loss open.
     *
     * <p>{@code @Size} ignores {@code null}, so a missing reason still falls through to the
     * service-layer check and its localised "Issue reason is required." reply rather than becoming
     * a {@code 400}. Character validation deliberately lives in the service too, so that a genuine
     * operator typo gets a localised WhatsApp answer instead of a flow-breaking {@code 400} — see
     * {@code GlificMeterWorkflowService.ISSUE_REASON_ALLOWED}.
     *
     * <p>Note this DTO is shared by {@code /issue-report/submit},
     * {@code /issue-report/telemetry/submit} and {@code /others/submitted}, so the cap applies to
     * all three.
     */
    @JsonAlias({"issueReason", "reason", "issue", "results", "message"})
    @Size(max = MAX_ISSUE_REASON_LENGTH,
            message = "issueReason must not exceed " + MAX_ISSUE_REASON_LENGTH + " characters")
    private String issueReason;
}
