package org.arghyam.jalsoochak.analytics.enums;

/** The two region trees a dashboard can be browsed by. */
public enum RegionHierarchy {
    /** Administrative regions: {@code dim_lgd_location_table}, {@code dim_scheme_table.level_N_lgd_id}. */
    LGD,
    /** Department regions: {@code dim_department_location_table}, {@code dim_scheme_table.level_N_dept_id}. */
    DEPARTMENT
}
