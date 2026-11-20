package org.arghyam.jalsoochak.tenant.enums.reportlayout;

/**
 * The columns a scheme list can show. Ids use platform terms; "Jal Mitra" and "IMIS" appear only
 * in the labels a layout gives them.
 */
public enum SchemeColumn {
    /** The row's 1-based position in its list. */
    SERIAL_NO,
    SCHEME_NAME,
    /** The scheme's IMIS id ({@code centre_scheme_id}). */
    CENTRE_SCHEME_ID,
    VILLAGES,
    PUMP_OPERATOR_NAMES,
    PUMP_OPERATOR_MOBILES,
    SECTION_OFFICER_NAMES,
    SECTION_OFFICER_MOBILES,
    /** Only the {@link SchemeList#ANOMALIES} list has an anomaly per row. */
    ANOMALY_TYPE;

    public boolean allowedIn(SchemeList list) {
        return this != ANOMALY_TYPE || list == SchemeList.ANOMALIES;
    }
}
