package org.arghyam.jalsoochak.user.enums;

/**
 * Represents a user's role in the context of tenant access validation.
 *
 * <p>Maps from the DB {@code admin_level} integer:
 * <ul>
 *   <li>{@code 1}    → {@link #SUPER_USER}</li>
 *   <li>{@code 2}    → {@link #STATE_ADMIN}</li>
 *   <li>any other value (including {@code null}) → {@link #STAFF}</li>
 * </ul>
 */
public enum TenantAccessRole {
    SUPER_USER,
    STATE_ADMIN,
    STAFF;

    /**
     * Derives the access role from a DB {@code admin_level} value.
     *
     * <p>Mapping:
     * <ul>
     *   <li>{@code 1}    → {@link #SUPER_USER}</li>
     *   <li>{@code 2}    → {@link #STATE_ADMIN}</li>
     *   <li>{@code null} → {@link #STAFF}</li>
     *   <li>any other non-null value → {@link #STAFF} (fail-safe default)</li>
     * </ul>
     *
     * @param adminLevel the {@code admin_level} column value from {@code admin_user_master_table}
     * @return the corresponding {@code TenantAccessRole}
     */
    public static TenantAccessRole fromAdminLevel(Integer adminLevel) {
        if (adminLevel == null) return STAFF;
        return switch (adminLevel) {
            case 1 -> SUPER_USER;
            case 2 -> STATE_ADMIN;
            default -> STAFF;
        };
    }
}
