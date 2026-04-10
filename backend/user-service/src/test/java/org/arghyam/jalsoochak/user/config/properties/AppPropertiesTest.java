package org.arghyam.jalsoochak.user.config.properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppPropertiesTest {

    @Test
    void validateDeploymentMode_validSingleTenant_passes() {
        AppProperties props = new AppProperties();
        props.setDeploymentMode("SINGLE_TENANT");
        assertDoesNotThrow(props::validateDeploymentMode);
    }

    @Test
    void validateDeploymentMode_validMultiTenant_passes() {
        AppProperties props = new AppProperties();
        props.setDeploymentMode("MULTI_TENANT");
        assertDoesNotThrow(props::validateDeploymentMode);
    }

    @Test
    void validateDeploymentMode_caseInsensitive_passes() {
        AppProperties props = new AppProperties();
        props.setDeploymentMode("single_tenant");
        assertDoesNotThrow(props::validateDeploymentMode);
    }

    @Test
    void validateDeploymentMode_invalidValue_throwsIllegalStateException() {
        AppProperties props = new AppProperties();
        props.setDeploymentMode("INVALID_MODE");
        IllegalStateException ex = assertThrows(IllegalStateException.class, props::validateDeploymentMode);
        assertTrue(ex.getMessage().contains("INVALID_MODE"));
    }

    @Test
    void isSingleTenantMode_returnsTrueForSingleTenant() {
        AppProperties props = new AppProperties();
        props.setDeploymentMode("SINGLE_TENANT");
        assertTrue(props.isSingleTenantMode());
        assertFalse(props.isMultiTenantMode());
    }

    @Test
    void isMultiTenantMode_returnsTrueForMultiTenant() {
        AppProperties props = new AppProperties();
        props.setDeploymentMode("MULTI_TENANT");
        assertFalse(props.isSingleTenantMode());
        assertTrue(props.isMultiTenantMode());
    }
}
