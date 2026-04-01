package org.arghyam.jalsoochak.user.util;

import lombok.experimental.UtilityClass;
import org.arghyam.jalsoochak.user.constants.TenantStatusConstants;
import org.arghyam.jalsoochak.user.enums.TenantAccessRole;
import org.arghyam.jalsoochak.user.exceptions.ForbiddenAccessException;

/**
 * Utility class for validating tenant access based on tenant status and user role.
 *
 * <p>Tenant status constants are defined in {@link TenantStatusConstants}.
 * These must stay in sync with:
 * <ul>
 *   <li>{@code common_schema.tenant_master_table} (database)</li>
 *   <li>{@link org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum} (tenant-service)</li>
 * </ul>
 *
 * <p>Tenant Status Access Rules:
 * <ul>
 *   <li>ONBOARDED (1): Only System Users (SUPER_USER, STATE_ADMIN) can access</li>
 *   <li>CONFIGURED (2): Only System Users (SUPER_USER, STATE_ADMIN) can access</li>
 *   <li>ACTIVE (3): All users can access (System Users, Staff, Public APIs)</li>
 *   <li>INACTIVE (0): Only System Users can access; data retained for compliance</li>
 *   <li>DEGRADED (5): All users can access (with known issues)</li>
 *   <li>SUSPENDED (4): Only System Users can access; access blocked for business users</li>
 *   <li>ARCHIVED (6): Only SUPER_USER can access; data in long-term storage</li>
 * </ul>
 */
@UtilityClass
public class TenantAccessValidator {

    // Use shared constants from TenantStatusConstants
    private static final int INACTIVE   = TenantStatusConstants.INACTIVE;
    private static final int ONBOARDED  = TenantStatusConstants.ONBOARDED;
    private static final int CONFIGURED = TenantStatusConstants.CONFIGURED;
    private static final int ACTIVE     = TenantStatusConstants.ACTIVE;
    private static final int SUSPENDED  = TenantStatusConstants.SUSPENDED;
    private static final int DEGRADED   = TenantStatusConstants.DEGRADED;
    private static final int ARCHIVED   = TenantStatusConstants.ARCHIVED;

    /**
     * Validates if a system user (SUPER_USER or STATE_ADMIN) can access a tenant.
     * System users can access all statuses except ARCHIVED, which is restricted to SUPER_USER.
     *
     * @param tenantStatus The tenant status code
     * @param role         The caller's access role
     * @throws ForbiddenAccessException if role is null or if the tenant status does not permit access
     */
    public static void validateSystemUserAccess(int tenantStatus, TenantAccessRole role) {
        if (role == null || (role != TenantAccessRole.SUPER_USER && role != TenantAccessRole.STATE_ADMIN)) {
            throw new ForbiddenAccessException("Access denied: invalid user role.");
        }
        if (tenantStatus == ARCHIVED && role == TenantAccessRole.STATE_ADMIN) {
            throw new ForbiddenAccessException("Tenant is archived and no longer accessible.");
        }
        if (tenantStatus != INACTIVE && tenantStatus != ONBOARDED && tenantStatus != CONFIGURED
                && tenantStatus != ACTIVE && tenantStatus != SUSPENDED && tenantStatus != DEGRADED
                && tenantStatus != ARCHIVED) {
            throw new ForbiddenAccessException("Tenant is not accessible.");
        }
    }

    /**
     * Validates if a staff user (business user) can access a tenant.
     * Staff users can only access: ACTIVE, DEGRADED.
     *
     * @param tenantStatus The tenant status code
     * @throws ForbiddenAccessException if tenantStatus is invalid or if the tenant status does not permit staff user access
     */
    public static void validateStaffUserAccess(int tenantStatus) {
        // Validate status is one of the allowed values
        if (tenantStatus != INACTIVE && tenantStatus != ONBOARDED && tenantStatus != CONFIGURED
                && tenantStatus != ACTIVE && tenantStatus != SUSPENDED && tenantStatus != DEGRADED
                && tenantStatus != ARCHIVED) {
            throw new ForbiddenAccessException("Tenant is not accessible.");
        }
        switch (tenantStatus) {
            case ACTIVE, DEGRADED -> { /* allowed */ }
            case ONBOARDED -> throw new ForbiddenAccessException("Tenant setup is not yet complete.");
            case CONFIGURED -> throw new ForbiddenAccessException("Tenant is not yet operational.");
            case INACTIVE -> throw new ForbiddenAccessException("Tenant access has been deactivated.");
            case SUSPENDED -> throw new ForbiddenAccessException("Tenant has been suspended.");
            case ARCHIVED -> throw new ForbiddenAccessException("Tenant is archived and no longer accessible.");
            default -> throw new ForbiddenAccessException("Tenant is not accessible.");
        }
    }

    /**
     * Validates if a user can access a tenant based on their role and the tenant status.
     *
     * <p>SUPER_USER / STATE_ADMIN:
     * <ul>
     *   <li>Can access: ONBOARDED, CONFIGURED, ACTIVE, INACTIVE, DEGRADED, SUSPENDED</li>
     *   <li>SUPER_USER only: ARCHIVED</li>
     * </ul>
     *
     * <p>STAFF:
     * <ul>
     *   <li>Can access: ACTIVE, DEGRADED</li>
     * </ul>
     *
     * @param tenantStatus The tenant status code
     * @param role         The caller's access role
     * @throws ForbiddenAccessException if role is null or if the tenant status does not permit access
     */
    public static void validateTenantAccess(int tenantStatus, TenantAccessRole role) {
        if (role == null) {
            throw new ForbiddenAccessException("Access denied: invalid user role.");
        }
        if (role == TenantAccessRole.STAFF) {
            validateStaffUserAccess(tenantStatus);
        } else {
            validateSystemUserAccess(tenantStatus, role);
        }
    }

    /**
     * Checks if a tenant status allows staff user access.
     *
     * @param tenantStatus The tenant status code
     * @return true if staff users can access this tenant, false otherwise
     */
    public static boolean isAccessibleToStaff(int tenantStatus) {
        return tenantStatus == ACTIVE || tenantStatus == DEGRADED;
    }

    /**
     * Checks if a tenant status allows system user access.
     *
     * @param tenantStatus The tenant status code
     * @param role         The caller's access role
     * @return true if the system user can access this tenant, false otherwise
     */
    public static boolean isAccessibleToSystemUser(int tenantStatus, TenantAccessRole role) {
        // Validate role is a system user role (explicit allowlist)
        if (role == null || (role != TenantAccessRole.SUPER_USER && role != TenantAccessRole.STATE_ADMIN)) {
            return false;
        }
        
        // ARCHIVED is only accessible to SUPER_USER
        if (tenantStatus == ARCHIVED) {
            return role == TenantAccessRole.SUPER_USER;
        }
        
        // All other valid statuses are accessible to SUPER_USER and STATE_ADMIN
        return tenantStatus == INACTIVE || tenantStatus == ONBOARDED || tenantStatus == CONFIGURED
                || tenantStatus == ACTIVE || tenantStatus == SUSPENDED || tenantStatus == DEGRADED;
    }
}
