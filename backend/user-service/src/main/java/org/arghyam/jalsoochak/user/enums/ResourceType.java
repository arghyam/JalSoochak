package org.arghyam.jalsoochak.user.enums;

/**
 * Logical resource categories tracked by the per-tenant
 * {@code data_versions_table}. The {@link #key()} is the value persisted to
 * the {@code resource_type} column — keep it stable.
 *
 * <p>Used by the report-cache to detect staleness: each mutation of a
 * resource bumps its counter; the cache key includes the version, so any
 * change invalidates previously generated reports.
 *
 * <p>New report types (schemes, operators, …) plug in as additional
 * enum constants — no schema change needed.
 */
public enum ResourceType {
    STAFF_USERS("STAFF_USERS");

    private final String key;

    ResourceType(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }
}