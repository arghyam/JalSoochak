package org.arghyam.jalsoochak.scheme.statesync.run;

public enum RunKind {
    /** Masters, users, every scheme, archived and blocked lists. */
    FULL,
    /** Schemes updated since the last APPLY watermark (all schemes when there is none), and the blocked list. */
    DELTA,
    /** One scheme, by upstream code or IMIS id, and the blocked list — for a support ticket or a lenient-ingestion miss. */
    SCHEME_REFRESH
}
