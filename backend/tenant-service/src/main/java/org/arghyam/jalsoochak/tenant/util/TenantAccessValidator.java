package org.arghyam.jalsoochak.tenant.util;

import lombok.experimental.UtilityClass;
import org.arghyam.jalsoochak.tenant.enums.TenantAccessRole;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.exception.ForbiddenAccessException;

/**
 * Utility class for validating tenant access based on tenant status and user role.
 *
 * <p>Tenant status constants are defined in user-service at
 * {@code org.arghyam.jalsoochak.user.constants.TenantStatusConstants}.
 * These must stay in sync with {@link org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum}
 * and {@code common_schema.tenant_master_table} (database).
 *
 * <p>Tenant Status Access Rules:
 * <ul>
 *   <li>ONBOARDED: Only System Users (Super User, State Admin) can access</li>
 *   <li>CONFIGURED: Only System Users (Super User, State Admin) can access</li>
 *   <li>ACTIVE: All users can access (System Users, Staff)</li>
 *   <li>INACTIVE: Only System Users can access; data retained for compliance</li>
 *   <li>DEGRADED: All users can access (with known issues)</li>
 *   <li>SUSPENDED: Only System Users can access; access blocked for business users</li>
 *   <li>ARCHIVED: Only Super User can access; data in long-term storage</li>
 * </ul>
 *
 * <p>User Role Levels:
 * <ul>
 *   <li>SUPER_USER: System user with access to all tenants</li>
 *   <li>STATE_ADMIN: System user with access to their own tenant</li>
 *   <li>STAFF: Business user (Pump Operators, Section Officers, SDO, etc.)</li>
 * </ul>
 */
@UtilityClass
public class TenantAccessValidator {

    /**
     * Checks if the given tenant status is a known/allowed status.
     *
     * @param tenantStatus The tenant status to validate
     * @return true if the status is one of the allowed values, false otherwise
     */
    private static boolean isKnownTenantStatus(TenantStatusEnum tenantStatus) {
        return tenantStatus == TenantStatusEnum.INACTIVE || tenantStatus == TenantStatusEnum.ONBOARDED
                || tenantStatus == TenantStatusEnum.CONFIGURED || tenantStatus == TenantStatusEnum.ACTIVE
                || tenantStatus == TenantStatusEnum.SUSPENDED || tenantStatus == TenantStatusEnum.DEGRADED
                || tenantStatus == TenantStatusEnum.ARCHIVED;
    }

    /**
     * Validates if a system user (SUPER_USER or STATE_ADMIN) can access a tenant.
     * System users can access all statuses except ARCHIVED, which is restricted to SUPER_USER.
     *
     * @param tenantStatus The tenant status
     * @param role         The caller's access role
     * @throws ForbiddenAccessException if role is null, STAFF, or if the tenant status does not permit access
     */
    public static void validateSystemUserAccess(TenantStatusEnum tenantStatus, TenantAccessRole role) {
        if (role == null || (!role.isSuperUserEquivalent() && !role.isStateAdminEquivalent())) {
            throw new ForbiddenAccessException("Access denied: invalid user role.");
        }
        if (tenantStatus == TenantStatusEnum.ARCHIVED && role == TenantAccessRole.STATE_ADMIN) {
            throw new ForbiddenAccessException("Tenant is archived and no longer accessible.");
        }
        if (!isKnownTenantStatus(tenantStatus)) {
            throw new ForbiddenAccessException("Tenant is not accessible.");
        }
    }

    /**
     * Validates if a staff user (business user) can access a tenant.
     * Staff users can only access: ACTIVE, DEGRADED.
     *
     * @param tenantStatus The tenant status
     * @throws ForbiddenAccessException if tenantStatus is null or if the tenant status does not permit staff user access
     */
    public static void validateStaffUserAccess(TenantStatusEnum tenantStatus) {
        if (tenantStatus == null || !isKnownTenantStatus(tenantStatus)) {
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
     * @param tenantStatus The tenant status
     * @param role         The caller's access role
     * @throws ForbiddenAccessException if role is null or if the tenant status does not permit access
     */
    public static void validateTenantAccess(TenantStatusEnum tenantStatus, TenantAccessRole role) {
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
     * @param tenantStatus The tenant status
     * @return true if staff users can access this tenant, false otherwise
     */
    public static boolean isAccessibleToStaff(TenantStatusEnum tenantStatus) {
        return tenantStatus == TenantStatusEnum.ACTIVE || tenantStatus == TenantStatusEnum.DEGRADED;
    }

    /**
     * Checks if a tenant status allows system user access.
     *
     * @param tenantStatus The tenant status
     * @param role         The caller's access role
     * @return true if the system user can access this tenant, false otherwise
     */
    public static boolean isAccessibleToSystemUser(TenantStatusEnum tenantStatus, TenantAccessRole role) {
        // Validate role is a system user role (explicit allowlist)
        if (role == null || (!role.isSuperUserEquivalent() && !role.isStateAdminEquivalent())) {
            return false;
        }

        // Validate tenant status is one of the allowed values
        if (tenantStatus == null) {
            return false;
        }

        // ARCHIVED is only accessible to SUPER_USER-equivalent roles
        if (tenantStatus == TenantStatusEnum.ARCHIVED) {
            return role.isSuperUserEquivalent();
        }
        
        // All other valid statuses are accessible to SUPER_USER and STATE_ADMIN
        return tenantStatus == TenantStatusEnum.ONBOARDED
                || tenantStatus == TenantStatusEnum.CONFIGURED
                || tenantStatus == TenantStatusEnum.ACTIVE
                || tenantStatus == TenantStatusEnum.INACTIVE
                || tenantStatus == TenantStatusEnum.SUSPENDED
                || tenantStatus == TenantStatusEnum.DEGRADED;
    }
}
