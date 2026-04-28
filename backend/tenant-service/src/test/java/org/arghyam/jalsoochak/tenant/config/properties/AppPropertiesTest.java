package org.arghyam.jalsoochak.tenant.config.properties;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppPropertiesTest {

    @Test
    void isSingleTenantMode_defaultsFalse() {
        AppProperties props = new AppProperties();
        assertFalse(props.isSingleTenantMode());
        assertTrue(props.isMultiTenantMode());
    }

    @Test
    void isSingleTenantMode_returnsTrueWhenEnabled() {
        AppProperties props = new AppProperties();
        props.setSingleTenantMode(true);
        assertTrue(props.isSingleTenantMode());
        assertFalse(props.isMultiTenantMode());
    }
}
