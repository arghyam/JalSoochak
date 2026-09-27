package org.arghyam.jalsoochak.message.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A fully-resolved scheme row, ready to render in any of the water reports' scheme-list sections.
 * Built in {@code NotificationEventRouter} by combining a scheme id from analytics with
 * operational-schema lookups (scheme name, IMIS id, villages, Jal Mitra and Section Officer contacts).
 *
 * <p>Which fields a given section uses varies — the SO daily lists IMIS id and Jal Mitra, the SDO
 * weekly additionally the Section Officer and village — so unused fields are simply left blank rather
 * than modelled as separate row types.</p>
 *
 * <p>All mobile fields are PII: they are excluded from {@code toString()} and must never reach an
 * INFO/WARN/ERROR log line.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportSchemeRow {
    private int schemeId;
    private String schemeName;
    private String imisId;            // scheme_master_table.centre_scheme_id
    private String villageNames;      // comma-joined; a scheme can span several villages
    private String jalMitraNames;     // pump operator name(s), comma-joined
    @ToString.Exclude
    private String jalMitraMobiles;   // PII
    private String sectionOfficerNames;   // SDO report only, comma-joined
    @ToString.Exclude
    private String sectionOfficerMobiles; // PII
    /** Anomaly type label, for the daily report's anomalous-submissions section only. */
    private String anomalyType;
}
