package org.arghyam.jalsoochak.user.util;

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
        @DisplayName("SUPER_USER can access ONBOARDED tenant")
        void superUserCanAccessOnboarded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(1, false));
        }

        @Test
        @DisplayName("SUPER_USER can access CONFIGURED tenant")
        void superUserCanAccessConfigured() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(2, false));
        }

        @Test
        @DisplayName("SUPER_USER can access ACTIVE tenant")
        void superUserCanAccessActive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(3, false));
        }

        @Test
        @DisplayName("SUPER_USER can access INACTIVE tenant")
        void superUserCanAccessInactive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(0, false));
        }

        @Test
        @DisplayName("SUPER_USER can access DEGRADED tenant")
        void superUserCanAccessDegraded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(5, false));
        }

        @Test
        @DisplayName("SUPER_USER can access SUSPENDED tenant")
        void superUserCanAccessSuspended() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(4, false));
        }

        @Test
        @DisplayName("SUPER_USER can access ARCHIVED tenant")
        void superUserCanAccessArchived() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(6, false));
        }

        @Test
        @DisplayName("STATE_ADMIN can access ONBOARDED tenant")
        void stateAdminCanAccessOnboarded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(1, true));
        }

        @Test
        @DisplayName("STATE_ADMIN can access CONFIGURED tenant")
        void stateAdminCanAccessConfigured() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(2, true));
        }

        @Test
        @DisplayName("STATE_ADMIN can access ACTIVE tenant")
        void stateAdminCanAccessActive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(3, true));
        }

        @Test
        @DisplayName("STATE_ADMIN can access INACTIVE tenant")
        void stateAdminCanAccessInactive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(0, true));
        }

        @Test
        @DisplayName("STATE_ADMIN can access DEGRADED tenant")
        void stateAdminCanAccessDegraded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(5, true));
        }

        @Test
        @DisplayName("STATE_ADMIN can access SUSPENDED tenant")
        void stateAdminCanAccessSuspended() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateSystemUserAccess(4, true));
        }

        @Test
        @DisplayName("STATE_ADMIN cannot access ARCHIVED tenant")
        void stateAdminCannotAccessArchived() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateSystemUserAccess(6, true)
            );
            assertTrue(exception.getMessage().contains("archived"));
        }

        @Test
        @DisplayName("Invalid status code throws exception")
        void invalidStatusCodeThrowsException() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateSystemUserAccess(999, false)
            );
        }
    }

    @Nested
    @DisplayName("Staff User Access Validation")
    class StaffUserAccessTests {

        @Test
        @DisplayName("Staff can access ACTIVE tenant")
        void staffCanAccessActive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateStaffUserAccess(3));
        }

        @Test
        @DisplayName("Staff can access DEGRADED tenant")
        void staffCanAccessDegraded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateStaffUserAccess(5));
        }

        @Test
        @DisplayName("Staff cannot access ONBOARDED tenant")
        void staffCannotAccessOnboarded() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateStaffUserAccess(1)
            );
            assertTrue(exception.getMessage().contains("not yet complete"));
        }

        @Test
        @DisplayName("Staff cannot access CONFIGURED tenant")
        void staffCannotAccessConfigured() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateStaffUserAccess(2)
            );
            assertTrue(exception.getMessage().contains("not yet operational"));
        }

        @Test
        @DisplayName("Staff cannot access INACTIVE tenant")
        void staffCannotAccessInactive() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateStaffUserAccess(0)
            );
            assertTrue(exception.getMessage().contains("deactivated"));
        }

        @Test
        @DisplayName("Staff cannot access SUSPENDED tenant")
        void staffCannotAccessSuspended() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateStaffUserAccess(4)
            );
            assertTrue(exception.getMessage().contains("suspended"));
        }

        @Test
        @DisplayName("Staff cannot access ARCHIVED tenant")
        void staffCannotAccessArchived() {
            ForbiddenAccessException exception = assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateStaffUserAccess(6)
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
        @DisplayName("System user (adminLevel=2) can access ACTIVE tenant")
        void systemUserCanAccessActive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateTenantAccess(3, 2));
        }

        @Test
        @DisplayName("System user (adminLevel=2) can access ONBOARDED tenant")
        void systemUserCanAccessOnboarded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateTenantAccess(1, 2));
        }

        @Test
        @DisplayName("System user (adminLevel=2) cannot access ARCHIVED tenant")
        void systemUserCannotAccessArchived() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(6, 2)
            );
        }

        @Test
        @DisplayName("SUPER_USER (adminLevel=null is not staff) can access ARCHIVED tenant")
        void superUserCanAccessArchived() {
            // When adminLevel is null but it's actually a SUPER_USER, we need to pass a non-null value
            // This test demonstrates that SUPER_USER access is handled at a higher level
            assertDoesNotThrow(() -> TenantAccessValidator.validateTenantAccess(6, 1));
        }

        @Test
        @DisplayName("Staff user (adminLevel=null) can access ACTIVE tenant")
        void staffCanAccessActive() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateTenantAccess(3, null));
        }

        @Test
        @DisplayName("Staff user (adminLevel=null) can access DEGRADED tenant")
        void staffCanAccessDegraded() {
            assertDoesNotThrow(() -> TenantAccessValidator.validateTenantAccess(5, null));
        }

        @Test
        @DisplayName("Staff user (adminLevel=null) cannot access ONBOARDED tenant")
        void staffCannotAccessOnboarded() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(1, null)
            );
        }

        @Test
        @DisplayName("Staff user (adminLevel=null) cannot access CONFIGURED tenant")
        void staffCannotAccessConfigured() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(2, null)
            );
        }

        @Test
        @DisplayName("Staff user (adminLevel=null) cannot access INACTIVE tenant")
        void staffCannotAccessInactive() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(0, null)
            );
        }

        @Test
        @DisplayName("Staff user (adminLevel=null) cannot access SUSPENDED tenant")
        void staffCannotAccessSuspended() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(4, null)
            );
        }

        @Test
        @DisplayName("Staff user (adminLevel=null) cannot access ARCHIVED tenant")
        void staffCannotAccessArchived() {
            assertThrows(
                    ForbiddenAccessException.class,
                    () -> TenantAccessValidator.validateTenantAccess(6, null)
            );
        }
    }

    @Nested
    @DisplayName("Helper Methods")
    class HelperMethodsTests {

        @Test
        @DisplayName("isAccessibleToStaff returns true for ACTIVE")
        void isAccessibleToStaffActive() {
            assertTrue(TenantAccessValidator.isAccessibleToStaff(3));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns true for DEGRADED")
        void isAccessibleToStaffDegraded() {
            assertTrue(TenantAccessValidator.isAccessibleToStaff(5));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns false for ONBOARDED")
        void isAccessibleToStaffOnboarded() {
            assertFalse(TenantAccessValidator.isAccessibleToStaff(1));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns false for CONFIGURED")
        void isAccessibleToStaffConfigured() {
            assertFalse(TenantAccessValidator.isAccessibleToStaff(2));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns false for INACTIVE")
        void isAccessibleToStaffInactive() {
            assertFalse(TenantAccessValidator.isAccessibleToStaff(0));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns false for SUSPENDED")
        void isAccessibleToStaffSuspended() {
            assertFalse(TenantAccessValidator.isAccessibleToStaff(4));
        }

        @Test
        @DisplayName("isAccessibleToStaff returns false for ARCHIVED")
        void isAccessibleToStaffArchived() {
            assertFalse(TenantAccessValidator.isAccessibleToStaff(6));
        }

        @Test
        @DisplayName("isAccessibleToSystemUser returns true for SUPER_USER accessing ARCHIVED")
        void isAccessibleToSystemUserSuperUserArchived() {
            assertTrue(TenantAccessValidator.isAccessibleToSystemUser(6, false));
        }

        @Test
        @DisplayName("isAccessibleToSystemUser returns false for STATE_ADMIN accessing ARCHIVED")
        void isAccessibleToSystemUserStateAdminArchived() {
            assertFalse(TenantAccessValidator.isAccessibleToSystemUser(6, true));
        }

        @Test
        @DisplayName("isAccessibleToSystemUser returns true for STATE_ADMIN accessing ACTIVE")
        void isAccessibleToSystemUserStateAdminActive() {
            assertTrue(TenantAccessValidator.isAccessibleToSystemUser(3, true));
        }

        @Test
        @DisplayName("isAccessibleToSystemUser returns true for SUPER_USER accessing ACTIVE")
        void isAccessibleToSystemUserSuperUserActive() {
            assertTrue(TenantAccessValidator.isAccessibleToSystemUser(3, false));
        }
    }
}
