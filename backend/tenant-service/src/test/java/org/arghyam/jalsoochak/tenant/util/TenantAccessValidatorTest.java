package org.arghyam.jalsoochak.tenant.util;

import org.arghyam.jalsoochak.tenant.enums.TenantAccessRole;
import org.arghyam.jalsoochak.tenant.enums.TenantStatusEnum;
import org.arghyam.jalsoochak.tenant.exception.ForbiddenAccessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TenantAccessValidator Tests")
class TenantAccessValidatorTest {

    @Nested
    @DisplayName("System User Access Validation")
    class SystemUserAccessTests {

        @Test
        @DisplayName("SUPER_USER can access ONBOARDED tenant")
        void superUserCanAccessOnboarded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.ONBOARDED, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("SUPER_USER can access CONFIGURED tenant")
        void superUserCanAccessConfigured() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.CONFIGURED, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("SUPER_USER can access ACTIVE tenant")
        void superUserCanAccessActive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.ACTIVE, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("SUPER_USER can access INACTIVE tenant")
        void superUserCanAccessInactive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.INACTIVE, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("SUPER_USER can access DEGRADED tenant")
        void superUserCanAccessDegraded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.DEGRADED, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("SUPER_USER can access SUSPENDED tenant")
        void superUserCanAccessSuspended() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.SUSPENDED, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("SUPER_USER can access ARCHIVED tenant")
        void superUserCanAccessArchived() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.ARCHIVED, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("STATE_ADMIN can access ONBOARDED tenant")
        void stateAdminCanAccessOnboarded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.ONBOARDED, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("STATE_ADMIN can access CONFIGURED tenant")
        void stateAdminCanAccessConfigured() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.CONFIGURED, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("STATE_ADMIN can access ACTIVE tenant")
        void stateAdminCanAccessActive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.ACTIVE, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("STATE_ADMIN can access INACTIVE tenant")
        void stateAdminCanAccessInactive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.INACTIVE, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("STATE_ADMIN can access DEGRADED tenant")
        void stateAdminCanAccessDegraded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.DEGRADED, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("STATE_ADMIN can access SUSPENDED tenant")
        void stateAdminCanAccessSuspended() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.SUSPENDED, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("STATE_ADMIN cannot access ARCHIVED tenant")
        void stateAdminCannotAccessArchived() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.ARCHIVED, TenantAccessRole.STATE_ADMIN)
            );
            assertTrue(exception.getMessage().contains("archived"));
        }

        @Test
        @DisplayName("SUPER_STATE_ADMIN can access ONBOARDED tenant")
        void superStateAdminCanAccessOnboarded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.ONBOARDED, TenantAccessRole.SUPER_STATE_ADMIN));
        }

        @Test
        @DisplayName("SUPER_STATE_ADMIN can access ACTIVE tenant")
        void superStateAdminCanAccessActive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.ACTIVE, TenantAccessRole.SUPER_STATE_ADMIN));
        }

        @Test
        @DisplayName("SUPER_STATE_ADMIN can access ARCHIVED tenant")
        void superStateAdminCanAccessArchived() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.ARCHIVED, TenantAccessRole.SUPER_STATE_ADMIN));
        }

        @Test
        @DisplayName("STAFF role throws ForbiddenAccessException")
        void staffRoleThrowsForbiddenAccess() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateSystemUserAccess(TenantStatusEnum.ACTIVE, TenantAccessRole.STAFF)
            );
            assertTrue(exception.getMessage().contains("invalid user role"));
        }
    }

    @Nested
    @DisplayName("Staff User Access Validation")
    class StaffUserAccessTests {

        @Test
        @DisplayName("Staff can access ACTIVE tenant")
        void staffCanAccessActive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateStaffUserAccess(TenantStatusEnum.ACTIVE));
        }

        @Test
        @DisplayName("Staff can access DEGRADED tenant")
        void staffCanAccessDegraded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateStaffUserAccess(TenantStatusEnum.DEGRADED));
        }

        @Test
        @DisplayName("Staff cannot access ONBOARDED tenant")
        void staffCannotAccessOnboarded() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateStaffUserAccess(TenantStatusEnum.ONBOARDED)
            );
            assertTrue(exception.getMessage().contains("not yet complete"));
        }

        @Test
        @DisplayName("Staff cannot access CONFIGURED tenant")
        void staffCannotAccessConfigured() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateStaffUserAccess(TenantStatusEnum.CONFIGURED)
            );
            assertTrue(exception.getMessage().contains("not yet operational"));
        }

        @Test
        @DisplayName("Staff cannot access INACTIVE tenant")
        void staffCannotAccessInactive() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateStaffUserAccess(TenantStatusEnum.INACTIVE)
            );
            assertTrue(exception.getMessage().contains("deactivated"));
        }

        @Test
        @DisplayName("Staff cannot access SUSPENDED tenant")
        void staffCannotAccessSuspended() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateStaffUserAccess(TenantStatusEnum.SUSPENDED)
            );
            assertTrue(exception.getMessage().contains("suspended"));
        }

        @Test
        @DisplayName("Staff cannot access ARCHIVED tenant")
        void staffCannotAccessArchived() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateStaffUserAccess(TenantStatusEnum.ARCHIVED)
            );
            assertTrue(exception.getMessage().contains("archived"));
        }
    }

    @Nested
    @DisplayName("Tenant Access Validation (Role-Based)")
    class TenantAccessTests {

        @Test
        @DisplayName("STATE_ADMIN can access ACTIVE tenant")
        void stateAdminCanAccessActive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateTenantAccess(TenantStatusEnum.ACTIVE, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("STATE_ADMIN can access ONBOARDED tenant")
        void stateAdminCanAccessOnboarded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateTenantAccess(TenantStatusEnum.ONBOARDED, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("STATE_ADMIN cannot access ARCHIVED tenant")
        void stateAdminCannotAccessArchived() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(TenantStatusEnum.ARCHIVED, TenantAccessRole.STATE_ADMIN)
            );
        }

        @Test
        @DisplayName("SUPER_USER can access ARCHIVED tenant")
        void superUserCanAccessArchived() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateTenantAccess(TenantStatusEnum.ARCHIVED, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("STAFF can access ACTIVE tenant")
        void staffCanAccessActive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateTenantAccess(TenantStatusEnum.ACTIVE, TenantAccessRole.STAFF));
        }

        @Test
        @DisplayName("STAFF can access DEGRADED tenant")
        void staffCanAccessDegraded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateTenantAccess(TenantStatusEnum.DEGRADED, TenantAccessRole.STAFF));
        }

        @Test
        @DisplayName("STAFF cannot access ONBOARDED tenant")
        void staffCannotAccessOnboarded() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(TenantStatusEnum.ONBOARDED, TenantAccessRole.STAFF)
            );
        }

        @Test
        @DisplayName("STAFF cannot access CONFIGURED tenant")
        void staffCannotAccessConfigured() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(TenantStatusEnum.CONFIGURED, TenantAccessRole.STAFF)
            );
        }

        @Test
        @DisplayName("STAFF cannot access INACTIVE tenant")
        void staffCannotAccessInactive() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(TenantStatusEnum.INACTIVE, TenantAccessRole.STAFF)
            );
        }

        @Test
        @DisplayName("STAFF cannot access SUSPENDED tenant")
        void staffCannotAccessSuspended() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(TenantStatusEnum.SUSPENDED, TenantAccessRole.STAFF)
            );
        }

        @Test
        @DisplayName("STAFF cannot access ARCHIVED tenant")
        void staffCannotAccessArchived() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(TenantStatusEnum.ARCHIVED, TenantAccessRole.STAFF)
            );
        }
    }

    @Nested
    @DisplayName("Helper Methods")
    class HelperMethodsTests {

        @Test
        @DisplayName("isAccessibleToStaff returns true for ACTIVE")
        void isAccessibleToStaffActive() {
            assertTrue(TenantAccessValidator.isAccessibleToStaff(TenantStatusEnum.ACTIVE));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns true for DEGRADED")
        void isAccessibleToStaffDegraded() {
            assertTrue(TenantAccessValidator.isAccessibleToStaff(TenantStatusEnum.DEGRADED));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns false for ONBOARDED")
        void isAccessibleToStaffOnboarded() {
            assertFalse(TenantAccessValidator.isAccessibleToStaff(TenantStatusEnum.ONBOARDED));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns false for CONFIGURED")
        void isAccessibleToStaffConfigured() {
            assertFalse(TenantAccessValidator.isAccessibleToStaff(TenantStatusEnum.CONFIGURED));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns false for INACTIVE")
        void isAccessibleToStaffInactive() {
            assertFalse(TenantAccessValidator.isAccessibleToStaff(TenantStatusEnum.INACTIVE));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns false for SUSPENDED")
        void isAccessibleToStaffSuspended() {
            assertFalse(TenantAccessValidator.isAccessibleToStaff(TenantStatusEnum.SUSPENDED));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns false for ARCHIVED")
        void isAccessibleToStaffArchived() {
            assertFalse(TenantAccessValidator.isAccessibleToStaff(TenantStatusEnum.ARCHIVED));
        }

        @Test
        @DisplayName("isAccessibleToSystemUser returns true for SUPER_USER accessing ARCHIVED")
        void isAccessibleToSystemUserSuperUserArchived() {
            assertTrue(TenantAccessValidator.isAccessibleToSystemUser(TenantStatusEnum.ARCHIVED, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("isAccessibleToSystemUser returns false for STATE_ADMIN accessing ARCHIVED")
        void isAccessibleToSystemUserStateAdminArchived() {
            assertFalse(TenantAccessValidator.isAccessibleToSystemUser(TenantStatusEnum.ARCHIVED, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("isAccessibleToSystemUser returns true for STATE_ADMIN accessing ACTIVE")
        void isAccessibleToSystemUserStateAdminActive() {
            assertTrue(TenantAccessValidator.isAccessibleToSystemUser(TenantStatusEnum.ACTIVE, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("isAccessibleToSystemUser returns true for SUPER_USER accessing ACTIVE")
        void isAccessibleToSystemUserSuperUserActive() {
            assertTrue(TenantAccessValidator.isAccessibleToSystemUser(TenantStatusEnum.ACTIVE, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("isAccessibleToSystemUser returns false for STAFF accessing ACTIVE")
        void isAccessibleToSystemUserStaffActive() {
            assertFalse(TenantAccessValidator.isAccessibleToSystemUser(TenantStatusEnum.ACTIVE, TenantAccessRole.STAFF));
        }

        @Test
        @DisplayName("isAccessibleToSystemUser returns true for SUPER_STATE_ADMIN accessing ACTIVE")
        void isAccessibleToSystemUserSuperStateAdminActive() {
            assertTrue(TenantAccessValidator.isAccessibleToSystemUser(TenantStatusEnum.ACTIVE, TenantAccessRole.SUPER_STATE_ADMIN));
        }

        @Test
        @DisplayName("isAccessibleToSystemUser returns true for SUPER_STATE_ADMIN accessing ARCHIVED")
        void isAccessibleToSystemUserSuperStateAdminArchived() {
            assertTrue(TenantAccessValidator.isAccessibleToSystemUser(TenantStatusEnum.ARCHIVED, TenantAccessRole.SUPER_STATE_ADMIN));
        }
    }
}
