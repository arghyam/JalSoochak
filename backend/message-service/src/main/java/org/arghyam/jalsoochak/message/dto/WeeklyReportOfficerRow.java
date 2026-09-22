package org.arghyam.jalsoochak.message.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * One row of the SDO weekly report's Section Officer performance table: the officer's resolved name
 * and mobile alongside the KPIs analytics computed for them.
 *
 * <p>Each row's KPIs cover only the schemes that officer shares with this SDO, so an officer's own
 * weekly report legitimately shows more schemes than their row here.</p>
 *
 * <p>{@code mobile} is PII: excluded from {@code toString()} and never logged above DEBUG.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklyReportOfficerRow {
    private long officerUserId;
    private String name;
    @ToString.Exclude
    private String mobile;
    private int totalSchemes;
    private int schemesSupplying;
    private int schemesNotSupplying;
    private int schemesLowLpcd;
    private double avgLpcd;
}
