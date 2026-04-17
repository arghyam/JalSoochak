package org.arghyam.jalsoochak.user.constants;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantStatusConstantsTest {

    @Test
    void testTenantStatusConstants() {
        // Test that all expected constants exist and have correct values
        assertEquals(0, TenantStatusConstants.INACTIVE);
        assertEquals(1, TenantStatusConstants.ONBOARDED);
        assertEquals(2, TenantStatusConstants.CONFIGURED);
        assertEquals(3, TenantStatusConstants.ACTIVE);
        assertEquals(4, TenantStatusConstants.SUSPENDED);
        assertEquals(5, TenantStatusConstants.DEGRADED);
        assertEquals(6, TenantStatusConstants.ARCHIVED);
    }

    @Test
    void testStatusValuesAreUnique() {
        // Ensure all status codes are unique
        int[] statuses = {
            TenantStatusConstants.INACTIVE,
            TenantStatusConstants.ONBOARDED,
            TenantStatusConstants.CONFIGURED,
            TenantStatusConstants.ACTIVE,
            TenantStatusConstants.SUSPENDED,
            TenantStatusConstants.DEGRADED,
            TenantStatusConstants.ARCHIVED
        };

        for (int i = 0; i < statuses.length; i++) {
            for (int j = i + 1; j < statuses.length; j++) {
                assertNotEquals(statuses[i], statuses[j], 
                    "Status codes should be unique: " + statuses[i] + " and " + statuses[j]);
            }
        }
    }

}
