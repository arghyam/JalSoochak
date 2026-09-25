package org.arghyam.jalsoochak.message.dto;

import java.util.Locale;

/**
 * The tenant a notification event belongs to, normalised to both halves of its identity:
 * the numeric id from {@code common_schema.tenant_master_table.id} and the state code from
 * {@code state_code} (e.g. {@code "MP"}).
 *
 * <p>Producers carry whichever half is in scope at the call site, so the router normalises
 * every event to this type at its boundary and fills in the other half. Either half may still
 * be null afterwards — the tenant may be unknown, soft-deleted, or deliberately absent, as it
 * is for super-user emails. {@link #NONE} is the explicit "this event has no tenant" value;
 * such events are served by the system default provider.
 *
 * <p>This is a tenant's <em>identity</em>. The separate {@code TenantSchemaRef} inside
 * {@code WhatsAppDeliveryReconciliationService} pairs an id with a tenant <em>schema name</em>
 * for its per-tenant queries, which is a different thing.
 */
public record TenantRef(Integer id, String code) {

    /** An event that carries no tenant. Served by the system default provider. */
    public static final TenantRef NONE = new TenantRef(null, null);

    /** Blanks collapse to null and the state code is upper-cased, so equal tenants compare equal. */
    public TenantRef {
        code = (code == null || code.isBlank()) ? null : code.trim().toUpperCase(Locale.ROOT);
    }

    /** True when at least one half of the identity is known. */
    public boolean isPresent() {
        return id != null || code != null;
    }

    /** True when both halves are known, i.e. the tenant resolved completely. */
    public boolean isComplete() {
        return id != null && code != null;
    }

    /** Safe for logs: identity only, never PII. */
    @Override
    public String toString() {
        return isPresent() ? "tenant(" + id + "/" + code + ")" : "tenant(none)";
    }
}
