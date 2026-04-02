package org.arghyam.jalsoochak.user.util;

import org.arghyam.jalsoochak.user.constants.TenantStatusConstants;
import org.arghyam.jalsoochak.user.enums.TenantAccessRole;
import org.arghyam.jalsoochak.user.exceptions.ForbiddenAccessException;
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
        @DisplayName("Null role throws ForbiddenAccessException")
        void nullRoleThrowsForbiddenAccess() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateSystemUserAccess(TenantStatusConstants.ACTIVE, null)
            );
            assertTrue(exception.getMessage().contains("invalid user role"));
        }

        @Test
        @DisplayName("SUPER_USER can access ONBOARDED tenant")
        void superUserCanAccessOnboarded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusConstants.ONBOARDED, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("SUPER_USER can access CONFIGURED tenant")
        void superUserCanAccessConfigured() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusConstants.CONFIGURED, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("SUPER_USER can access ACTIVE tenant")
        void superUserCanAccessActive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusConstants.ACTIVE, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("SUPER_USER can access INACTIVE tenant")
        void superUserCanAccessInactive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusConstants.INACTIVE, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("SUPER_USER can access DEGRADED tenant")
        void superUserCanAccessDegraded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusConstants.DEGRADED, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("SUPER_USER can access SUSPENDED tenant")
        void superUserCanAccessSuspended() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusConstants.SUSPENDED, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("SUPER_USER can access ARCHIVED tenant")
        void superUserCanAccessArchived() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusConstants.ARCHIVED, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("STATE_ADMIN can access ONBOARDED tenant")
        void stateAdminCanAccessOnboarded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusConstants.ONBOARDED, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("STATE_ADMIN can access CONFIGURED tenant")
        void stateAdminCanAccessConfigured() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusConstants.CONFIGURED, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("STATE_ADMIN can access ACTIVE tenant")
        void stateAdminCanAccessActive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusConstants.ACTIVE, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("STATE_ADMIN can access INACTIVE tenant")
        void stateAdminCanAccessInactive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusConstants.INACTIVE, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("STATE_ADMIN can access DEGRADED tenant")
        void stateAdminCanAccessDegraded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusConstants.DEGRADED, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("STATE_ADMIN can access SUSPENDED tenant")
        void stateAdminCanAccessSuspended() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(TenantStatusConstants.SUSPENDED, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("STATE_ADMIN cannot access ARCHIVED tenant")
        void stateAdminCannotAccessArchived() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateSystemUserAccess(TenantStatusConstants.ARCHIVED, TenantAccessRole.STATE_ADMIN)
            );
            assertTrue(exception.getMessage().contains("archived"));
        }

        @Test
        @DisplayName("Invalid status code throws exception for STATE_ADMIN")
        void invalidStatusCodeThrowsException() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateSystemUserAccess(999, TenantAccessRole.STATE_ADMIN)
            );
        }

        @Test
        @DisplayName("STAFF role throws ForbiddenAccessException")
        void staffRoleThrowsForbiddenAccess() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateSystemUserAccess(TenantStatusConstants.ACTIVE, TenantAccessRole.STAFF)
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
            assertDoesNotThrow(() -> TenantAccessValidator.validateStaffUserAccess(TenantStatusConstants.ACTIVE));
        }

        @Test
        @DisplayName("Staff can access DEGRADED tenant")
        void staffCanAccessDegraded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateStaffUserAccess(TenantStatusConstants.DEGRADED));
        }

        @Test
        @DisplayName("Staff cannot access ONBOARDED tenant")
        void staffCannotAccessOnboarded() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateStaffUserAccess(TenantStatusConstants.ONBOARDED)
            );
            assertTrue(exception.getMessage().contains("not yet complete"));
        }

        @Test
        @DisplayName("Staff cannot access CONFIGURED tenant")
        void staffCannotAccessConfigured() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateStaffUserAccess(TenantStatusConstants.CONFIGURED)
            );
            assertTrue(exception.getMessage().contains("not yet operational"));
        }

        @Test
        @DisplayName("Staff cannot access INACTIVE tenant")
        void staffCannotAccessInactive() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateStaffUserAccess(TenantStatusConstants.INACTIVE)
            );
            assertTrue(exception.getMessage().contains("deactivated"));
        }

        @Test
        @DisplayName("Staff cannot access SUSPENDED tenant")
        void staffCannotAccessSuspended() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateStaffUserAccess(TenantStatusConstants.SUSPENDED)
            );
            assertTrue(exception.getMessage().contains("suspended"));
        }

        @Test
        @DisplayName("Staff cannot access ARCHIVED tenant")
        void staffCannotAccessArchived() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateStaffUserAccess(TenantStatusConstants.ARCHIVED)
            );
            assertTrue(exception.getMessage().contains("archived"));
        }

        @Test
        @DisplayName("Invalid status code throws exception")
        void invalidStatusCodeThrowsException() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateStaffUserAccess(999)
            );
        }
    }

    @Nested
    @DisplayName("Tenant Access Validation (Role-Based)")
    class TenantAccessTests {

        @Test
        @DisplayName("Null role throws ForbiddenAccessException")
        void nullRoleThrowsForbiddenAccess() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(TenantStatusConstants.ACTIVE, null)
            );
            assertTrue(exception.getMessage().contains("invalid user role"));
        }

        @Test
        @DisplayName("STATE_ADMIN can access ACTIVE tenant")
        void stateAdminCanAccessActive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateTenantAccess(TenantStatusConstants.ACTIVE, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("STATE_ADMIN can access ONBOARDED tenant")
        void stateAdminCanAccessOnboarded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateTenantAccess(TenantStatusConstants.ONBOARDED, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("STATE_ADMIN cannot access ARCHIVED tenant")
        void stateAdminCannotAccessArchived() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(TenantStatusConstants.ARCHIVED, TenantAccessRole.STATE_ADMIN)
            );
        }

        @Test
        @DisplayName("SUPER_USER can access ARCHIVED tenant")
        void superUserCanAccessArchived() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateTenantAccess(TenantStatusConstants.ARCHIVED, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("STAFF can access ACTIVE tenant")
        void staffCanAccessActive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateTenantAccess(TenantStatusConstants.ACTIVE, TenantAccessRole.STAFF));
        }

        @Test
        @DisplayName("STAFF can access DEGRADED tenant")
        void staffCanAccessDegraded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateTenantAccess(TenantStatusConstants.DEGRADED, TenantAccessRole.STAFF));
        }

        @Test
        @DisplayName("STAFF cannot access ONBOARDED tenant")
        void staffCannotAccessOnboarded() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(TenantStatusConstants.ONBOARDED, TenantAccessRole.STAFF)
            );
        }

        @Test
        @DisplayName("STAFF cannot access CONFIGURED tenant")
        void staffCannotAccessConfigured() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(TenantStatusConstants.CONFIGURED, TenantAccessRole.STAFF)
            );
        }

        @Test
        @DisplayName("STAFF cannot access INACTIVE tenant")
        void staffCannotAccessInactive() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(TenantStatusConstants.INACTIVE, TenantAccessRole.STAFF)
            );
        }

        @Test
        @DisplayName("STAFF cannot access SUSPENDED tenant")
        void staffCannotAccessSuspended() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(TenantStatusConstants.SUSPENDED, TenantAccessRole.STAFF)
            );
        }

        @Test
        @DisplayName("STAFF cannot access ARCHIVED tenant")
        void staffCannotAccessArchived() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(TenantStatusConstants.ARCHIVED, TenantAccessRole.STAFF)
            );
        }
    }

    @Nested
    @DisplayName("Helper Methods")
    class HelperMethodsTests {

        @Test
        @DisplayName("isAccessibleToStaff returns true for ACTIVE")
        void isAccessibleToStaffActive() {
            assertTrue(TenantAccessValidator.isAccessibleToStaff(TenantStatusConstants.ACTIVE));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns true for DEGRADED")
        void isAccessibleToStaffDegraded() {
            assertTrue(TenantAccessValidator.isAccessibleToStaff(TenantStatusConstants.DEGRADED));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns false for ONBOARDED")
        void isAccessibleToStaffOnboarded() {
            assertFalse(TenantAccessValidator.isAccessibleToStaff(TenantStatusConstants.ONBOARDED));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns false for CONFIGURED")
        void isAccessibleToStaffConfigured() {
            assertFalse(TenantAccessValidator.isAccessibleToStaff(TenantStatusConstants.CONFIGURED));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns false for INACTIVE")
        void isAccessibleToStaffInactive() {
            assertFalse(TenantAccessValidator.isAccessibleToStaff(TenantStatusConstants.INACTIVE));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns false for SUSPENDED")
        void isAccessibleToStaffSuspended() {
            assertFalse(TenantAccessValidator.isAccessibleToStaff(TenantStatusConstants.SUSPENDED));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns false for ARCHIVED")
        void isAccessibleToStaffArchived() {
            assertFalse(TenantAccessValidator.isAccessibleToStaff(TenantStatusConstants.ARCHIVED));
        }

        @Test
        @DisplayName("isAccessibleToSystemUser returns false when role is null")
        void isAccessibleToSystemUserNullRole() {
            assertFalse(TenantAccessValidator.isAccessibleToSystemUser(TenantStatusConstants.ACTIVE, null));
        }

        @Test
        @DisplayName("isAccessibleToSystemUser returns true for SUPER_USER accessing ARCHIVED")
        void isAccessibleToSystemUserSuperUserArchived() {
            assertTrue(TenantAccessValidator.isAccessibleToSystemUser(TenantStatusConstants.ARCHIVED, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("isAccessibleToSystemUser returns false for STATE_ADMIN accessing ARCHIVED")
        void isAccessibleToSystemUserStateAdminArchived() {
            assertFalse(TenantAccessValidator.isAccessibleToSystemUser(TenantStatusConstants.ARCHIVED, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("isAccessibleToSystemUser returns true for STATE_ADMIN accessing ACTIVE")
        void isAccessibleToSystemUserStateAdminActive() {
            assertTrue(TenantAccessValidator.isAccessibleToSystemUser(TenantStatusConstants.ACTIVE, TenantAccessRole.STATE_ADMIN));
        }

        @Test
        @DisplayName("isAccessibleToSystemUser returns true for SUPER_USER accessing ACTIVE")
        void isAccessibleToSystemUserSuperUserActive() {
            assertTrue(TenantAccessValidator.isAccessibleToSystemUser(TenantStatusConstants.ACTIVE, TenantAccessRole.SUPER_USER));
        }

        @Test
        @DisplayName("isAccessibleToSystemUser returns false for STAFF accessing ACTIVE")
        void isAccessibleToSystemUserStaffActive() {
            assertFalse(TenantAccessValidator.isAccessibleToSystemUser(TenantStatusConstants.ACTIVE, TenantAccessRole.STAFF));
        }
    }
}
