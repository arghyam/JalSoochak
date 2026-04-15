package org.arghyam.jalsoochak.scheme.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void setGetAndClearSchema() {
        TenantContext.setSchema("tenant_ka");
        assertThat(TenantContext.getSchema()).isEqualTo("tenant_ka");

        TenantContext.clear();
        assertThat(TenantContext.getSchema()).isNull();
    }
}
