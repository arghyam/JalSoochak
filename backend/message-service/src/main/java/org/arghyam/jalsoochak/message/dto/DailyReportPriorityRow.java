package org.arghyam.jalsoochak.message.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A fully-resolved Priority Actions row, ready to render in the PDF. Built in
 * {@code NotificationEventRouter} by combining the analytics {@code PriorityAction} (schemeId +
 * issue + daysNoSupply) with operational-schema lookups (scheme name, IMIS id, pump operators).
 *
 * <p>{@code jalMitraNames} / {@code jalMitraMobiles} are already display-joined (one cell each);
 * mobiles are PII and must never be logged at INFO/WARN/ERROR.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyReportPriorityRow {
    private String scheme;          // scheme_name
    private String imisId;          // centre_scheme_id
    private String jalMitraNames;   // pump operator name(s), comma-joined
    private String jalMitraMobiles; // pump operator phone(s), comma-joined
    private String issue;           // outage reason (human name)
    private String remarks;         // e.g. "No water supply for past N days"
}
