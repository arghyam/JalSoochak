package org.arghyam.jalsoochak.tenant.enums;

/**
 * Represents a user's role in the context of tenant access validation.
 *
 * <p>Maps from the DB {@code admin_level} integer:
 * <ul>
 *   <li>{@code 1}    → {@link #SUPER_USER}</li>
 *   <li>{@code 2}    → {@link #STATE_ADMIN}</li>
 *   <li>any other value (including {@code null}) → throws {@link IllegalArgumentException}</li>
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
     *   <li>{@code null} or any other non-null value → throws {@link IllegalArgumentException}</li>
     * </ul>
     *
     * @param adminLevel the {@code admin_level} column value from {@code admin_user_master_table}
     * @return the corresponding {@code TenantAccessRole}
     * @throws IllegalArgumentException if adminLevel is null or not a recognised system-user level
     */
    public static TenantAccessRole fromAdminLevel(Integer adminLevel) {
        if (adminLevel == null) {
            throw new IllegalArgumentException("Unrecognised admin_level: null");
        }
        return switch (adminLevel) {
            case 1 -> SUPER_USER;
            case 2 -> STATE_ADMIN;
            default -> throw new IllegalArgumentException("Unrecognised admin_level: " + adminLevel);
        };
    }
}
