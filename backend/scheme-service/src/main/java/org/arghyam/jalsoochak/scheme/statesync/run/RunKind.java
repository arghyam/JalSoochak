package org.arghyam.jalsoochak.scheme.statesync.run;

public enum RunKind {
    /** Masters, users, every scheme, archived and blocked lists. */
    FULL,
    /** Schemes updated since the last APPLY watermark (all schemes when there is none). */
    DELTA,
    /** One scheme, by upstream code or IMIS id — for a support ticket or a lenient-ingestion miss. */
    SCHEME_REFRESH
}
