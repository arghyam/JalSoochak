package org.arghyam.jalsoochak.tenant.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TenantContext ThreadLocal Tests")
class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("getSchema returns null when never set")
    void getSchema_returnsNull_whenNeverSet() {
        assertThat(TenantContext.getSchema()).isNull();
    }

    @Test
    @DisplayName("setSchema stores value retrievable by getSchema")
    void setAndGetSchema_roundTrip() {
        TenantContext.setSchema("tenant_mp");

        assertThat(TenantContext.getSchema()).isEqualTo("tenant_mp");
    }

    @Test
    @DisplayName("setSchema overwrites any previous value")
    void setSchema_overwritesPreviousValue() {
        TenantContext.setSchema("tenant_mp");
        TenantContext.setSchema("tenant_tr");

        assertThat(TenantContext.getSchema()).isEqualTo("tenant_tr");
    }

    @Test
    @DisplayName("clear removes the stored schema value")
    void clear_removesSchema() {
        TenantContext.setSchema("tenant_mp");
        TenantContext.clear();

        assertThat(TenantContext.getSchema()).isNull();
    }

    @Test
    @DisplayName("clear is safe to call when nothing has been set")
    void clear_isIdempotent_whenNothingSet() {
        TenantContext.clear(); // should not throw
        assertThat(TenantContext.getSchema()).isNull();
    }

    @Test
    @DisplayName("clear is safe to call multiple times")
    void clear_isIdempotent_whenCalledMultipleTimes() {
        TenantContext.setSchema("tenant_mp");
        TenantContext.clear();
        TenantContext.clear(); // second clear should not throw

        assertThat(TenantContext.getSchema()).isNull();
    }
}
