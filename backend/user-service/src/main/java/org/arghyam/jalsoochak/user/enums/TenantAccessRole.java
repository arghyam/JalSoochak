package org.arghyam.jalsoochak.user.enums;

/**
 * Represents a user's role in the context of tenant access validation.
 *
 * <p>Maps from the DB {@code user_type_master_table.c_name} string (case-insensitive).
 */
public enum TenantAccessRole {
    SUPER_USER,
    STATE_ADMIN,
    STAFF;

    /**
     * Derives the access role from the {@code c_name} column of {@code user_type_master_table}.
     * Matching is case-insensitive so that variations like {@code "Super User"} or
     * {@code "super_user"} are accepted alongside the canonical {@code "SUPER_USER"}.
     *
     * @param cName the {@code c_name} value joined from {@code user_type_master_table}
     * @return the corresponding {@code TenantAccessRole}
     * @throws IllegalArgumentException if {@code cName} is {@code null} or has no matching constant
     */
    public static TenantAccessRole fromCName(String cName) {
        if (cName == null) {
            throw new IllegalArgumentException("Unrecognised user type c_name: null");
        }
        // Normalize: trim, replace whitespace with underscore, uppercase — so "Super User" and "super_user" both match SUPER_USER
        String normalized = cName.trim().replaceAll("\\s+", "_").toUpperCase(java.util.Locale.ROOT);
        for (TenantAccessRole role : values()) {
            if (role.name().equals(normalized)) {
                return role;
            }
        }
        throw new IllegalArgumentException("Unrecognised user type c_name: " + cName);
    }
}
