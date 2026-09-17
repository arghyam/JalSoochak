package org.arghyam.jalsoochak.user.constants;

/**
 * Tenant status constants used for access validation.
 *
 * <p>These constants must stay in sync with:
 * <ul>
 *   <li>{@code common_schema.tenant_master_table} (database)</li>
 *   <li>{@link org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum} (tenant-service)</li>
 * </ul>
 *
 * <p>Tenant Status Access Rules:
 * <ul>
 *   <li>INACTIVE (0): Super User only; State Admin blocked; data retained for compliance</li>
 *   <li>ONBOARDED (1): System Users only</li>
 *   <li>CONFIGURED (2): System Users only</li>
 *   <li>ACTIVE (3): All users (System, Staff, Public APIs)</li>
 *   <li>SUSPENDED (4): Super User only; State Admin and business users blocked</li>
 *   <li>DEGRADED (5): All users (with known issues)</li>
 *   <li>ARCHIVED (6): Super User only; State Admin blocked</li>
 *   <li>REGISTERED (7): Pre-seeded tenant, not yet onboarded; no schema; hidden from all access</li>
 * </ul>
 */
public final class TenantStatusConstants {

    private TenantStatusConstants() {
        // Utility class; prevent instantiation
    }

    public static final int INACTIVE   = 0;
    public static final int ONBOARDED  = 1;
    public static final int CONFIGURED = 2;
    public static final int ACTIVE     = 3;
    public static final int SUSPENDED  = 4;
    public static final int DEGRADED   = 5;
    public static final int ARCHIVED   = 6;
    public static final int REGISTERED = 7;
}
