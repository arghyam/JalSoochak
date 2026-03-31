package org.arghyam.jalsoochak.tenant.util;

import lombok.experimental.UtilityClass;
import org.arghyam.jalsoochak.tenant.exception.ForbiddenAccessException;

/**
 * Utility class for validating tenant access based on tenant status and user role.
 * 
 * <p>Tenant Status Access Rules:
 * <ul>
 *   <li>ONBOARDED (1): Only System Users (Super User, State Admin) can access</li>
 *   <li>CONFIGURED (2): Only System Users (Super User, State Admin) can access</li>
 *   <li>ACTIVE (3): All users can access (System Users, Staff, Public APIs)</li>
 *   <li>INACTIVE (0): Only System Users can access; data retained for compliance</li>
 *   <li>DEGRADED (5): All users can access (with known issues)</li>
 *   <li>SUSPENDED (4): Only System Users can access; access blocked for business users</li>
 *   <li>ARCHIVED (6): Only Super User can access; data in long-term storage</li>
 * </ul>
 * 
 * <p>User Role Levels:
 * <ul>
 *   <li>SUPER_USER: System user with access to all tenants</li>
 *   <li>STATE_ADMIN (adminLevel=2): System user with access to their own tenant</li>
 *   <li>STAFF (adminLevel=null): Business user (Pump Operators, Section Officers, SDO, etc.)</li>
 * </ul>
 */
@UtilityClass
public class TenantAccessValidator {

    /**
     * Validates if a system user (Super User or State Admin) can access a tenant.
     * System users can access: ONBOARDED, CONFIGURED, ACTIVE, INACTIVE, DEGRADED, SUSPENDED, ARCHIVED.
     * 
     * @param tenantStatus The tenant status code
     * @param isStateAdmin Whether the user is a STATE_ADMIN (true) or SUPER_USER (false)
     * @throws ForbiddenAccessException if the tenant status does not permit system user access
     */
    public static void validateSystemUserAccess(int tenantStatus, boolean isStateAdmin) {
        switch (tenantStatus) {
            case 0:  // INACTIVE
            case 1:  // ONBOARDED
            case 2:  // CONFIGURED
            case 3:  // ACTIVE
            case 4:  // SUSPENDED
            case 5:  // DEGRADED
                // System users can access these statuses
                return;
            case 6:  // ARCHIVED
                // Only SUPER_USER can access ARCHIVED tenants
                if (isStateAdmin) {
                    throw new ForbiddenAccessException("Tenant is archived and no longer accessible.");
                }
                return;
            default:
                throw new ForbiddenAccessException("Tenant is not accessible.");
        }
    }

    /**
     * Validates if a staff user (business user) can access a tenant.
     * Staff users can only access: ACTIVE, DEGRADED.
     * 
     * @param tenantStatus The tenant status code
     * @throws ForbiddenAccessException if the tenant status does not permit staff user access
     */
    public static void validateStaffUserAccess(int tenantStatus) {
        switch (tenantStatus) {
            case 3:  // ACTIVE
            case 5:  // DEGRADED
                // Staff users can access these statuses
                return;
            case 1:  // ONBOARDED
                throw new ForbiddenAccessException("Tenant setup is not yet complete.");
            case 2:  // CONFIGURED
                throw new ForbiddenAccessException("Tenant is not yet operational.");
            case 0:  // INACTIVE
                throw new ForbiddenAccessException("Tenant access has been deactivated.");
            case 4:  // SUSPENDED
                throw new ForbiddenAccessException("Tenant has been suspended.");
            case 6:  // ARCHIVED
                throw new ForbiddenAccessException("Tenant is archived and no longer accessible.");
            default:
                throw new ForbiddenAccessException("Tenant is not accessible.");
        }
    }

    /**
     * Validates if a user can access a tenant based on their role and the tenant status.
     * 
     * <p>System Users (SUPER_USER, STATE_ADMIN):
     * <ul>
     *   <li>Can access: ONBOARDED, CONFIGURED, ACTIVE, INACTIVE, DEGRADED, SUSPENDED</li>
     *   <li>SUPER_USER only: ARCHIVED</li>
     * </ul>
     * 
     * <p>Staff Users (adminLevel=null):
     * <ul>
     *   <li>Can access: ACTIVE, DEGRADED</li>
     * </ul>
     * 
     * @param tenantStatus The tenant status code
     * @param adminLevel The admin level (2 for STATE_ADMIN, null for staff users)
     * @throws ForbiddenAccessException if the tenant status does not permit access
     */
    public static void validateTenantAccess(int tenantStatus, Integer adminLevel) {
        boolean isSystemUser = adminLevel != null;
        boolean isStateAdmin = adminLevel != null && adminLevel == 2;

        if (isSystemUser) {
            validateSystemUserAccess(tenantStatus, isStateAdmin);
        } else {
            validateStaffUserAccess(tenantStatus);
        }
    }

    /**
     * Checks if a tenant status allows staff user access.
     * 
     * @param tenantStatus The tenant status code
     * @return true if staff users can access this tenant, false otherwise
     */
    public static boolean isAccessibleToStaff(int tenantStatus) {
        return tenantStatus == 3 || tenantStatus == 5; // ACTIVE or DEGRADED
    }

    /**
     * Checks if a tenant status allows system user access.
     * 
     * @param tenantStatus The tenant status code
     * @param isStateAdmin Whether the user is a STATE_ADMIN
     * @return true if the system user can access this tenant, false otherwise
     */
    public static boolean isAccessibleToSystemUser(int tenantStatus, boolean isStateAdmin) {
        if (tenantStatus == 6) { // ARCHIVED
            return !isStateAdmin; // Only SUPER_USER
        }
        return tenantStatus == 0 || tenantStatus == 1 || tenantStatus == 2 || 
               tenantStatus == 3 || tenantStatus == 4 || tenantStatus == 5;
    }
}
