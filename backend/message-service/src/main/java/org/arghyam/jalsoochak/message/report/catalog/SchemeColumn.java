package org.arghyam.jalsoochak.message.report.catalog;

import org.arghyam.jalsoochak.message.dto.ReportSchemeRow;

import java.util.function.BiFunction;

/**
 * The columns a scheme list can show. Ids use platform terms; "Jal Mitra" and "IMIS" appear only
 * in the labels a layout gives them.
 */
public enum SchemeColumn {
    /** The row's 1-based position in its list. */
    SERIAL_NO(6f, false, false, (row, serialNo) -> String.valueOf(serialNo)),
    SCHEME_NAME(30f, false, false, (row, serialNo) -> row.getSchemeName()),
    /** The scheme's IMIS id ({@code centre_scheme_id}). */
    CENTRE_SCHEME_ID(20f, false, false, (row, serialNo) -> row.getImisId()),
    VILLAGES(18f, false, true, (row, serialNo) -> row.getVillageNames()),
    PUMP_OPERATOR_NAMES(24f, false, false, (row, serialNo) -> row.getJalMitraNames()),
    PUMP_OPERATOR_MOBILES(20f, false, false, (row, serialNo) -> row.getJalMitraMobiles()),
    SECTION_OFFICER_NAMES(20f, true, false, (row, serialNo) -> row.getSectionOfficerNames()),
    SECTION_OFFICER_MOBILES(18f, true, false, (row, serialNo) -> row.getSectionOfficerMobiles()),
    /** Only the {@link SchemeList#ANOMALIES} list has an anomaly per row. */
    ANOMALY_TYPE(23f, false, false, (row, serialNo) -> row.getAnomalyType());

    private final float defaultWeight;
    private final boolean needsSectionOfficers;
    private final boolean needsVillages;
    private final BiFunction<ReportSchemeRow, Integer, String> cell;

    SchemeColumn(float defaultWeight, boolean needsSectionOfficers, boolean needsVillages,
                 BiFunction<ReportSchemeRow, Integer, String> cell) {
        this.defaultWeight = defaultWeight;
        this.needsSectionOfficers = needsSectionOfficers;
        this.needsVillages = needsVillages;
        this.cell = cell;
    }

    /** The relative width used when a table's columns set none. */
    public float defaultWeight() {
        return defaultWeight;
    }

    /** Whether the rows must carry the schemes' Section Officer contacts, an extra lookup. */
    public boolean needsSectionOfficers() {
        return needsSectionOfficers;
    }

    /** Whether the rows must carry the schemes' village names, an extra lookup. */
    public boolean needsVillages() {
        return needsVillages;
    }

    public boolean allowedIn(SchemeList list) {
        return this != ANOMALY_TYPE || list == SchemeList.ANOMALIES;
    }

    /** The cell text for {@code row}, never {@code null}. */
    public String cell(ReportSchemeRow row, int serialNo) {
        String text = cell.apply(row, serialNo);
        return text == null ? "" : text;
    }
}
