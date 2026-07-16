package org.arghyam.jalsoochak.message.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A fully-resolved Section-Officer Summary row for the SDO report's per-officer breakdown table
 * (the first table of the SDO Summary section). Built in {@code NotificationEventRouter} by combining
 * the analytics {@code SectionOfficerSummary} (KPIs, keyed by officer user id) with an operational
 * {@code user_table} lookup for the officer's name + mobile.
 *
 * <p>{@code officerMobile} is PII and must never be logged at INFO/WARN/ERROR.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyReportSectionOfficerRow {
    private String officerName;
    @ToString.Exclude
    private String officerMobile; // PII — omitted from toString()
    private int totalSchemes;
    private int schemesSupplying;
    private int schemesNotSupplying;
    private double avgLpcd;
    private double avgMld;
    private double regularSupplyPctWeek;
    private double readingSubmissionPct;
    private int anomalousCount;
}
